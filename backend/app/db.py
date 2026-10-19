"""SQLAlchemy models.

Two tables: cards and jobs. The audit rule is that a row in `cards` may only ever hold
the URL and the generated card fields. There is deliberately no column for a
transcript, caption, OCR text, or media path - so even a careless query cannot leak
temp content that should have been deleted.
"""

from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import (
    DateTime,
    Enum as SAEnum,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, relationship

from app.models import CardStatus, JobStage


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def ensure_card_columns(sync_connection) -> None:
    """Idempotently adds columns introduced after the DB was first created.

    New features (description, hashtags, creator profile) add columns to the cards
    table, but `create_all` only creates missing *tables*, never missing columns.
    This cheaply ALTERs the dev SQLite DB forward. Alembic-style versioned
    migrations are deliberately deferred until the project has a real deployment.
    """
    from sqlalchemy import inspect as sqlalchemy_inspect

    columns = {
        column["name"]: column for column in sqlalchemy_inspect(sync_connection).get_columns("cards")
    }
    statements = [
        (
            "description",
            "ALTER TABLE cards ADD COLUMN description VARCHAR(400) NOT NULL DEFAULT ''",
        ),
        ("hashtags", "ALTER TABLE cards ADD COLUMN hashtags TEXT NOT NULL DEFAULT '[]'"),
        ("creator_name", "ALTER TABLE cards ADD COLUMN creator_name VARCHAR(200)"),
        ("creator_username", "ALTER TABLE cards ADD COLUMN creator_username VARCHAR(200)"),
        ("creator_bio", "ALTER TABLE cards ADD COLUMN creator_bio VARCHAR(400)"),
    ]
    for name, statement in statements:
        if name not in columns:
            sync_connection.exec_driver_sql(statement)


class Base(DeclarativeBase):
    pass


class Card(Base):
    __tablename__ = "cards"

    # The id is supplied by the client so the phone can insert locally and reconcile
    # later. It is a UUID string, not a server sequence, for that reason.
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(128), nullable=False, index=True)

    reel_url: Mapped[str] = mapped_column(String(2048), nullable=False)

    title: Mapped[str] = mapped_column(String(200), nullable=False, default="")
    summary: Mapped[str] = mapped_column(Text, nullable=False, default="")
    description: Mapped[str] = mapped_column(String(400), nullable=False, default="")
    hashtags: Mapped[str] = mapped_column(Text, nullable=False, default="[]")
    creator_name: Mapped[str | None] = mapped_column(String(200))
    creator_username: Mapped[str | None] = mapped_column(String(200))
    creator_bio: Mapped[str | None] = mapped_column(String(400))
    key_insights: Mapped[str] = mapped_column(Text, nullable=False, default="[]")
    action_items: Mapped[str] = mapped_column(Text, nullable=False, default="[]")
    topics: Mapped[str] = mapped_column(Text, nullable=False, default="[]")

    status: Mapped[CardStatus] = mapped_column(
        SAEnum(CardStatus, native_enum=False, length=16),
        nullable=False,
        default=CardStatus.QUEUED,
    )

    provider: Mapped[str | None] = mapped_column(String(64))
    thumbnail_url: Mapped[str | None] = mapped_column(String(2048))
    last_error: Mapped[str | None] = mapped_column(Text)

    # Unix epoch day, matching the client, so snooze comparisons are trivial.
    snooze_until_epoch_day: Mapped[int | None] = mapped_column(Integer)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow
    )
    reviewed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    jobs: Mapped[list["Job"]] = relationship(
        back_populates="card", cascade="all, delete-orphan", lazy="selectin"
    )

    __table_args__ = (
        Index("ix_cards_user_status", "user_id", "status"),
        Index("ix_cards_user_created", "user_id", "created_at"),
    )

    @property
    def is_deleted(self) -> bool:
        return self.status is CardStatus.DELETED


class Job(Base):
    __tablename__ = "jobs"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    card_id: Mapped[str] = mapped_column(ForeignKey("cards.id", ondelete="CASCADE"), index=True)
    user_id: Mapped[str] = mapped_column(String(128), nullable=False, index=True)

    stage: Mapped[JobStage] = mapped_column(
        SAEnum(JobStage, native_enum=False, length=16),
        nullable=False,
        default=JobStage.QUEUED,
    )
    error: Mapped[str | None] = mapped_column(Text)
    provider: Mapped[str | None] = mapped_column(String(64))
    attempt: Mapped[int] = mapped_column(Integer, nullable=False, default=0)

    # Diagnostic breadcrumbs only. Deliberately short and never raw content: these
    # strings can end up in logs, and the point of the temp-file policy is that reel
    # content does not persist. e.g. "oembed:title+caption", "ocr:8 frames, 31 lines".
    notes: Mapped[str | None] = mapped_column(Text)

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, default=utcnow, onupdate=utcnow
    )
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    card: Mapped[Card] = relationship(back_populates="jobs", lazy="selectin")
