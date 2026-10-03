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
import java.text.SimpleDateFormat
import java.util.*

enum class SortMode(val label: String) {
    SHORTEST("Sort (Small)"),
    LONGEST("Sort (Big)"),
    NEWEST("Sort (Fresh)"),
    OLDEST("Sort (Stale)")
}

// Sealed class for list items (song or demarcation)
sealed class SongListItem {
    data class SongItem(val song: Song) : SongListItem()
    data class Demarcation(val label: String, val nextSongId: Long) : SongListItem()
}

@Composable
fun DemarcationDivider(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.2f))
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 10.sp
            )
        }
        HorizontalDivider(modifier = Modifier.weight(1f), color = Color.White.copy(alpha = 0.2f))
    }
}

// Duration bucket definitions (in milliseconds)
private data class DurationBucket(val maxMs: Long, val label: String)

private val DURATION_BUCKETS = listOf(
    DurationBucket(6 * 60 * 1000, "0–6 min"),
    DurationBucket(11 * 60 * 1000, "6–11 min"),
    DurationBucket(21 * 60 * 1000, "11–21 min"),
    DurationBucket(41 * 60 * 1000, "21–41 min"),
    DurationBucket(60 * 60 * 1000, "41 min–1 hr"),
    DurationBucket(2 * 60 * 60 * 1000, "1–2 hr"),
    DurationBucket(4 * 60 * 60 * 1000, "2–4 hr"),
    DurationBucket(Long.MAX_VALUE, "4 hr+")
)

private fun getDurationBucket(durationMs: Long): Int {
    return DURATION_BUCKETS.indexOfFirst { durationMs < it.maxMs }
        .takeIf { it >= 0 } ?: 0
}

private fun getDurationBucketLabel(durationMs: Long): String {
    return DURATION_BUCKETS[getDurationBucket(durationMs)].label
}

private fun getDateKey(timestampMs: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
    return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
}

private fun formatDateLabel(timestampMs: Long): String {
    val formatter = SimpleDateFormat("EEE MMM dd a", Locale.getDefault())
    return formatter.format(Date(timestampMs))
}

private fun shouldAddDemarcation(
    prevSong: Song,
    currentSong: Song,
    prevProgress: com.example.myapp.data.TrackProgress?,
    currentProgress: com.example.myapp.data.TrackProgress?,
    sortMode: SortMode,
    storageManager: StorageManager,
    pinnedSong: Song?
): Boolean {
    // After pinned track, always add demarcation
    if (pinnedSong != null && prevSong.uri == pinnedSong.uri) {
        return true
    }

    return when (sortMode) {
        SortMode.SHORTEST, SortMode.LONGEST -> {
            val prevDuration = prevProgress?.duration ?: estimateDuration(prevSong.fileSize)
            val currentDuration = currentProgress?.duration ?: estimateDuration(currentSong.fileSize)
            val prevBucket = getDurationBucket(prevDuration)
            val currentBucket = getDurationBucket(currentDuration)
            prevBucket != currentBucket
        }
        SortMode.NEWEST, SortMode.OLDEST -> {
            val prevKey = getDateKey(prevSong.dateModified)
            val currentKey = getDateKey(currentSong.dateModified)
            prevKey != currentKey
        }
    }
}

private fun getDemarcationLabel(
    song: Song,
    progress: com.example.myapp.data.TrackProgress?,
    sortMode: SortMode,
    storageManager: StorageManager
): String? {
    return when (sortMode) {
        SortMode.SHORTEST, SortMode.LONGEST -> {
            val duration = progress?.duration ?: estimateDuration(song.fileSize)
            getDurationBucketLabel(duration)
        }
        SortMode.NEWEST, SortMode.OLDEST -> {
            formatDateLabel(song.dateModified)
        }
    }
}

