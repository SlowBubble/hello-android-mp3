package com.example.myapp.data

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages session persistence with auto-save every 5 seconds.
 * Tracks listening time and saves playback position periodically.
 */
class SessionManager(
    private val storageManager: SessionStorageManager,
    private val coroutineScope: CoroutineScope
) {
    
    private var autoSaveJob: Job? = null
    private var currentTrackSession: TrackSession? = null
    private var listeningSessionStartTime: Long? = null
    private var lastSaveTime: Long = 0L
    
    companion object {
        private const val AUTO_SAVE_INTERVAL_MS = 5000L // 5 seconds
        private const val FINAL_TRACK_THRESHOLD_MS = 30000L // 30 seconds from end
    }
    
    /**
     * Start a listening session for a track
     */
    fun startListeningSession(trackSession: TrackSession) {
        currentTrackSession = trackSession
        listeningSessionStartTime = System.currentTimeMillis()
        lastSaveTime = 0L
        startAutoSave()
    }
    
    /**
     * Update current position during playback
     */
    fun updatePlaybackPosition(currentPosition: Long, duration: Long) {
        currentTrackSession = currentTrackSession?.copy(
            currentPosition = currentPosition,
            duration = duration
        )
    }
    
    /**
     * Stop the listening session and save final state
     */
    fun stopListeningSession(finalPosition: Long, duration: Long, isCompleted: Boolean = false) {
        stopAutoSave()
        
        currentTrackSession?.let { session ->
            val now = System.currentTimeMillis()
            val sessionDuration = now - (listeningSessionStartTime ?: now)
            
            // If within 30 seconds of end, mark as completed
            val timeToEnd = (duration - finalPosition)
            val shouldMarkCompleted = isCompleted || (timeToEnd < FINAL_TRACK_THRESHOLD_MS && duration > 0)
            
            val finalSession = session.copy(
                currentPosition = if (shouldMarkCompleted) 0L else finalPosition,
                duration = duration,
                playCount = session.playCount + 1,
                firstListened = session.firstListened ?: now,
                lastPlayed = now,
                totalListeningTime = session.totalListeningTime + sessionDuration
            )
            
            storageManager.saveTrackSession(finalSession)
            currentTrackSession = null
            listeningSessionStartTime = null
        }
    }
    
    /**
     * Pause the session without marking completion
     */
    fun pauseSession(currentPosition: Long, duration: Long) {
        stopAutoSave()
        
        currentTrackSession?.let { session ->
            val now = System.currentTimeMillis()
            val sessionDuration = now - (listeningSessionStartTime ?: now)
            
            val updatedSession = session.copy(
                currentPosition = currentPosition,
                duration = duration,
                totalListeningTime = session.totalListeningTime + sessionDuration
            )
            
            storageManager.saveTrackSession(updatedSession)
            currentTrackSession = updatedSession
            listeningSessionStartTime = null
        }
    }
    
    /**
     * Resume a paused session
     */
    fun resumeSession() {
        listeningSessionStartTime = System.currentTimeMillis()
        startAutoSave()
    }
    
    /**
     * Start automatic saving every 5 seconds
     */
    private fun startAutoSave() {
        stopAutoSave() // Cancel any existing job
        
        autoSaveJob = coroutineScope.launch {
            while (true) {
                delay(AUTO_SAVE_INTERVAL_MS)
                autoSave()
            }
        }
    }
    
    /**
     * Stop automatic saving
     */
    private fun stopAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = null
    }
    
    /**
     * Perform auto-save (called every 5 seconds)
     */
    private fun autoSave() {
        currentTrackSession?.let { session ->
            storageManager.saveTrackSession(session)
            lastSaveTime = System.currentTimeMillis()
        }
    }
    
    /**
     * Get stats display text (e.g., "3x • 45m total")
     */
    fun getStatsDisplayText(session: TrackSession): String? {
        if (session.playCount == 0) return null
        
        val playCountStr = "${session.playCount}x"
        val totalMinutes = session.totalListeningTime / 1000 / 60
        
        return if (totalMinutes > 0) {
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            
            val timeStr = when {
                hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
                hours > 0 -> "${hours}h"
                else -> "${minutes}m"
            }
            
            "$playCountStr • $timeStr total"
        } else {
            playCountStr
        }
    }
    
    /**
     * Load last active track and restore session
     */
    fun restoreLastActiveSession(): TrackSession? {
        val trackId = storageManager.getLastActiveTrack()
        return if (trackId > -1L) {
            storageManager.loadTrackSession(trackId)
        } else {
            null
        }
    }
    
    /**
     * Save currently playing track as last active
     */
    fun saveAsLastActive(trackId: Long) {
        storageManager.saveLastActiveTrack(trackId)
    }
}
