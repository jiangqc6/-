package com.jqc.webplayer.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history", indices = [Index(value = ["pageUrl"], unique = true)])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageUrl: String,
    val pageTitle: String,
    val siteHost: String,
    val videoUrl: String?,
    val streamType: String?,
    val qualityLabel: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE pageUrl = :pageUrl LIMIT 1")
    suspend fun findByPageUrl(pageUrl: String): HistoryEntity?

    // 页面身份去重需要看全量：分享链接常带不同跟踪参数，精确查 pageUrl 找不到同一页面。
    @Query("SELECT * FROM history")
    suspend fun snapshot(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HistoryEntity): Long

    @Query("UPDATE history SET positionMs = :positionMs, durationMs = :durationMs, updatedAt = :updatedAt WHERE pageUrl = :pageUrl")
    suspend fun updateProgress(pageUrl: String, positionMs: Long, durationMs: Long, updatedAt: Long)

    @Query("UPDATE history SET pageTitle = :pageTitle, siteHost = :siteHost, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateVisit(id: Long, pageTitle: String, siteHost: String, updatedAt: Long)

    @Query("UPDATE history SET pageUrl = :pageUrl, pageTitle = :pageTitle, siteHost = :siteHost, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePage(id: Long, pageUrl: String, pageTitle: String, siteHost: String, updatedAt: Long)

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY updatedAt DESC LIMIT :limit)")
    suspend fun trimTo(limit: Int)
}
