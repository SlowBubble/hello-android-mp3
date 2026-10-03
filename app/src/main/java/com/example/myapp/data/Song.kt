package com.example.myapp.data

import android.net.Uri

data class Song(
    val id: Long,  // WARNING: Unstable ID! Based on list position at scan time.
                   // TODO(m4): Use stable identifiers like URI hash or file hash instead.
    val title: String,
    val artist: String,
    val uri: Uri,
    val duration: Long,
    val fileSize: Long = 0,
    val dateModified: Long = 0,
    val playCount: Int = 0,
    val firstListened: Long? = null,
    val lastPlayed: Long? = null,
    val totalListeningTime: Long = 0L // in milliseconds
)
