package com.jqc.webplayer.data

import com.jqc.webplayer.core.PageIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class WatchSessionRepository(
    private val dao: WatchSessionDao,
    private val historyDao: HistoryDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun findForPage(pageUrl: String): WatchSessionEntity? {
        val identity = PageIdentity.key(pageUrl) ?: return null
        return dao.findByIdentity(identity)
    }

    /** 首页“继续观看”：最近打开过、且没看完的网页视频会话。 */
    fun observeRecent(limit: Int = MAX_RECENT): Flow<List<WatchSessionEntity>> =
        dao.observeRecent(limit).map { sessions -> sessions.filterNot(::isFinished) }

    /**
     * 网页视频进度落库。太短的视频（广告）和刚打开几秒的位置不记，
     * 避免把“点开即走”写进历史。
     */
    suspend fun save(
        pageUrl: String,
        pageTitle: String,
        videoKey: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        val identity = PageIdentity.key(pageUrl) ?: return
        if (durationMs < MIN_VIDEO_MS) return
        if (videoKey.isBlank() || videoKey.length > MAX_VIDEO_KEY) return
        if (positionMs < MIN_SAVE_MS && positionMs < durationMs - MIN_SAVE_MS) return

        val existing = dao.findByIdentity(identity)
        val position = positionMs.coerceIn(0, durationMs)
        dao.upsert(
            WatchSessionEntity(
                id = existing?.id ?: 0,
                identityUrl = identity,
                pageUrl = pageUrl,
                pageTitle = pageTitle.ifBlank { pageUrl },
                videoKey = videoKey,
                positionMs = position,
                durationMs = durationMs,
                updatedAt = clock(),
            )
        )
        // 历史行存在时同步进度，首页列表就能直接显示“已看 30%”。
        historyDao.updateProgress(pageUrl, position, durationMs, clock())
        dao.trimTo(MAX_SESSIONS)
    }

    /** 用户选了“从头播放”：忘掉本次进度，历史进度条也归零。 */
    suspend fun clear(pageUrl: String) {
        val identity = PageIdentity.key(pageUrl) ?: return
        val existing = dao.findByIdentity(identity) ?: return
        dao.deleteByIdentity(identity)
        historyDao.updateProgress(existing.pageUrl, 0, 0, clock())
    }

    private companion object {
        const val MIN_VIDEO_MS = 30_000L
        const val MIN_SAVE_MS = 5_000L
        const val MAX_VIDEO_KEY = 4096
        const val MAX_SESSIONS = 100
        const val MAX_RECENT = 3
        const val FINISHED_PERCENT = 95
    }

    private fun isFinished(session: WatchSessionEntity): Boolean =
        session.durationMs > 0 &&
            session.positionMs * 100 / session.durationMs >= FINISHED_PERCENT
}
