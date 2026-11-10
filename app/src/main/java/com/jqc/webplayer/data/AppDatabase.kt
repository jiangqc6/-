package com.jqc.webplayer.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [HistoryEntity::class, FavoriteEntity::class, WatchSessionEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun watchSessionDao(): WatchSessionDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_sessions` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`identityUrl` TEXT NOT NULL, " +
                        "`pageUrl` TEXT NOT NULL, " +
                        "`pageTitle` TEXT NOT NULL, " +
                        "`videoKey` TEXT NOT NULL, " +
                        "`positionMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_watch_sessions_identityUrl` " +
                        "ON `watch_sessions` (`identityUrl`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watch_sessions_updatedAt` " +
                        "ON `watch_sessions` (`updatedAt`)"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "webplayer.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
