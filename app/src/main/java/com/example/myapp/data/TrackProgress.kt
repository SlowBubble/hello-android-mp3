package com.example.myapp.data

/**
 * Represents playback progress and metadata for a track.
 * Persisted to local storage via SharedPreferences JSON serialization.
 */
data class TrackProgress(
    val trackName: String,
    val currentTime: Long = 0,           // Current playback position in ms
    val duration: Long = 0,              // Total duration in ms
    val lastPlayed: Long = 0,            // Timestamp of last play
    val firstListened: Long? = null,     // Timestamp of first listen
    val totalListeningTime: Long = 0,    // Total time spent listening in ms
    val playCount: Int = 0               // Number of times track has been played
)
