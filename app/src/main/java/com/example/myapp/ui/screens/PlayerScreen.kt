package com.example.myapp.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.myapp.data.Song
import com.example.myapp.data.StorageManager
import com.example.myapp.service.PlayerService
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    song: Song,
    isPlaying: Boolean,
    onPlayPause: (PlayerService) -> Unit,
    onRewind: (PlayerService) -> Unit,
    onFastForward: (PlayerService) -> Unit,
    onRewind45: (PlayerService) -> Unit,
    onFastForward45: (PlayerService) -> Unit,
    onBack: () -> Unit,
    songs: List<Song> = emptyList(),
    hiddenTracks: List<String> = emptyList(),
    currentSongIndex: Int = 0,
    onTrackHideAndNext: (nextSong: Song) -> Unit = {},
    playerViewModel: com.example.myapp.viewmodel.PlayerViewModel,
    modifier: Modifier = Modifier
) {
    var service by remember { mutableStateOf<PlayerService?>(null) }
    var connected by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playbackRate by remember { mutableStateOf(1.0f) }
    var currentChapter by remember { mutableStateOf(0) }

    val context = LocalContext.current
    val storageManager = remember { StorageManager(context) }
    
    // Collect lastLoadedSongUri from ViewModel to track across navigation
    val lastLoadedSongUri by playerViewModel.lastLoadedSongUri.collectAsState()

    // Helper function to get the next track in sorted order (ignoring the pinned track)
    fun getNextTrackInSortOrder(): Song? {
        if (songs.isEmpty()) return null
        
        val hiddenSet = hiddenTracks.toSet()
        val sortIndex = storageManager.getSortIndex()
        val currentSongUri = song.uri.toString()
        
        // Estimate duration from file size (128 kbps average bitrate)
        fun estimateDurationLocal(bytes: Long): Long {
            if (bytes <= 0) return 0
            val BYTES_PER_SECOND = 16000L
            return (bytes / BYTES_PER_SECOND) * 1000
        }
        
        // Sort according to current sort mode, but without pinning the current track
        val sorted = when (sortIndex) {
            0 -> songs.sortedBy { track ->
                storageManager.getTrackProgress(track.title)?.duration ?: estimateDurationLocal(track.fileSize)
            }
            1 -> songs.sortedByDescending { track ->
                storageManager.getTrackProgress(track.title)?.duration ?: estimateDurationLocal(track.fileSize)
            }
            2 -> songs.sortedByDescending { it.dateModified }
            else -> songs.sortedBy { it.dateModified }
        }
        
        // Find current song in sorted list by URI (stable identifier)
        val currentIndex = sorted.indexOfFirst { it.uri.toString() == currentSongUri }
        if (currentIndex < 0) return null
        
        // Find next non-hidden track after current position
        return sorted.drop(currentIndex + 1).firstOrNull { track ->
            !hiddenSet.contains(track.title)
        }
    }

    // Helper function to save progress
    fun saveProgress() {
        if (service != null) {
            val pos = service!!.getCurrentPosition()
            val dur = service!!.getDuration()
            
            if (dur > 0) {
                val progress = storageManager.getTrackProgress(song.title) 
                    ?: com.example.myapp.data.TrackProgress(song.title)
                
                val updated = progress.copy(currentTime = pos, duration = dur)
                storageManager.saveTrackProgress(song.title, updated)
            }
        }
    }

    val connection = remember(context) {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder is PlayerService.LocalBinder) {
                    service = binder.getService()
                    connected = true
                    // Don't load here — let LaunchedEffect handle it
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected = false
                service = null
            }
        }
    }

    // Key is only `context` — NOT `service`, which would cause an unbind loop
    DisposableEffect(context) {
        val intent = Intent(context, PlayerService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

        onDispose {
            if (connected) {
                context.unbindService(connection)
            }
        }
    }

    // Track whether this is the first time we've connected for this song
    // (removed local remember — now using ViewModel's persistent state)

    // Restore saved position then start playing — only when the song actually changes
    LaunchedEffect(song, service, connected) {
        try {
            if (connected && service != null) {
                val songUri = song.uri?.toString() ?: return@LaunchedEffect
                val alreadyLoaded = lastLoadedSongUri == songUri
                val alreadyPlaying = service!!.isPlaying()

                if (alreadyLoaded && alreadyPlaying) {
                    // Navigated back to the same song that is actively playing — do nothing
                    return@LaunchedEffect
                }

                // Load the new song URI
                service!!.loadUri(songUri)
                playerViewModel.setLastLoadedSongUri(songUri)

                // Wait for ExoPlayer to load metadata
                var attempts = 0
                while (service!!.getDuration() <= 0 && attempts < 20) {
                    delay(100)
                    attempts++
                }

                val progress = storageManager.getTrackProgress(song.title)
                if (progress != null && progress.currentTime > 0 && progress.duration > 0) {
                    // Seek to saved position before playing
                    service!!.seekTo(progress.currentTime)
                }

                // Now start playing
                service!!.play()
            }
        } catch (e: Exception) {
            Log.e("PlayerScreen", "Error loading song: ${e.message}", e)
        }
    }

    // Continuously update UI state from service
    LaunchedEffect(service, connected) {
        while (connected && service != null) {
            currentPosition = service!!.getCurrentPosition()
            val newDuration = service!!.getDuration()
            if (newDuration > 0 && newDuration != duration) {
                duration = newDuration
            }
            delay(500)
        }
    }

    // Save progress every 5 seconds (M2 spec)
    LaunchedEffect(service, connected, song) {
        while (connected && service != null) {
            delay(5000) // 5 seconds as per M2 spec
            saveProgress()
        }
    }

    // Update chapter display
    LaunchedEffect(currentPosition, duration) {
        if (duration > 0) {
            val chapterSize = duration / 10
            currentChapter = minOf(9, (currentPosition / chapterSize).toInt())
        }
    }

    // Auto-hide on completion
    LaunchedEffect(currentPosition, duration) {
        if (duration > 0 && duration - currentPosition < 30000 && duration - currentPosition > 29000) {
            try {
                // Track is finishing, hide it and play next
                storageManager.hideTrack(song.title)
                
                // Find next track in sorted order
                val nextSong = getNextTrackInSortOrder()
                
                if (nextSong != null) {
                    onTrackHideAndNext(nextSong)
                } else {
                    // No more non-hidden tracks, go back to home
                    onBack()
                }
            } catch (e: Exception) {
                Log.e("PlayerScreen", "Error auto-advancing to next track: ${e.message}", e)
                onBack()
            }
        }
    }

    // Save when screen is disposed (user navigates away)
    DisposableEffect(Unit) {
        onDispose {
            saveProgress()
        }
    }

    // Gesture handling for double-tap
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapX by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                color = when {
                    isPlaying -> Color(0xFF064e3b)  // Green
                    connected -> Color(0xFF7f1d1d)  // Red
                    else -> Color(0xFF667eea)        // Purple
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        val currentTime = System.currentTimeMillis()
                        val timeDiff = currentTime - lastTapTime
                        lastTapTime = currentTime
                        lastTapX = offset.x

                        // Check if this is a double-tap (within 300ms of last tap)
                        if (timeDiff < 300) {
                            // Double tap detected
                            val screenWidth = size.width
                            val isLeftHalf = lastTapX < screenWidth / 2f

                            if (isLeftHalf) {
                                // Double tap left: rewind 45 seconds
                                service?.let { it.seekTo((it.getCurrentPosition() - 45000).coerceAtLeast(0)) }
                            } else {
                                // Double tap right: forward 45 seconds
                                service?.let { it.seekTo((it.getCurrentPosition() + 45000).coerceAtMost(duration)) }
                            }
                        } else {
                            // Single tap: toggle play/pause
                            service?.let { 
                                onPlayPause(it)
                                saveProgress()
                            }
                        }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top bar with playback rate button and hide button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { 
                        service?.let { 
                            playbackRate = it.getPlaybackRate()
                            // Cycle playback rate: 1.0 → 1.15 → 1.25 → 1.35 → 1.0
                            val rates = listOf(1.0f, 1.15f, 1.25f, 1.35f)
                            val currentIndex = rates.indexOf(playbackRate.let { r ->
                                rates.minByOrNull { kotlin.math.abs(it - r) } ?: 1.0f
                            })
                            val nextIndex = (currentIndex + 1) % rates.size
                            it.setPlaybackRate(rates[nextIndex])
                            playbackRate = rates[nextIndex]
                        }
                    },
                    modifier = Modifier
                        .size(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.15f)
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(String.format("%.2fx", playbackRate), fontSize = 14.sp, color = Color.White)
                }

                // Home button spanning the width between speed and X buttons
                Button(
                    onClick = {
                        saveProgress()
                        onBack()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .padding(horizontal = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.15f)
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("🏠", fontSize = 20.sp, color = Color.White)
                }

                // X button: hide current track and play next
                Button(
                    onClick = {
                        storageManager.hideTrack(song.title)
                        
                        // Find next track in sorted order
                        val nextSong = getNextTrackInSortOrder()
                        
                        if (nextSong != null) {
                            onTrackHideAndNext(nextSong)
                        } else {
                            // No more non-hidden tracks, go back to home
                            onBack()
                        }
                    },
                    modifier = Modifier
                        .size(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF4444).copy(alpha = 0.3f)
                    ),
                    contentPadding = PaddingValues(0.dp),
                    enabled = songs.isNotEmpty()
                ) {
                    Text("✕", fontSize = 20.sp, color = Color.White)
                }
            }

            // Main track display with 45s skip buttons on sides
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: -45s button
                Button(
                    onClick = { service?.let { onRewind45(it) } },
                    modifier = Modifier
                        .width(60.dp)
                        .fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.15f)
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("⏪", fontSize = 24.sp, color = Color.White)
                }

                // Center: Track info
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        modifier = Modifier.padding(16.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    Text(
                        song.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )

                    // M2: Playback rate display
                    Text(
                        "${String.format("%.2f", playbackRate)}x",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                // Right: +45s button
                Button(
                    onClick = { service?.let { onFastForward45(it) } },
                    modifier = Modifier
                        .width(60.dp)
                        .fillMaxHeight(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.15f)
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("⏩", fontSize = 24.sp, color = Color.White)
                }
            }

            // Controls section - moved up, no play/pause button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color.Black.copy(alpha = 0.3f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                    )
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 7-second skip buttons (above progress bar)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { service?.let { onRewind(it) } },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.15f)
                        )
                    ) {
                        Text("⏪ -7s", fontSize = 14.sp, color = Color.White)
                    }
                    Button(
                        onClick = { service?.let { onFastForward(it) } },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.15f)
                        )
                    ) {
                        Text("+7s ⏩", fontSize = 14.sp, color = Color.White)
                    }
                }

                // Progress bar and time display
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(
                                color = Color.White.copy(alpha = 0.2f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
                            )
                    ) {
                        if (duration > 0) {
                            val progress = currentPosition.toFloat() / duration.toFloat()
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress)
                                    .background(
                                        color = Color.White,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
                                    )
                            )
                        }
                    }

                    // Time display
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatTime(currentPosition),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Text(
                            formatTime(duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                // Home button - wide button below progress bar
                Button(
                    onClick = {
                        saveProgress()
                        onBack()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.15f)
                    )
                ) {
                    Text("🏠 Home", fontSize = 16.sp, color = Color.White)
                }
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
