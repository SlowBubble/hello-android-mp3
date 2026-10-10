package com.example.myapp.ui.screens

import android.content.Context
import android.util.Log
import android.widget.Toast
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
import com.example.myapp.ui.screens.QueueUtils
import java.text.SimpleDateFormat
import java.util.*

enum class SortMode(val label: String, val symbol: String) {
    SHORTEST("Sort", "📏"),
    LONGEST("Sort", "📏📏📏"),
    NEWEST("Sort", "⏳"),
    OLDEST("Sort", "⏳⏳⏳")
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
    val samePinnedSong = pinnedSong != null && (prevSong.id == pinnedSong.id || prevSong.uri == pinnedSong.uri)
    val visiblePinnedBoundary = pinnedSong != null && prevSong.id == pinnedSong.id

    // After pinned track, always add demarcation.
    // This must stay true even when the list has been re-sorted or the song object
    // itself is stale, so use the pinned song identity as the primary boundary.
    if (samePinnedSong || visiblePinnedBoundary) {
        return true
    }

    return when (sortMode) {
        SortMode.SHORTEST, SortMode.LONGEST -> {
            val prevDuration = QueueUtils.effectiveDuration(prevSong, prevProgress?.duration)
            val currentDuration = QueueUtils.effectiveDuration(currentSong, currentProgress?.duration)
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
            val duration = QueueUtils.effectiveDuration(song, progress?.duration)
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
    onPinnedTrackClick: ((Song) -> Unit)? = null,
    onFolderButtonClick: (() -> Unit)? = null,
    onSwitchButtonClick: (() -> Unit)? = null,
    onSongsDeleted: ((List<Song>) -> Unit)? = null,
    folderDisplayName: String = "Switch",
    isShowingAllFolders: Boolean = false,
    isHiddenOverlay: Boolean? = null,
    onShowHome: (() -> Unit)? = null,
    onShowHidden: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val storageManager = remember { StorageManager(context) }

    var sortMode by remember {
        mutableIntStateOf(storageManager.getSortIndex())
    }
    var localShowHidden by remember { mutableStateOf(false) }
    val showHidden = isHiddenOverlay ?: localShowHidden
    var visibleCount by remember { mutableIntStateOf(30) }
    var hiddenTracksRefresh by remember { mutableIntStateOf(0) }

    val hiddenTracks = remember(showHidden, songs, hiddenTracksRefresh) {
        storageManager.getHiddenTracks()
    }

    val visibleQueue = remember(songs, sortMode, hiddenTracks, currentSongId) {
        QueueUtils.buildVisibleQueue(
            songs = songs,
            sortMode = sortMode,
            hiddenTrackKeys = hiddenTracks,
            currentSongId = currentSongId
        )
    }

    val hiddenQueue = remember(songs, sortMode, hiddenTracks) {
        QueueUtils.buildHiddenQueue(
            songs = songs,
            sortMode = sortMode,
            hiddenTrackKeys = hiddenTracks
        )
    }

    val sortedAndFiltered = if (showHidden) hiddenQueue else visibleQueue

    // M4e: Auto-reset tracks that are within 25 seconds of the end
    val visibleSongs = remember(sortedAndFiltered, visibleCount) {
        val songs = sortedAndFiltered.take(visibleCount)
        songs.forEach { song ->
            val progress = storageManager.getTrackProgress(song)
            if (progress != null && progress.duration > 0 && progress.currentTime > 0) {
                val timeToEnd = progress.duration - progress.currentTime
                // If within 25 seconds of the end, reset current time to 0
                if (timeToEnd < 25000) { // 25 seconds in milliseconds
                    val resetProgress = progress.copy(currentTime = 0)
                    storageManager.saveTrackProgress(song, resetProgress)
                }
            }
        }
        songs
    }

    val hasMore = sortedAndFiltered.size > visibleCount

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF667eea))
            .padding(16.dp)
    ) {
        // Playlist
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Controls - different for home and hidden pages (as regular items, non-sticky)
            item {
                if (showHidden) {
                    // Hidden page: Home on row 1, Delete All on row 2
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (onShowHome != null) {
                                        onShowHome()
                                    } else {
                                        localShowHidden = false
                                    }
                                },
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

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val deleted = storageManager.deleteHiddenTracks(songs)
                                    onSongsDeleted?.invoke(deleted)
                                    hiddenTracksRefresh++
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFFFFF).copy(alpha = 0.2f)
                                )
                            ) {
                                Text(
                                    "Delete All",
                                    fontSize = 14.sp,
                                    color = Color.White
                                )
                            }
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
                                onClick = {
                                    if (onShowHidden != null) {
                                        onShowHidden()
                                    } else {
                                        localShowHidden = true
                                    }
                                },
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
                                val label = if (isShowingAllFolders) "All" else folderDisplayName
                                Text(label, fontSize = 14.sp, color = Color.White)
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
                                Text(
                                    "${SortMode.values()[sortMode].label} | ${SortMode.values()[sortMode].symbol}",
                                    fontSize = 14.sp,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }

            if (sortedAndFiltered.isEmpty()) {
                item {
                    Text(
                        if (showHidden) "No hidden tracks" else "No MP3 files found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            } else {
                // Build items with demarcations
                val itemsWithDemarcations = mutableListOf<SongListItem>()
                var prevSong: Song? = null
                var prevProgress: com.example.myapp.data.TrackProgress? = null
                val pinnedSong = sortedAndFiltered.find { it.id == currentSongId }
                val pinnedSongIndex = visibleSongs.indexOfFirst { song ->
                    pinnedSong != null && (song.id == pinnedSong.id || song.uri == pinnedSong.uri)
                }

                visibleSongs.forEachIndexed { index, song ->
                    val progress = storageManager.getTrackProgress(song)
                    val isCurrentTrack = song.id == currentSongId
                    val prevSongIndex = if (prevSong == null) null else visibleSongs.indexOfFirst { candidate ->
                        candidate.id == prevSong!!.id || candidate.uri == prevSong!!.uri
                    }

                    // Determine if we need a demarcation before this song
                    val needsDemarcation = if (prevSong == null) {
                        // First item: no demarcation before it
                        false
                    } else if (pinnedSongIndex >= 0 && prevSongIndex == pinnedSongIndex) {
                        // The item immediately after the pinned track always gets a divider,
                        // even when the song object identity is stale after a Next jump.
                        true
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
                            val maxDurationMs = visibleSongs.maxOfOrNull { s ->
                                QueueUtils.effectiveDuration(s, storageManager.getTrackProgress(s)?.duration)
                            } ?: 1L

                            SongListItemComposable(
                                song = song,
                                isActive = song.id == currentSongId,
                                progress = storageManager.getTrackProgress(song),
                                isHidden = storageManager.isTrackHidden(song),
                                onSongClick = {
                                    val isCurrentTrack = song.id == currentSongId

                                    if (isCurrentTrack && onPinnedTrackClick != null) {
                                        // M6b: Use dedicated pinned track handler to avoid crash
                                        try {
                                            onPinnedTrackClick(song)
                                        } catch (t: Throwable) {
                                            val debugInfo = buildString {
                                                append("currentSongId=$currentSongId\n")
                                                append("clickedSongId=${song.id}\n")
                                                append("clickedSongUri=${song.uri}\n")
                                                append("isCurrentTrack=$isCurrentTrack\n")
                                                append("showHidden=$showHidden\n")
                                                append("visibleItems=${visibleQueue.size}\n")
                                                append("hiddenItems=${hiddenQueue.size}\n")
                                            }
                                            Log.e("SongListScreen", "Pinned-track click failed\n$debugInfo", t)
                                            Toast.makeText(
                                                context,
                                                "Pinned-track click failed\n${t::class.simpleName}: ${t.message}\n\nDebug:\n$debugInfo",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    } else {
                                        // Normal song click logic
                                        // M4e: Auto-unhide if playing from hidden page
                                        if (showHidden) {
                                            storageManager.unhideTrack(song)
                                            hiddenTracksRefresh++
                                        }
                                        onSongClick(song)
                                    }
                                },
                                onHideClick = {
                                    storageManager.hideTrack(song)
                                    hiddenTracksRefresh++
                                },
                                onRestoreClick = {
                                    storageManager.unhideTrack(song)
                                    hiddenTracksRefresh++
                                },
                                onDeleteClick = {
                                    storageManager.deleteSong(song)
                                    onSongsDeleted?.invoke(listOf(song))
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
            val itemTitle = if (isActive) "[NOW PLAYING] ${song.title}" else song.title

            Text(
                itemTitle,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Duration or file size
            val durationText = if (progress?.duration != null && progress.duration > 0) {
                formatTime(progress.duration)
            } else {
                formatTime(QueueUtils.effectiveDuration(song, null))
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
                val trackDuration = QueueUtils.effectiveDuration(song, progress?.duration)

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
