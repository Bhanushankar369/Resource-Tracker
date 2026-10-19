"""Single-user identity.

The authentication layer has been removed: this deployment is single-tenant and
every request operates on the same fixed local user. `current_user_id` deliberately
keeps the shape of the old `Depends(...)` dependency, so routes and the `user_id`
ownership columns are unchanged - only the *source* of the id changed (a configured
constant instead of a verified Google token).

The id is still threaded through every card/job row so the ownership boundary and
the existing query filters keep working unchanged.
"""

from __future__ import annotations

from fastapi import Depends

from app.config import Settings, get_settings


async def current_user_id(settings: Settings = Depends(get_settings)) -> str:
    """The single local user that owns every card in this deployment."""
    return settings.local_user_id
