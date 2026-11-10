package com.jqc.webplayer.core

class MediaCollector(private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private var pageUrl: String? = null
    private val candidates = LinkedHashMap<String, MediaCandidate>()

    fun startPage(url: String) = synchronized(lock) {
        pageUrl = url
        candidates.clear()
    }

    fun updatePage(url: String) = synchronized(lock) {
        pageUrl = url
    }

    fun offer(rawUrl: String, source: SniffSource): List<MediaCandidate> = synchronized(lock) {
        val candidate = MediaUrlClassifier.classify(rawUrl, pageUrl, source, clock())
        if (candidate != null && !candidates.containsKey(candidate.url)) {
            candidates[candidate.url] = candidate
            if (candidates.size > 50) {
                val oldest = candidates.values.minByOrNull { it.discoveredAt }
                if (oldest != null) candidates.remove(oldest.url)
            }
        }
        snapshotLocked()
    }

    fun setQuality(url: String, label: String): List<MediaCandidate> = synchronized(lock) {
        candidates[url]?.let { candidates[url] = it.copy(qualityLabel = label) }
        snapshotLocked()
    }

    fun snapshot(): List<MediaCandidate> = synchronized(lock) { snapshotLocked() }

    fun clear() = synchronized(lock) { candidates.clear() }

    private fun snapshotLocked(): List<MediaCandidate> {
        val pageHost = pageUrl?.let { MediaUrlClassifier.hostOf(it) }
        return MediaUrlClassifier.rank(candidates.values.toList(), pageHost)
    }
}
