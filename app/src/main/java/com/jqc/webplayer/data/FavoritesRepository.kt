package com.jqc.webplayer.data

import kotlinx.coroutines.flow.Flow

class FavoritesRepository(
    private val dao: FavoriteDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observe(): Flow<List<FavoriteEntity>> = dao.observeAll()

    suspend fun isFavorite(pageUrl: String): Boolean = dao.findByPageUrl(pageUrl) != null

    suspend fun toggle(pageUrl: String, title: String, videoUrl: String?): Boolean {
        val existing = dao.findByPageUrl(pageUrl)
        return if (existing != null) {
            dao.delete(existing.id)
            false
        } else {
            dao.insert(
                FavoriteEntity(
                    pageUrl = pageUrl,
                    title = title.ifBlank { pageUrl },
                    videoUrl = videoUrl,
                    createdAt = clock(),
                )
            )
            true
        }
    }

    suspend fun rename(id: Long, title: String) = dao.rename(id, title.ifBlank { "未命名" })

    suspend fun delete(id: Long) = dao.delete(id)
}
