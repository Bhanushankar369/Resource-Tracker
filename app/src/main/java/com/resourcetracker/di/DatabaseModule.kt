package com.resourcetracker.di

import android.content.Context
import androidx.room.Room
import com.resourcetracker.BuildConfig
import com.resourcetracker.data.local.AppDatabase
import com.resourcetracker.data.local.CardDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            // A destructive fallback throws away unsynced review actions on upgrade, so
            // it is allowed only in debug builds. In release a missing migration fails
            // loudly instead, which is the signal to ship a real migration.
            .apply {
                if (BuildConfig.DEBUG) {
                    fallbackToDestructiveMigration()
                }
            }
            .build()

    @Provides
    @Singleton
    fun cardDao(database: AppDatabase): CardDao = database.cardDao()
}
