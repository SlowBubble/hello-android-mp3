package com.example.myapp.ui.screens

import com.example.myapp.data.Song

object QueueUtils {
    private const val BYTES_PER_SECOND = 16000L

    fun effectiveDuration(song: Song, savedDuration: Long? = null): Long {
        return savedDuration?.takeIf { it > 0 }
            ?: song.duration.takeIf { it > 0 }
            ?: estimateDuration(song.fileSize)
    }

    private fun estimateDuration(bytes: Long): Long {
        if (bytes <= 0) return 0L
        return (bytes / BYTES_PER_SECOND) * 1000L
    }

    fun buildVisibleQueue(
        songs: List<Song>,
        sortMode: Int,
        hiddenTrackKeys: Iterable<String> = emptyList(),
        currentSongId: Long? = null
    ): List<Song> {
        if (songs.isEmpty()) return emptyList()

        val hiddenValues = hiddenTrackKeys
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val effectiveSortMode = SortMode.values()[sortMode.coerceIn(0, SortMode.values().size - 1)]

        fun matchesSongIdentity(song: Song, raw: String): Boolean {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return false
            return song.id.toString() == trimmed ||
                song.uri.toString() == trimmed ||
                song.title == trimmed ||
                trimmed.toLongOrNull() == song.id
        }

        fun isHidden(song: Song): Boolean {
            return hiddenValues.any { matchesSongIdentity(song, it) }
        }

        val sorted = when (effectiveSortMode) {
            SortMode.SHORTEST -> songs.sortedBy { effectiveDuration(it) }
            SortMode.LONGEST -> songs.sortedByDescending { effectiveDuration(it) }
            SortMode.NEWEST -> songs.sortedByDescending { it.dateModified }
            SortMode.OLDEST -> songs.sortedBy { it.dateModified }
        }

        val visible = sorted.filterNot(::isHidden)
        if (currentSongId == null) return visible

        val pinned = visible.firstOrNull { it.id == currentSongId }
        return if (pinned != null) {
            listOf(pinned) + visible.filter { it.id != currentSongId }
        } else {
            visible
        }
    }

    fun findSongIndex(queue: List<Song>, song: Song?): Int {
        if (song == null) return -1
        return queue.indexOfFirst { it.id == song.id || it.uri == song.uri }
    }

    fun findSongIndexById(queue: List<Song>, songId: Long?): Int {
        if (songId == null) return -1
        return queue.indexOfFirst { it.id == songId }
    }

    fun nextTrack(queue: List<Song>, currentSongId: Long?): Song? {
        if (queue.isEmpty()) return null
        val currentIndex = findSongIndexById(queue, currentSongId)
        return if (currentIndex >= 0) {
            queue.getOrNull(currentIndex + 1)
                ?: queue.firstOrNull { it.id != currentSongId }
        } else {
            queue.firstOrNull()
        }
    }
}
