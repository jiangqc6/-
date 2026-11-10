package com.jqc.webplayer.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "watch_sessions",
    indices = [
        Index(value = ["identityUrl"], unique = true),
        Index(value = ["updatedAt"]),
    ],
)
data class WatchSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val identityUrl: String,
    val pageUrl: String,
    val pageTitle: String,
    val videoKey: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Dao
interface WatchSessionDao {
    @Query("SELECT * FROM watch_sessions WHERE identityUrl = :identityUrl LIMIT 1")
    suspend fun findByIdentity(identityUrl: String): WatchSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchSessionEntity): Long

    @Query("DELETE FROM watch_sessions WHERE identityUrl = :identityUrl")
    suspend fun deleteByIdentity(identityUrl: String)

    @Query("SELECT * FROM watch_sessions ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<WatchSessionEntity>>

    @Query("DELETE FROM watch_sessions WHERE id NOT IN (SELECT id FROM watch_sessions ORDER BY updatedAt DESC LIMIT :limit)")
    suspend fun trimTo(limit: Int)
}
