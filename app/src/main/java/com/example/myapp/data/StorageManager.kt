package com.example.myapp.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.Serializable
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
        private const val MAX_FOLDER_HISTORY = 10
    }

    // ============== Track Progress ==============

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
            val json = json.encodeToString(tracks)
            prefs.edit().putString(KEY_HIDDEN_TRACKS, json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ============== Sort Preference ==============

    fun setSortIndex(index: Int) {
        prefs.edit().putInt(KEY_SORT_INDEX, index).apply()
    }

    fun getSortIndex(): Int {
        return prefs.getInt(KEY_SORT_INDEX, 0)
    }

    // ============== Last Active Track ==============

    fun setLastActiveTrack(trackName: String?) {
        if (trackName != null) {
            prefs.edit().putString(KEY_LAST_ACTIVE_TRACK, trackName).apply()
        } else {
            prefs.edit().remove(KEY_LAST_ACTIVE_TRACK).apply()
        }
    }

    fun getLastActiveTrack(): String? {
        return prefs.getString(KEY_LAST_ACTIVE_TRACK, null)
    }

    // ============== Folder URI ==============

    fun setFolderUri(folderUri: String?) {
        if (folderUri != null) {
            prefs.edit().putString(KEY_FOLDER_URI, folderUri).apply()
        } else {
            prefs.edit().remove(KEY_FOLDER_URI).apply()
        }
    }

    fun getFolderUri(): String? {
        return prefs.getString(KEY_FOLDER_URI, null)
    }

    // ============== Folder History ==============

    /**
     * Returns the ordered list of previously opened folder URIs (most recent first).
     */
    fun getFolderHistory(): List<String> {
        return try {
            val stored = prefs.getString(KEY_FOLDER_HISTORY, "[]") ?: "[]"
            Json.decodeFromString<List<String>>(stored)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Adds a folder URI to the front of the history list (deduplicating and capping at MAX).
     * Also resets the current folder index to 0 (the newly added folder is now "current").
     */
    fun addFolderToHistory(folderUri: String) {
        val history = getFolderHistory().toMutableList()
        history.remove(folderUri)          // remove duplicate if present
        history.add(0, folderUri)          // prepend as most recent
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

    fun getCurrentFolderIndex(): Int {
        return prefs.getInt(KEY_CURRENT_FOLDER_INDEX, 0)
    }

    fun setCurrentFolderIndex(index: Int) {
        prefs.edit().putInt(KEY_CURRENT_FOLDER_INDEX, index).apply()
    }

    /**
     * Returns the URI for the currently selected folder (by index into history),
     * falling back to the legacy single folder_uri key for backwards compatibility.
     */
    fun getCurrentFolderUri(): String? {
        val history = getFolderHistory()
        if (history.isNotEmpty()) {
            val idx = getCurrentFolderIndex().coerceIn(0, history.size - 1)
            return history[idx]
        }
        return getFolderUri()
    }

    // ============== Playback Statistics ==============

    fun updateLastPlayedDate(trackName: String) {
        val progress = getTrackProgress(trackName) ?: TrackProgress(trackName)
        val updated = progress.copy(
            lastPlayed = System.currentTimeMillis(),
            firstListened = progress.firstListened ?: System.currentTimeMillis(),
            playCount = progress.playCount + 1
        )
        saveTrackProgress(trackName, updated)
    }

    fun addListeningTime(trackName: String, timeMs: Long) {
        val progress = getTrackProgress(trackName) ?: TrackProgress(trackName)
        val updated = progress.copy(
            totalListeningTime = progress.totalListeningTime + timeMs
        )
        saveTrackProgress(trackName, updated)
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // ============== File Deletion ==============

    fun deleteTrack(trackTitle: String, trackUri: android.net.Uri) {
        try {
            android.provider.DocumentsContract.deleteDocument(context.contentResolver, trackUri)
            Log.d("StorageManager", "Deleted file: $trackTitle at $trackUri")
        } catch (e: Exception) {
            Log.e("StorageManager", "Error deleting file: $trackTitle", e)
        }
    }
}
