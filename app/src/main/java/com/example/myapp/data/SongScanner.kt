package com.example.myapp.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

class SongScanner(private val context: Context) {

    private fun extractDurationMillis(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
            if (fd != null) {
                try {
                    retriever.setDataSource(fd.fileDescriptor)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    duration?.toLongOrNull() ?: 0L
                } finally {
                    fd.close()
                }
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.w("SongScanner", "Could not read duration for $uri: ${e.message}", e)
            0L
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // no-op
            }
        }
    }

    fun scanFolderForMp3s(folderUri: String): List<Song> {
        val result = mutableListOf<Song>()

        Log.d("SongScanner", "Scanning for MP3 files in folder: $folderUri")

        val treeUri = Uri.parse(folderUri)
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        Log.d("SongScanner", "Tree document ID: $treeDocId")

        // Build the children URI — this is what you actually query for SAF folders
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            Log.d("SongScanner", "Query returned ${cursor.count} items")

            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val lastModCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val mime = cursor.getString(mimeCol) ?: ""
                val fileSize = cursor.getLong(sizeCol)
                val lastModified = cursor.getLong(lastModCol)

                Log.d("SongScanner", "Item: $name, MIME: $mime, Size: $fileSize, Modified: $lastModified")

                val isMp3 = mime.equals("audio/mpeg", ignoreCase = true) ||
                        name.lowercase().endsWith(".mp3")

                if (isMp3) {
                    // Build the actual content URI ExoPlayer can open
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val title = name.removeSuffix(".mp3").removeSuffix(".MP3")
                    val duration = extractDurationMillis(docUri)
                    result.add(
                        Song(
                            id = Song.generateStableId(docUri),
                            title = title,
                            artist = "Unknown Artist",
                            uri = docUri,
                            duration = duration,
                            fileSize = fileSize,
                            dateModified = lastModified
                        )
                    )
                    Log.d("SongScanner", "Added MP3: $title → $docUri (duration=${duration}ms)")
                }
            }
        }

        Log.d("SongScanner", "Total MP3 files found: ${result.size}")
        return result
    }

    /**
     * Scan multiple folders and return combined list of all MP3s found.
     * Deduplicates by URI to avoid showing the same file twice if it appears in multiple folders.
     */
    fun scanMultipleFoldersForMp3s(folderUris: List<String>): List<Song> {
        val allSongs = mutableListOf<Song>()
        val seenUris = mutableSetOf<String>()

        for (folderUri in folderUris) {
            try {
                val songs = scanFolderForMp3s(folderUri)
                for (song in songs) {
                    val uriString = song.uri.toString()
                    if (!seenUris.contains(uriString)) {
                        allSongs.add(song)
                        seenUris.add(uriString)
                    }
                }
            } catch (e: Exception) {
                Log.w("SongScanner", "Error scanning folder $folderUri: ${e.message}", e)
                // Continue scanning other folders even if one fails
            }
        }

        Log.d("SongScanner", "Total MP3 files found across all folders: ${allSongs.size}")
        return allSongs
    }
}
