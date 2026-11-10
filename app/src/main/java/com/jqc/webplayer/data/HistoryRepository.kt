package com.jqc.webplayer.data

import com.jqc.webplayer.core.MediaUrlClassifier
import com.jqc.webplayer.core.PageIdentity
import com.jqc.webplayer.core.StreamType
import kotlinx.coroutines.flow.Flow

class HistoryRepository(
    private val dao: HistoryDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observe(): Flow<List<HistoryEntity>> = dao.observeAll()

    suspend fun find(pageUrl: String): HistoryEntity? = dao.findByPageUrl(pageUrl)

    /**
     * 记录一次网页浏览：打开过的页面都进历史，点击时可回到该网页。
     * 若该页已有播放记录，只更新时间与标题，保留视频地址和进度。
     */
    suspend fun recordVisit(pageUrl: String, title: String) {
        if (!pageUrl.startsWith("http://") && !pageUrl.startsWith("https://")) return
        val host = MediaUrlClassifier.hostOf(pageUrl) ?: ""
        val existing = findSamePage(pageUrl)
        if (existing == null) {
            dao.insert(
                HistoryEntity(
                    pageUrl = pageUrl,
                    pageTitle = title.ifBlank { pageUrl },
                    siteHost = host,
                    videoUrl = null,
                    streamType = null,
                    qualityLabel = null,
                    positionMs = 0,
                    durationMs = 0,
                    updatedAt = clock(),
                )
            )
        } else {
            // 同一页面的分享链接常带不同跟踪参数，保留最近一次打开的链接，
            // 下次从历史点进去就是用户刚看到的那个地址。
            dao.updatePage(
                id = existing.id,
                pageUrl = pageUrl,
                pageTitle = title.ifBlank { existing.pageTitle },
                siteHost = host.ifBlank { existing.siteHost },
                updatedAt = clock(),
            )
        }
        dao.trimTo(200)
    }

    suspend fun recordPlayback(
        pageUrl: String,
        title: String,
        videoUrl: String,
        type: StreamType,
        quality: String?,
        positionMs: Long,
        durationMs: Long,
    ) {
        val existing = findSamePage(pageUrl)
        dao.insert(
            HistoryEntity(
                id = existing?.id ?: 0,
                pageUrl = pageUrl,
                pageTitle = title.ifBlank { existing?.pageTitle ?: pageUrl },
                siteHost = MediaUrlClassifier.hostOf(pageUrl) ?: "",
                videoUrl = videoUrl,
                streamType = type.name,
                qualityLabel = quality ?: existing?.qualityLabel,
                positionMs = positionMs.coerceAtLeast(0),
                durationMs = durationMs.coerceAtLeast(0),
                updatedAt = clock(),
            )
        )
        dao.trimTo(200)
    }

    suspend fun updateProgress(pageUrl: String, positionMs: Long, durationMs: Long) {
        dao.updateProgress(pageUrl, positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), clock())
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun clear() = dao.clear()

    /**
     * 先按精确地址找，找不到再按页面身份找：分享链接的 utm/spm 等跟踪参数不同，
     * 但用户看的是同一页，历史里不该出现多条。
     */
    private suspend fun findSamePage(pageUrl: String): HistoryEntity? {
        dao.findByPageUrl(pageUrl)?.let { return it }
        val identity = PageIdentity.key(pageUrl) ?: return null
        return dao.snapshot().firstOrNull { PageIdentity.key(it.pageUrl) == identity }
    }
}
