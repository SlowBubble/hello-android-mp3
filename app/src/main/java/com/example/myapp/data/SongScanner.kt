package com.example.myapp.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log

class SongScanner(private val context: Context) {

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
                    result.add(
                        Song(
                            id = result.size.toLong(),
                            title = title,
                            artist = "Unknown Artist",
                            uri = docUri,
                            duration = 0L,
                            fileSize = fileSize,
                            dateModified = lastModified
                        )
                    )
                    Log.d("SongScanner", "Added MP3: $title → $docUri")
                }
            }
        }

        Log.d("SongScanner", "Total MP3 files found: ${result.size}")
        return result
    }
}
