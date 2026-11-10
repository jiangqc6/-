package com.jqc.webplayer.data

import com.jqc.webplayer.core.StreamType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeHistoryDao : HistoryDao {
    val rows = mutableListOf<HistoryEntity>()
    private var nextId = 1L
    private val state = MutableStateFlow<List<HistoryEntity>>(emptyList())

    private fun publish() {
        state.value = rows.sortedByDescending { it.updatedAt }
    }

    override fun observeAll(): Flow<List<HistoryEntity>> = state

    override suspend fun findByPageUrl(pageUrl: String): HistoryEntity? =
        rows.firstOrNull { it.pageUrl == pageUrl }

    override suspend fun snapshot(): List<HistoryEntity> = rows.toList()

    override suspend fun insert(entity: HistoryEntity): Long {
        val id = if (entity.id == 0L) nextId++ else entity.id
        val saved = entity.copy(id = id)
        rows.removeAll { it.pageUrl == saved.pageUrl || it.id == saved.id }
        rows += saved
        publish()
        return id
    }

    override suspend fun updateProgress(pageUrl: String, positionMs: Long, durationMs: Long, updatedAt: Long) {
        val index = rows.indexOfFirst { it.pageUrl == pageUrl }
        if (index >= 0) {
            rows[index] = rows[index].copy(positionMs = positionMs, durationMs = durationMs, updatedAt = updatedAt)
            publish()
        }
    }

    override suspend fun updateVisit(id: Long, pageTitle: String, siteHost: String, updatedAt: Long) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(pageTitle = pageTitle, siteHost = siteHost, updatedAt = updatedAt)
            publish()
        }
    }

    override suspend fun updatePage(id: Long, pageUrl: String, pageTitle: String, siteHost: String, updatedAt: Long) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(
                pageUrl = pageUrl,
                pageTitle = pageTitle,
                siteHost = siteHost,
                updatedAt = updatedAt,
            )
            publish()
        }
    }

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
        publish()
    }

    override suspend fun clear() {
        rows.clear()
        publish()
    }

    override suspend fun trimTo(limit: Int) {
        val kept = rows.sortedByDescending { it.updatedAt }.take(limit)
        rows.clear()
        rows.addAll(kept)
        publish()
    }
}

class HistoryRepositoryTest {
    @Test
    fun recordPlaybackDedupesByPageUrl() = runTest {
        val dao = FakeHistoryDao()
        val repo = HistoryRepository(dao) { 1_000L }

        repo.recordPlayback("https://m.example.com/play/1", "第一集", "https://cdn/a.m3u8", StreamType.HLS, "720P", 5_000, 60_000)
        repo.recordPlayback("https://m.example.com/play/1", "第一集", "https://cdn/b.m3u8", StreamType.HLS, "1080P", 20_000, 60_000)

        val rows = repo.observe().first()
        assertEquals(1, rows.size)
        assertEquals(20_000L, rows[0].positionMs)
        assertEquals("1080P", rows[0].qualityLabel)
        assertEquals("m.example.com", rows[0].siteHost)
    }

    @Test
    fun updateProgressOnlyChangesProgress() = runTest {
        val dao = FakeHistoryDao()
        val repo = HistoryRepository(dao) { 1_000L }
        repo.recordPlayback("https://example.com/p", "Title", "https://cdn/a.mp4", StreamType.MP4, null, 0, 100_000)
        repo.updateProgress("https://example.com/p", 30_000, 100_000)
        val row = repo.observe().first().single()
        assertEquals(30_000L, row.positionMs)
        assertEquals("Title", row.pageTitle)
    }

    @Test
    fun recordVisitCreatesPageEntry() = runTest {
        val dao = FakeHistoryDao()
        val repo = HistoryRepository(dao) { 2_000L }

        repo.recordVisit("https://m.example.com/vod/1", "第一集")

        val row = repo.observe().first().single()
        assertEquals("https://m.example.com/vod/1", row.pageUrl)
        assertEquals("第一集", row.pageTitle)
        assertEquals("m.example.com", row.siteHost)
        assertEquals(null, row.videoUrl)
        assertEquals(0L, row.positionMs)
    }

    @Test
    fun recordVisitKeepsPlaybackInfo() = runTest {
        val dao = FakeHistoryDao()
        var now = 1_000L
        val repo = HistoryRepository(dao) { now }
        repo.recordPlayback("https://example.com/p", "旧标题", "https://cdn/a.m3u8", StreamType.HLS, "720P", 30_000, 100_000)

        now = 5_000L
        repo.recordVisit("https://example.com/p", "新标题")

        val row = repo.observe().first().single()
        assertEquals("新标题", row.pageTitle)
        assertEquals(5_000L, row.updatedAt)
        assertEquals("https://cdn/a.m3u8", row.videoUrl)
        assertEquals(30_000L, row.positionMs)
        assertEquals(100_000L, row.durationMs)
    }

    @Test
    fun recordVisitIgnoresNonHttpUrls() = runTest {
        val dao = FakeHistoryDao()
        val repo = HistoryRepository(dao) { 1_000L }

        repo.recordVisit("about:blank", "")
        repo.recordVisit("file:///sdcard/x.html", "本地")

        assertEquals(0, repo.observe().first().size)
    }

    @Test
    fun recordVisitMergesTrackingParamVariants() = runTest {
        val dao = FakeHistoryDao()
        val repo = HistoryRepository(dao) { 1_000L }

        repo.recordVisit("https://m.example.com/vod/1?utm_source=wx", "第一集")
        repo.recordVisit("https://m.example.com/vod/1?utm_medium=feed&spm=abc", "第一集")

        val rows = repo.observe().first()
        assertEquals(1, rows.size)
        // 合并后保留最近一次打开的链接，分享参数不同也算同一页。
        assertEquals("https://m.example.com/vod/1?utm_medium=feed&spm=abc", rows[0].pageUrl)
    }

    @Test
    fun recordPlaybackMergesTrackingParamVariants() = runTest {
        val dao = FakeHistoryDao()
        var now = 1_000L
        val repo = HistoryRepository(dao) { now }

        repo.recordPlayback(
            "https://m.example.com/vod/1?utm_source=wx", "第一集",
            "https://cdn/a.m3u8", StreamType.HLS, "720P", 5_000, 60_000,
        )
        now = 9_000L
        repo.recordPlayback(
            "https://m.example.com/vod/1?utm_source=qq", "第一集",
            "https://cdn/b.m3u8", StreamType.HLS, "1080P", 20_000, 60_000,
        )

        val rows = repo.observe().first()
        assertEquals(1, rows.size)
        assertEquals("https://m.example.com/vod/1?utm_source=qq", rows[0].pageUrl)
        assertEquals(20_000L, rows[0].positionMs)
        assertEquals("https://cdn/b.m3u8", rows[0].videoUrl)
    }

    @Test
    fun recordVisitKeepsSemanticParamsDistinct() = runTest {
        val dao = FakeHistoryDao()
        val repo = HistoryRepository(dao) { 1_000L }

        repo.recordVisit("https://m.example.com/play?id=1", "第一集")
        repo.recordVisit("https://m.example.com/play?id=2", "第二集")

        assertEquals(2, repo.observe().first().size)
    }
}
