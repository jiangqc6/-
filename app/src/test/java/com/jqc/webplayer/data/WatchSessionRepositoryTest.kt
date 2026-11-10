package com.jqc.webplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private class FakeWatchSessionDao : WatchSessionDao {
    val rows = mutableListOf<WatchSessionEntity>()
    private var nextId = 1L

    override suspend fun findByIdentity(identityUrl: String): WatchSessionEntity? =
        rows.firstOrNull { it.identityUrl == identityUrl }

    override fun observeRecent(limit: Int) = flowOf(
        rows.sortedByDescending { it.updatedAt }.take(limit)
    )

    override suspend fun upsert(entity: WatchSessionEntity): Long {
        val id = if (entity.id == 0L) nextId++ else entity.id
        val saved = entity.copy(id = id)
        rows.removeAll { it.identityUrl == saved.identityUrl || it.id == saved.id }
        rows += saved
        return id
    }

    override suspend fun deleteByIdentity(identityUrl: String) {
        rows.removeAll { it.identityUrl == identityUrl }
    }

    override suspend fun trimTo(limit: Int) {
        val kept = rows.sortedByDescending { it.updatedAt }.take(limit)
        rows.clear()
        rows.addAll(kept)
    }
}

private class RecordingHistoryDao : HistoryDao {
    val updates = mutableListOf<Triple<String, Long, Long>>()

    override fun observeAll() = throw UnsupportedOperationException()

    override suspend fun findByPageUrl(pageUrl: String): HistoryEntity? = null

    override suspend fun snapshot(): List<HistoryEntity> = emptyList()

    override suspend fun insert(entity: HistoryEntity): Long = 0

    override suspend fun updateProgress(pageUrl: String, positionMs: Long, durationMs: Long, updatedAt: Long) {
        updates += Triple(pageUrl, positionMs, durationMs)
    }

    override suspend fun updateVisit(id: Long, pageTitle: String, siteHost: String, updatedAt: Long) = Unit

    override suspend fun updatePage(id: Long, pageUrl: String, pageTitle: String, siteHost: String, updatedAt: Long) = Unit

    override suspend fun delete(id: Long) = Unit

    override suspend fun clear() = Unit

    override suspend fun trimTo(limit: Int) = Unit
}

class WatchSessionRepositoryTest {
    @Test
    fun saveStoresLatestPositionAndUpdatesHistory() = runTest {
        val dao = FakeWatchSessionDao()
        val history = RecordingHistoryDao()
        val repo = WatchSessionRepository(dao, history) { 5_000L }

        repo.save(
            pageUrl = "https://m.example.com/vod/play/1?utm_source=wx",
            pageTitle = "第一集",
            videoKey = "https://cdn.example.com/1.m3u8",
            positionMs = 120_000,
            durationMs = 600_000,
        )

        assertEquals(1, dao.rows.size)
        assertEquals(120_000L, dao.rows[0].positionMs)
        assertEquals(600_000L, dao.rows[0].durationMs)
        assertEquals("https://cdn.example.com/1.m3u8", dao.rows[0].videoKey)
        assertEquals(listOf(Triple("https://m.example.com/vod/play/1?utm_source=wx", 120_000L, 600_000L)), history.updates)
    }

    @Test
    fun saveIgnoresShortVideosAndTinyPositions() = runTest {
        val dao = FakeWatchSessionDao()
        val repo = WatchSessionRepository(dao, RecordingHistoryDao()) { 1_000L }

        repo.save("https://a.com/v", "广告", "k1", 8_000, 20_000)
        repo.save("https://a.com/v", "刚打开", "k1", 2_000, 300_000)

        assertEquals(0, dao.rows.size)
    }

    @Test
    fun trackingParamVariantsShareOneSessionAndCanBeFound() = runTest {
        val dao = FakeWatchSessionDao()
        val repo = WatchSessionRepository(dao, RecordingHistoryDao()) { 1_000L }

        repo.save("https://a.com/v?id=1&utm_source=wx", "标题", "k", 20_000, 100_000)
        repo.save("https://a.com/v?id=1&utm_medium=feed", "标题", "k", 40_000, 100_000)

        assertEquals(1, dao.rows.size)
        assertEquals(40_000L, dao.rows[0].positionMs)
        assertEquals(40_000L, repo.findForPage("https://a.com/v?id=1&utm_campaign=x")?.positionMs)
    }

    @Test
    fun invalidPageOrKeyIsIgnored() = runTest {
        val dao = FakeWatchSessionDao()
        val repo = WatchSessionRepository(dao, RecordingHistoryDao()) { 1_000L }

        repo.save("about:blank", "x", "k", 20_000, 100_000)
        repo.save("https://a.com/v", "x", "", 20_000, 100_000)

        assertEquals(0, dao.rows.size)
        assertNull(repo.findForPage("about:blank"))
    }

    @Test
    fun positionIsClampedIntoDuration() = runTest {
        val dao = FakeWatchSessionDao()
        val repo = WatchSessionRepository(dao, RecordingHistoryDao()) { 1_000L }

        repo.save("https://a.com/v", "x", "k", 200_000, 100_000)

        assertEquals(100_000L, dao.rows[0].positionMs)
    }

    @Test
    fun clearForgetsSessionAndResetsHistoryProgress() = runTest {
        val dao = FakeWatchSessionDao()
        val history = RecordingHistoryDao()
        val repo = WatchSessionRepository(dao, history) { 1_000L }
        repo.save("https://a.com/v?utm_source=wx", "x", "k", 40_000, 100_000)

        repo.clear("https://a.com/v")

        assertEquals(0, dao.rows.size)
        assertNull(repo.findForPage("https://a.com/v"))
        assertEquals(Triple("https://a.com/v?utm_source=wx", 0L, 0L), history.updates.last())
    }

    @Test
    fun recentHidesNearlyFinishedAndKeepsLatestFirst() = runTest {
        val dao = FakeWatchSessionDao()
        var now = 1_000L
        val repo = WatchSessionRepository(dao, RecordingHistoryDao()) { now }
        repo.save("https://a.com/1", "第一集", "k1", 40_000, 100_000)
        now = 2_000L
        repo.save("https://a.com/2", "第二集", "k2", 97_000, 100_000)
        now = 3_000L
        repo.save("https://a.com/3", "第三集", "k3", 200_000, 600_000)

        val recent = repo.observeRecent().first()

        assertEquals(listOf("第三集", "第一集"), recent.map { it.pageTitle })
    }
}
