package com.example.myapp.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages persistence of track session data across app sessions.
 * Handles saving/loading of:
 * - Current playback position
 * - Listening statistics (play count, first listened, last played, total time)
 * - Hidden/completed tracks
 * - Playback rate preference
 */
class SessionStorageManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "track_sessions",
        Context.MODE_PRIVATE
    )
    
    private val json = Json { ignoreUnknownKeys = true }
    
    companion object {
        private const val LAST_ACTIVE_TRACK_KEY = "last_active_track"
        private const val HIDDEN_TRACKS_KEY = "hidden_tracks"
        private const val SORT_PREFERENCE_KEY = "sort_preference"
        private const val GLOBAL_PLAYBACK_RATE_KEY = "global_playback_rate"
        private const val TRACK_SESSION_PREFIX = "track_session_"
    }
    
    /**
     * Save track session data (position, stats, etc.)
     */
    fun saveTrackSession(session: TrackSession) {
        try {
            val json = Json.encodeToString(session)
            prefs.edit().putString("${TRACK_SESSION_PREFIX}${session.trackId}", json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Load track session data by track ID
     */
    fun loadTrackSession(trackId: Long): TrackSession? {
        return try {
            val json = prefs.getString("${TRACK_SESSION_PREFIX}${trackId}", null) ?: return null
            Json.decodeFromString<TrackSession>(json)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Save the last active track (for session restoration on app restart)
     */
    fun saveLastActiveTrack(trackId: Long) {
        prefs.edit().putLong(LAST_ACTIVE_TRACK_KEY, trackId).apply()
    }
    
    /**
     * Get the last active track ID
     */
    fun getLastActiveTrack(): Long {
        return prefs.getLong(LAST_ACTIVE_TRACK_KEY, -1L)
    }
    
    /**
     * Mark a track as hidden
     */
    fun hideTrack(trackId: Long) {
        val hiddenTracks = getHiddenTracks().toMutableSet()
        hiddenTracks.add(trackId)
        saveHiddenTracks(hiddenTracks)
    }
    
    /**
     * Unhide a track
     */
    fun unhideTrack(trackId: Long) {
        val hiddenTracks = getHiddenTracks().toMutableSet()
        hiddenTracks.remove(trackId)
        saveHiddenTracks(hiddenTracks)
    }
    
    /**
     * Get all hidden track IDs
     */
    fun getHiddenTracks(): Set<Long> {
        val json = prefs.getString(HIDDEN_TRACKS_KEY, "[]") ?: "[]"
        return try {
            Json.decodeFromString<List<Long>>(json).toSet()
        } catch (e: Exception) {
            e.printStackTrace()
            emptySet()
        }
    }
    
    /**
     * Save the set of hidden tracks
     */
    private fun saveHiddenTracks(trackIds: Set<Long>) {
        try {
            val json = Json.encodeToString(trackIds.toList())
            prefs.edit().putString(HIDDEN_TRACKS_KEY, json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Save sort preference (0 = shortest, 1 = longest, 2 = newest, 3 = oldest)
     */
    fun saveSortPreference(sortMode: Int) {
        prefs.edit().putInt(SORT_PREFERENCE_KEY, sortMode).apply()
    }
    
    /**
     * Get sort preference
     */
    fun getSortPreference(): Int {
        return prefs.getInt(SORT_PREFERENCE_KEY, 0)
    }
    
    /**
     * Save global playback rate preference
     */
    fun savePlaybackRate(rate: Float) {
        prefs.edit().putFloat(GLOBAL_PLAYBACK_RATE_KEY, rate).apply()
    }
    
    /**
     * Get global playback rate preference
     */
    fun getPlaybackRate(): Float {
        return prefs.getFloat(GLOBAL_PLAYBACK_RATE_KEY, 1.0f)
    }
    
    /**
     * Clear all session data (e.g., when loading new folder)
     */
    fun clearAllSessions() {
        prefs.edit().clear().apply()
    }
}
