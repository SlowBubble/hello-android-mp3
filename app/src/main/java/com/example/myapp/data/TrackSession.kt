package com.example.myapp.data

/**
 * Represents the session/progress data for a track.
 * This is persisted separately from the Song metadata.
 */
data class TrackSession(
    val trackId: Long,
    val trackTitle: String,
    val currentPosition: Long = 0L, // in milliseconds
    val duration: Long = 0L, // in milliseconds
    val playCount: Int = 0,
    val firstListened: Long? = null, // timestamp in milliseconds
    val lastPlayed: Long? = null, // timestamp in milliseconds
    val totalListeningTime: Long = 0L, // in milliseconds
    val playbackRate: Float = 1.0f,
    val isHidden: Boolean = false,
    val lastSavedTime: Long = System.currentTimeMillis()
)
