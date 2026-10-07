package com.example.myapp.ui.screens

import com.example.myapp.data.Song

object QueueUtils {
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
            SortMode.SHORTEST -> songs.sortedBy { track ->
                track.duration.takeIf { it > 0 } ?: track.fileSize
            }
            SortMode.LONGEST -> songs.sortedByDescending { track ->
                track.duration.takeIf { it > 0 } ?: track.fileSize
            }
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