@Composable
fun SongListScreen(
    songs: List<Song>,
    currentSongId: Long? = null,
    onSongClick: (Song) -> Unit,
    onFolderButtonClick: (() -> Unit)? = null,
    onSwitchButtonClick: (() -> Unit)? = null,
    onSongDeleted: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val storageManager = remember { StorageManager(context) }
    
    var sortMode by remember { 
        mutableIntStateOf(storageManager.getSortIndex())
    }
    var showHidden by remember { mutableStateOf(false) }
    var visibleCount by remember { mutableIntStateOf(30) }
    var hiddenTracksRefresh by remember { mutableIntStateOf(0) }

    val hiddenTracks = remember(showHidden, songs, hiddenTracksRefresh) {
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
                // Controls - different for home and hidden pages (as regular items, non-sticky)
                item {
                    if (showHidden) {
                        // Hidden page: only "Home" button
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Button(
                                onClick = { showHidden = !showHidden },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFFFFF).copy(alpha = 0.2f)
                                )
                            ) {
                                Text(
                                    "Home",
                                    fontSize = 14.sp,
                                    color = Color.White
                                )
                            }
                        }
                    } else {
                        // Home page: two rows of buttons at the top
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Row 1: Folder | Hidden
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
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
                                    Text("Folder", fontSize = 14.sp, color = Color.White)
                                }
                                Button(
                                    onClick = { showHidden = !showHidden },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFFFFFF).copy(alpha = 0.2f)
                                    )
                                ) {
                                    Text("Hidden", fontSize = 14.sp, color = Color.White)
                                }
                            }

                            // Row 2: Switch | Sort
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onSwitchButtonClick?.invoke() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFFFFFFF).copy(alpha = 0.2f)
                                    ),
                                    enabled = onSwitchButtonClick != null
                                ) {
                                    Text("Switch", fontSize = 14.sp, color = Color.White)
                                }
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
                                    Text(SortMode.values()[sortMode].label, fontSize = 14.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }

                // Build items with demarcations
                val itemsWithDemarcations = mutableListOf<SongListItem>()
                var prevSong: Song? = null
                var prevProgress: com.example.myapp.data.TrackProgress? = null
                val pinnedSong = sortedAndFiltered.find { it.id == currentSongId }

                visibleSongs.forEachIndexed { index, song ->
                    val progress = storageManager.getTrackProgress(song.title)
                    val isCurrentTrack = song.id == currentSongId

                    // Determine if we need a demarcation before this song
                    val needsDemarcation = if (prevSong == null) {
                        // First item: no demarcation before it
                        false
                    } else {
                        // Check if we've crossed a boundary
                        shouldAddDemarcation(
                            prevSong!!, song,
                            prevProgress, progress,
                            SortMode.values()[sortMode],
                            storageManager,
                            pinnedSong
                        )
                    }

                    if (needsDemarcation) {
                        val label = getDemarcationLabel(
                            song,
                            progress,
                            SortMode.values()[sortMode],
                            storageManager
                        )
                        if (label != null) {
                            itemsWithDemarcations.add(SongListItem.Demarcation(label, song.id))
                        }
                    }

                    itemsWithDemarcations.add(SongListItem.SongItem(song))

                    prevSong = song
                    prevProgress = progress
                }

                items(itemsWithDemarcations, key = { item ->
                    when (item) {
                        is SongListItem.SongItem -> "song_${item.song.id}_${item.song.uri}"
                        is SongListItem.Demarcation -> "demarcation_${item.label}_${item.nextSongId}"
                    }
                }) { item ->
                    when (item) {
                        is SongListItem.SongItem -> {
                            val song = item.song
                            // Calculate max duration across all visible songs (using estimated duration)
                            val maxDurationMs = visibleSongs.maxOfOrNull { s ->
                                estimateDuration(s.fileSize)
                            } ?: 1L
                            
                            SongListItemComposable(
                                song = song,
                                isActive = song.id == currentSongId,
                                progress = storageManager.getTrackProgress(song.title),
                                maxFileSize = songs.maxOf { it.fileSize },
                                isHidden = hiddenTracks.contains(song.title),
                                onSongClick = { onSongClick(song) },
                                onHideClick = {
                                    storageManager.hideTrack(song.title)
                                    hiddenTracksRefresh++
                                },
                                onRestoreClick = {
                                    storageManager.unhideTrack(song.title)
                                    hiddenTracksRefresh++
                                },
                                onDeleteClick = {
                                    // Delete the file from disk
                                    storageManager.deleteTrack(song.title, song.uri)
                                    storageManager.unhideTrack(song.title) // Also remove from hidden list
                                    onSongDeleted?.invoke(song) // Notify parent to remove from list
                                    hiddenTracksRefresh++
                                },
                                showingHidden = showHidden,
                                maxDuration = maxDurationMs
                            )
                        }
                        is SongListItem.Demarcation -> {
                            DemarcationDivider(item.label)
                        }
                    }
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
fun SongListItemComposable(
    song: Song,
    isActive: Boolean,
    progress: com.example.myapp.data.TrackProgress?,
    maxFileSize: Long,
    isHidden: Boolean,
    onSongClick: () -> Unit,
    onHideClick: () -> Unit,
    onRestoreClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null,
    showingHidden: Boolean = false,
    maxDuration: Long = 1L
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
                formatTime(estimateDuration(song.fileSize))
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
                val trackDuration = estimateDuration(song.fileSize)
                
                if (maxDuration > 0 && trackDuration > 0) {
                    val grayWidth = (trackDuration.toFloat() / maxDuration.toFloat()) * 100
                    
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

        // Buttons - different behavior on hidden vs home page
        if (showingHidden) {
            // Hidden page: show both restore and delete buttons
            Row(
                modifier = Modifier
                    .width(108.dp)
                    .height(50.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Restore button
                Button(
                    onClick = onRestoreClick,
                    modifier = Modifier
                        .size(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4ade80).copy(alpha = 0.2f)
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        "↺",
                        fontSize = 20.sp,
                        color = Color.White
                    )
                }
                // Delete button
                Button(
                    onClick = { onDeleteClick?.invoke() },
                    modifier = Modifier
                        .size(50.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF4444).copy(alpha = 0.3f)
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        "✕",
                        fontSize = 20.sp,
                        color = Color.White
                    )
                }
            }
        } else {
            // Home page: show hide button
            Button(
                onClick = onHideClick,
                modifier = Modifier
                    .size(50.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFFFFF).copy(alpha = 0.1f)
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    "✕",
                    fontSize = 20.sp,
                    color = Color.White
                )
            }
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
            storageManager.getTrackProgress(song.title)?.duration ?: estimateDuration(song.fileSize)
        }
        SortMode.LONGEST -> this.sortedByDescending { song ->
            storageManager.getTrackProgress(song.title)?.duration ?: estimateDuration(song.fileSize)
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

// Estimate duration from file size (assuming average bitrate of 128 kbps)
// This is a reasonable estimate for typical MP3 files
private fun estimateDuration(bytes: Long): Long {
    if (bytes <= 0) return 0
    // Bitrate: 128 kbps = 128 * 1000 / 8 = 16000 bytes per second
    val BYTES_PER_SECOND = 16000L
    return (bytes / BYTES_PER_SECOND) * 1000 // Return in milliseconds
}
