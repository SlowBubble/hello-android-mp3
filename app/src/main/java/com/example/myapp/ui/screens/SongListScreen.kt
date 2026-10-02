package com.example.myapp.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.data.Song
import com.example.myapp.data.StorageManager

enum class SortMode(val label: String) {
    SHORTEST("Sort (Small)"),
    LONGEST("Sort (Big)"),
    NEWEST("Sort (Fresh)"),
    OLDEST("Sort (Stale)")
}

@Composable
fun SongListScreen(
    songs: List<Song>,
    currentSongId: Long? = null,
    onSongClick: (Song) -> Unit,
    onFolderButtonClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val storageManager = remember { StorageManager(context) }
    
    var sortMode by remember { 
        mutableIntStateOf(storageManager.getSortIndex())
    }
    var showHidden by remember { mutableStateOf(false) }
    var visibleCount by remember { mutableIntStateOf(30) }

    val hiddenTracks = remember(showHidden, songs) {
        storageManager.getHiddenTracks()
    }

    val sortedAndFiltered = remember(songs, sortMode, hiddenTracks, showHidden) {
        val sorted = songs.sortedAccordingTo(sortMode, storageManager, currentSongId)
        val filtered = sorted.filter { song ->
            val isHidden = hiddenTracks.contains(song.title)
            if (showHidden) isHidden else !isHidden
        }
        filtered
    }

    val visibleSongs = remember(sortedAndFiltered, visibleCount) {
        sortedAndFiltered.take(visibleCount)
    }

    val hasMore = sortedAndFiltered.size > visibleCount

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF667eea))
            .padding(16.dp)
    ) {
        // Header
        Text(
            "MP3 Player",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Left button - Reselect folder
            Button(
                onClick = { onFolderButtonClick?.invoke() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFFFFF).copy(alpha = 0.2f)
                ),
                enabled = onFolderButtonClick != null
            ) {
                Text(
                    "Folder",
                    fontSize = 14.sp,
                    color = Color.White
                )
            }

            // Middle button - Toggle hidden
            Button(
                onClick = { showHidden = !showHidden },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (showHidden) Color(0xFF4ade80).copy(alpha = 0.3f)
                    else Color(0xFFFFFFFF).copy(alpha = 0.2f)
                )
            ) {
                Text(
                    if (showHidden) "Show All" else "Hidden",
                    fontSize = 14.sp,
                    color = Color.White
                )
            }

            // Right button - Sort
            Button(
                onClick = {
                    sortMode = (sortMode + 1) % SortMode.values().size
                    storageManager.setSortIndex(sortMode)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFFFFF).copy(alpha = 0.2f)
                )
            ) {
                Text(
                    SortMode.values()[sortMode].label,
                    fontSize = 14.sp,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Playlist
        if (sortedAndFiltered.isEmpty()) {
            Text(
                if (showHidden) "No hidden tracks" else "No MP3 files found",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(visibleSongs, key = { it.id }) { song ->
                    SongListItem(
                        song = song,
                        isActive = song.id == currentSongId,
                        progress = storageManager.getTrackProgress(song.title),
                        maxFileSize = songs.maxOf { it.fileSize },
                        isHidden = hiddenTracks.contains(song.title),
                        onSongClick = { onSongClick(song) },
                        onHideClick = {
                            storageManager.hideTrack(song.title)
                        },
                        onRestoreClick = {
                            storageManager.unhideTrack(song.title)
                        }
                    )
                }

                // Load more button
                if (hasMore) {
                    item {
                        Button(
                            onClick = { visibleCount += 30 },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFFFFF).copy(alpha = 0.2f)
                            )
                        ) {
                            val remaining = sortedAndFiltered.size - visibleCount
                            Text(
                                "Load ${minOf(30, remaining)} more ($remaining remaining)",
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SongListItem(
    song: Song,
    isActive: Boolean,
    progress: com.example.myapp.data.TrackProgress?,
    maxFileSize: Long,
    isHidden: Boolean,
    onSongClick: () -> Unit,
    onHideClick: () -> Unit,
    onRestoreClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Main track content
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(
                    color = if (isActive) Color(0xFF4ade80).copy(alpha = 0.25f)
                    else Color(0xFFFFFFFF).copy(alpha = 0.1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                )
                .clickable { onSongClick() }
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                song.title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Duration or file size
            val durationText = if (progress?.duration != null && progress.duration > 0) {
                formatTime(progress.duration)
            } else {
                formatFileSize(song.fileSize)
            }

            // Current time / total time display if track has been played
            val timeDisplayText = if (progress?.duration != null && progress.duration > 0 && progress.currentTime > 0) {
                "${formatTime(progress.currentTime)} / ${formatTime(progress.duration)}"
            } else {
                durationText
            }

            Text(
                timeDisplayText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )

            // Progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            ) {
                // Gray bar: proportional to track duration vs longest track
                if (maxFileSize > 0 && (progress?.duration ?: song.fileSize) > 0) {
                    val trackDuration = progress?.duration ?: song.fileSize
                    val grayWidth = (trackDuration.toFloat() / maxFileSize.toFloat()) * 100
                    
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(grayWidth / 100f)
                            .background(
                                color = Color.White.copy(alpha = 0.4f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
                            )
                    ) {
                        // Green bar: only shown if there's saved progress
                        if (progress?.duration != null && progress.duration > 0 && progress.currentTime > 0) {
                            val greenWidth = (progress.currentTime.toFloat() / progress.duration.toFloat()) * 100
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(greenWidth / 100f)
                                    .background(
                                        color = Color(0xFF4ade80),
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
                                    )
                            )
                        }
                    }
                }
            }
        }

        // Hide/Restore button
        Button(
            onClick = if (isHidden) onRestoreClick else onHideClick,
            modifier = Modifier
                .size(50.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isHidden) Color(0xFF4ade80).copy(alpha = 0.2f)
                else Color(0xFFFFFFFF).copy(alpha = 0.1f)
            ),
            contentPadding = PaddingValues(0.dp)
        ) {
            Text(
                if (isHidden) "↺" else "✕",
                fontSize = 20.sp,
                color = Color.White
            )
        }
    }
}

private fun List<Song>.sortedAccordingTo(
    sortMode: Int,
    storageManager: StorageManager,
    currentSongId: Long?
): List<Song> {
    val currentSong = this.find { it.id == currentSongId }
    
    val sorted = when (SortMode.values()[sortMode]) {
        SortMode.SHORTEST -> this.sortedBy { song ->
            storageManager.getTrackProgress(song.title)?.duration ?: song.fileSize
        }
        SortMode.LONGEST -> this.sortedByDescending { song ->
            storageManager.getTrackProgress(song.title)?.duration ?: song.fileSize
        }
        SortMode.NEWEST -> this.sortedByDescending { it.dateModified }
        SortMode.OLDEST -> this.sortedBy { it.dateModified }
    }

    // Pin current song to top
    return if (currentSong != null) {
        listOf(currentSong) + sorted.filter { it.id != currentSong.id }
    } else {
        sorted
    }
}

private fun formatTime(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / (1000 * 60)) % 60
    val hours = ms / (1000 * 60 * 60)
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
