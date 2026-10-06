package com.example.myapp.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages persistent storage of track progress, hidden tracks, and user preferences.
 * Uses Android SharedPreferences with JSON serialization.
 */
class StorageManager(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("mp3_player_prefs", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val KEY_TRACK_PROGRESS = "track_progress_"
        private const val KEY_HIDDEN_TRACKS = "hidden_tracks"
        private const val KEY_SORT_INDEX = "sort_index"
        private const val KEY_LAST_ACTIVE_TRACK = "last_active_track"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_FOLDER_HISTORY = "folder_history"
        private const val KEY_CURRENT_FOLDER_INDEX = "current_folder_index"
        private const val KEY_SHOW_ALL_FOLDERS = "show_all_folders"
        private const val MAX_FOLDER_HISTORY = 10
    }

    private fun songKey(song: Song): String = song.id.toString()

    // ============== Track Progress ==============
    fun saveTrackProgress(song: Song, progress: TrackProgress) {
        val canonical = progress.copy(trackId = song.id, trackName = song.title)
        saveTrackProgress(songKey(song), canonical)
    }

    fun getTrackProgress(song: Song): TrackProgress? {
        val byId = getTrackProgress(songKey(song)) ?: return null
        return if (byId.trackId == null || byId.trackId == song.id) byId else null
    }

    // Backwards-compatible raw-key lookup used by older code paths
    fun saveTrackProgress(trackName: String, progress: TrackProgress) {
        try {
            val json = json.encodeToString(progress)
            prefs.edit().putString("$KEY_TRACK_PROGRESS$trackName", json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getTrackProgress(trackName: String): TrackProgress? {
        return try {
            val json = prefs.getString("$KEY_TRACK_PROGRESS$trackName", null)
            if (json == null) return null
            Json.decodeFromString<TrackProgress>(json)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ============== Hidden Tracks ==============
    fun hideTrack(song: Song) {
        val canonicalKey = songKey(song)
        val hidden = getHiddenTracks().toMutableList()
        if (!hidden.contains(canonicalKey)) {
            hidden.add(canonicalKey)
        }
        saveHiddenTracks(hidden)
    }

    fun unhideTrack(song: Song) {
        val hidden = getHiddenTracks().toMutableList()
        hidden.remove(songKey(song))
        saveHiddenTracks(hidden)
    }

    fun isTrackHidden(song: Song): Boolean {
        return getHiddenTracks().contains(songKey(song))
    }

    fun hideTrack(trackName: String) {
        val hidden = getHiddenTracks().toMutableList()
        if (!hidden.contains(trackName)) {
            hidden.add(trackName)
            saveHiddenTracks(hidden)
        }
    }

    fun unhideTrack(trackName: String) {
        val hidden = getHiddenTracks().toMutableList()
        hidden.remove(trackName)
        saveHiddenTracks(hidden)
    }

    fun getHiddenTracks(): List<String> {
        return try {
            val json = prefs.getString(KEY_HIDDEN_TRACKS, "[]") ?: "[]"
            Json.decodeFromString<List<String>>(json)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun saveHiddenTracks(tracks: List<String>) {
        try {
            val json = json.encodeToString(tracks.distinct())
            prefs.edit().putString(KEY_HIDDEN_TRACKS, json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ============== Last Active Track ==============
    fun setLastActiveTrack(song: Song?) {
        if (song == null) {
            prefs.edit().remove(KEY_LAST_ACTIVE_TRACK).apply()
            return
        }
        prefs.edit().putString(KEY_LAST_ACTIVE_TRACK, songKey(song)).apply()
    }

    fun setLastActiveTrack(trackName: String?) {
        if (trackName != null) {
            prefs.edit().putString(KEY_LAST_ACTIVE_TRACK, trackName).apply()
        } else {
            prefs.edit().remove(KEY_LAST_ACTIVE_TRACK).apply()
        }
    }

    fun getLastActiveTrack(): String? = prefs.getString(KEY_LAST_ACTIVE_TRACK, null)

    fun getLastActiveTrackSong(songs: List<Song>): Song? {
        val value = getLastActiveTrack() ?: return null
        return songs.find { songKey(it) == value }
    }

    fun clearLastActiveTrack() {
        prefs.edit().remove(KEY_LAST_ACTIVE_TRACK).apply()
    }

    // ============== Sort Preference ==============
    fun setSortIndex(index: Int) {
        prefs.edit().putInt(KEY_SORT_INDEX, index).apply()
    }

    fun getSortIndex(): Int = prefs.getInt(KEY_SORT_INDEX, 0)

    // ============== Folder URI ==============
    fun setFolderUri(folderUri: String?) {
        if (folderUri != null) {
            prefs.edit().putString(KEY_FOLDER_URI, folderUri).apply()
        } else {
            prefs.edit().remove(KEY_FOLDER_URI).apply()
        }
    }

    fun getFolderUri(): String? = prefs.getString(KEY_FOLDER_URI, null)

    // ============== Folder History ==============
    fun getFolderHistory(): List<String> {
        return try {
            val stored = prefs.getString(KEY_FOLDER_HISTORY, "[]") ?: "[]"
            Json.decodeFromString<List<String>>(stored)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun addFolderToHistory(folderUri: String) {
        val history = getFolderHistory().toMutableList()
        history.remove(folderUri)
        history.add(0, folderUri)
        if (history.size > MAX_FOLDER_HISTORY) history.removeAt(history.size - 1)
        try {
            val stored = json.encodeToString(history)
            prefs.edit()
                .putString(KEY_FOLDER_HISTORY, stored)
                .putInt(KEY_CURRENT_FOLDER_INDEX, 0)
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCurrentFolderIndex(): Int = prefs.getInt(KEY_CURRENT_FOLDER_INDEX, 0)

    fun setCurrentFolderIndex(index: Int) {
        prefs.edit().putInt(KEY_CURRENT_FOLDER_INDEX, index).apply()
    }

    fun getCurrentFolderUri(): String? {
        val history = getFolderHistory()
        if (history.isNotEmpty()) {
            val idx = getCurrentFolderIndex().coerceIn(0, history.size - 1)
            return history[idx]
        }
        return getFolderUri()
    }

    private fun extractFolderName(uri: String): String? {
        return try {
            val decoded = java.net.URLDecoder.decode(uri, "UTF-8")
            val treeDocIdPattern = "tree/([^/]+)".toRegex()
            val match = treeDocIdPattern.find(decoded)
            if (match != null) {
                val treeDocId = match.groupValues[1]
                val parts = treeDocId.split(":")
                val pathPart = if (parts.size > 1) parts[1] else treeDocId
                val folderParts = pathPart.split("/")
                val lastFolder = folderParts.lastOrNull { it.isNotEmpty() }
                lastFolder?.takeIf { it.isNotEmpty() }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun getFolderDisplayName(uri: String, index: Int): String {
        val extracted = extractFolderName(uri)
        return if (!extracted.isNullOrEmpty()) extracted else "Folder ${index + 1}"
    }

    fun getCurrentFolderDisplayName(): String {
        val history = getFolderHistory()
        if (history.isNotEmpty()) {
            val idx = getCurrentFolderIndex().coerceIn(0, history.size - 1)
            return getFolderDisplayName(history[idx], idx)
        }
        return "Folder"
    }

    fun setShowAllFolders(showAll: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ALL_FOLDERS, showAll).apply()
    }

    fun isShowAllFolders(): Boolean = prefs.getBoolean(KEY_SHOW_ALL_FOLDERS, false)

    // ============== Playback Statistics ==============
    fun updateLastPlayedDate(trackName: String) {
        val progress = getTrackProgress(trackName) ?: TrackProgress(trackName = trackName)
        val updated = progress.copy(
            lastPlayed = System.currentTimeMillis(),
            firstListened = progress.firstListened ?: System.currentTimeMillis(),
            playCount = progress.playCount + 1
        )
        saveTrackProgress(trackName, updated)
    }

    fun addListeningTime(trackName: String, timeMs: Long) {
        val progress = getTrackProgress(trackName) ?: TrackProgress(trackName = trackName)
        val updated = progress.copy(totalListeningTime = progress.totalListeningTime + timeMs)
        saveTrackProgress(trackName, updated)
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    fun deleteTrack(trackTitle: String, trackUri: android.net.Uri) {
        try {
            android.provider.DocumentsContract.deleteDocument(context.contentResolver, trackUri)
            Log.d("StorageManager", "Deleted file: $trackTitle at $trackUri")
        } catch (e: Exception) {
            Log.e("StorageManager", "Error deleting file: $trackTitle", e)
        }
    }
}
