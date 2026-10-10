package com.example.myapp.data

import android.net.Uri

data class Song(
    val id: Long,  // Stable ID: hash of URI path. Same file always has same ID.
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
) {
    val displayTitle: String
        get() = sanitizeDisplayTitle(title)

    fun matches(other: Song): Boolean = id == other.id || uri == other.uri

    companion object {
        private val trailingBracketSuffix = Regex("""\s*\[[^\]]+\]\s*$""")

        fun sanitizeDisplayTitle(rawTitle: String): String {
            return rawTitle.replace(trailingBracketSuffix, "").trim()
        }

        // Generate stable ID from URI
        fun generateStableId(uri: Uri): Long {
            return uri.toString().hashCode().toLong()
        }
    }
}
