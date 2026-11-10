package com.jqc.webplayer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeFavoriteDao : FavoriteDao {
    val rows = mutableListOf<FavoriteEntity>()
    private var nextId = 1L
    private val state = MutableStateFlow<List<FavoriteEntity>>(emptyList())

    private fun publish() {
        state.value = rows.sortedByDescending { it.createdAt }
    }

    override fun observeAll(): Flow<List<FavoriteEntity>> = state

    override suspend fun findByPageUrl(pageUrl: String): FavoriteEntity? =
        rows.firstOrNull { it.pageUrl == pageUrl }

    override suspend fun insert(entity: FavoriteEntity): Long {
        val id = if (entity.id == 0L) nextId++ else entity.id
        val saved = entity.copy(id = id)
        rows.removeAll { it.pageUrl == saved.pageUrl || it.id == saved.id }
        rows += saved
        publish()
        return id
    }

    override suspend fun rename(id: Long, title: String) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) {
            rows[index] = rows[index].copy(title = title)
            publish()
        }
    }

    override suspend fun delete(id: Long) {
        rows.removeAll { it.id == id }
        publish()
    }
}

class FavoritesRepositoryTest {
    @Test
    fun toggleAddsThenRemoves() = runTest {
        val dao = FakeFavoriteDao()
        val repo = FavoritesRepository(dao) { 1_000L }

        assertTrue(repo.toggle("https://example.com/p", "标题", null))
        assertTrue(repo.isFavorite("https://example.com/p"))
        assertEquals(1, repo.observe().first().size)

        assertFalse(repo.toggle("https://example.com/p", "标题", null))
        assertFalse(repo.isFavorite("https://example.com/p"))
    }
}
