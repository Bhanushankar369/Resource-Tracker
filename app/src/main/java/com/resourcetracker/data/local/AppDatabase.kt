package com.resourcetracker.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CardEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao

    companion object {
        const val NAME = "resourcetracker.db"

        /**
         * v1 -> v2 adds the metadata fields that make a re-summarised reel useful on
         * its own: description, hashtags and creator profile, plus jobId so the app
         * can follow the backend job to completion. ALTER TABLE never rewrites rows,
         * so nothing the user has already reviewed is disturbed.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN description TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cards ADD COLUMN hashtags TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE cards ADD COLUMN creatorName TEXT")
                db.execSQL("ALTER TABLE cards ADD COLUMN creatorUsername TEXT")
                db.execSQL("ALTER TABLE cards ADD COLUMN creatorBio TEXT")
                db.execSQL("ALTER TABLE cards ADD COLUMN jobId TEXT")
            }
        }
    }
}