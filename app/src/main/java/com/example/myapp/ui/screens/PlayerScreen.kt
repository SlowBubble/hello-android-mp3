package com.example.myapp.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
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
import com.example.myapp.ui.screens.QueueUtils
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
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
    onSkipToNext: (nextSong: Song) -> Unit = {},
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
    val currentVmSong by playerViewModel.currentSong.collectAsState()
    val currentVmSongId by playerViewModel.currentSongId.collectAsState()

    // State to track if we've already handled this track ending
    var trackEndedHandled by remember { mutableStateOf(false) }
    var currentPlayerListener by remember { mutableStateOf<Player.Listener?>(null) }

    // Helper function to get the next track in sorted order (ignoring the pinned track)
    fun getVisibleQueue(): List<Song> {
        val activeSongId = currentVmSongId ?: song.id
        // Always read current hidden tracks from StorageManager, not the stale parameter
        val currentHiddenTracks = storageManager.getHiddenTracks()
        return QueueUtils.buildVisibleQueue(
            songs = songs,
            sortMode = storageManager.getSortIndex(),
            hiddenTrackKeys = currentHiddenTracks,
            currentSongId = activeSongId
        )
    }

    fun getNextTrackInSortOrder(): Song? {
        val queue = getVisibleQueue()
        val activeSongId = currentVmSongId ?: song.id
        return QueueUtils.nextTrack(queue, activeSongId)
    }

    // Helper function to save progress
    fun saveProgressForSong(targetSong: Song) {
        val activeSongId = playerViewModel.currentSong.value?.id
        if (activeSongId != null && activeSongId != targetSong.id) {
            Log.d(
                "PlayerScreen",
                "Skipping stale save for id=${targetSong.id} because active song is id=$activeSongId"
            )
            return
        }

        if (service == null) {
            Log.d("PlayerScreen", "saveProgressForSong skipped: no service for id=${targetSong.id}")
            return
        }

        val pos = service!!.getCurrentPosition()
        val dur = service!!.getDuration()
        Log.d(
            "PlayerScreen",
            "saveProgressForSong: id=${targetSong.id} title=${targetSong.title} pos=$pos dur=$dur"
        )

        if (pos < 0 || dur <= 0 || dur == Long.MIN_VALUE || dur == Long.MIN_VALUE + 1) {
            Log.d(
                "PlayerScreen",
                "Ignoring invalid duration for id=${targetSong.id}: pos=$pos dur=$dur"
            )
            return
        }

        val progress = storageManager.getTrackProgress(targetSong)
            ?: com.example.myapp.data.TrackProgress(trackName = targetSong.title, trackId = targetSong.id)
        val updated = progress.copy(
            trackId = targetSong.id,
            trackName = targetSong.title,
            currentTime = pos,
            duration = dur
        )
        storageManager.saveTrackProgress(targetSong, updated)
        Log.d(
            "PlayerScreen",
            "saved progress for id=${targetSong.id}: currentTime=${updated.currentTime} duration=${updated.duration}"
        )
    }

    // Helper function to save progress
    fun saveProgress() {
        saveProgressForSong(song)
    }

    fun moveToNextTrack(hideCurrent: Boolean) {
        val previousSong = song
        val nextSong = getNextTrackInSortOrder() ?: return
        val nextUri = nextSong.uri.toString()

        Log.d(
            "PlayerScreen",
            "moveToNextTrack: from id=${previousSong.id} title=${previousSong.title} to id=${nextSong.id} title=${nextSong.title} hideCurrent=$hideCurrent"
        )

        // Save the old song while it is still the active player state.
        saveProgressForSong(previousSong)

        if (hideCurrent) {
            storageManager.hideTrack(previousSong)
        }

        // Advance the active song before the service switch so any stale delayed saves
        // from the old composable will be skipped instead of writing the next track's
        // duration into the previous track's persisted progress.
        playerViewModel.setLastLoadedSongUri(nextUri)
        playerViewModel.setCurrentSong(nextSong)
        playerViewModel.setIsPlaying(true)

        service?.let { svc ->
            svc.loadUri(nextUri)
            svc.play()
        }
    }

    val connection = remember(context, song) {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder is PlayerService.LocalBinder) {
                    service = binder.getService()
                    connected = true

                    // Remove any previous listener before adding a new one
                    if (currentPlayerListener != null) {
                        try {
                            service?.exoPlayer?.removeListener(currentPlayerListener!!)
                            Log.d("PlayerScreen", "Removed old player listener")
                        } catch (e: Exception) {
                            Log.w("PlayerScreen", "Error removing old listener: ${e.message}")
                        }
                    }

                    // Add listener for track end detection
                    val listener = object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            Log.d("PlayerScreen", "Playback state changed: $playbackState for song: ${song.title}")
                            if (playbackState == Player.STATE_ENDED) {
                                // Track finished naturally
                                if (!trackEndedHandled) {
                                    trackEndedHandled = true
                                    try {
                                        Log.d("PlayerScreen", "Track ended: ${song.title}")
                                        // Save progress
                                        saveProgress()

                                        // Hide current track
                                        storageManager.hideTrack(song)

                                        // Find next track in sorted order
                                        val nextSong = getNextTrackInSortOrder()

                                        if (nextSong != null) {
                                            Log.d("PlayerScreen", "Advancing to next track: ${nextSong.title}")
                                            onTrackHideAndNext(nextSong)
                                        } else {
                                            // No more non-hidden tracks, go back to home
                                            Log.d("PlayerScreen", "No more tracks available, going back")
                                            onBack()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("PlayerScreen", "Error auto-advancing to next track: ${e.message}", e)
                                        onBack()
                                    }
                                }
                            }
                        }
                    }

                    currentPlayerListener = listener
                    service?.exoPlayer?.addListener(listener)
                    Log.d("PlayerScreen", "Added player listener for song: ${song.title}")
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
        Log.d("PlayerScreen", "bindService called")

        onDispose {
            Log.d("PlayerScreen", "DisposableEffect(context) onDispose called, connected=$connected")
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
                trackEndedHandled = false  // Reset flag for new song
                
                val songUri = song.uri?.toString() ?: return@LaunchedEffect
                val alreadyLoaded = lastLoadedSongUri == songUri
                val alreadyPlaying = service!!.isPlaying()

                if (alreadyLoaded && alreadyPlaying) {
                    // Navigated back to the same song that is actively playing — do nothing
                    return@LaunchedEffect
                }

                // Load the new song URI (this calls stop() internally)
                service!!.loadUri(songUri)
                playerViewModel.setLastLoadedSongUri(songUri)

                // Wait for ExoPlayer to load metadata
                var attempts = 0
                while (service!!.getDuration() <= 0 && attempts < 20) {
                    delay(100)
                    attempts++
                }

                val progress = storageManager.getTrackProgress(song)
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
            // Always update duration if it's valid (> 0), even if it's the same
            // This ensures progress bar proportions stay accurate when duration changes
            if (newDuration > 0) {
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



    // Save when screen is disposed (user navigates away)
    DisposableEffect(Unit) {
        onDispose {
            val vmCurrentSong = playerViewModel.currentSong.value
            val shouldPreserveNewerSong = vmCurrentSong != null && vmCurrentSong.id != song.id

            if (shouldPreserveNewerSong) {
                // A newer current song was already selected (e.g. user pressed Next),
                // so do not overwrite it with this stale song on dispose.
                return@onDispose
            }

            // Keep the currently selected track pinned even when playback is paused.
            // The home list should still treat it as the active pinned item and show
            // the demarcation below it until a different song becomes current.
            if (vmCurrentSong == null || vmCurrentSong.id == song.id) {
                playerViewModel.setCurrentSong(song)
            }
        }
    }

    // When the screen is replaced by a new current song, make sure the current track's
    // progress is recorded before we drop this composable, otherwise the old entry can stay
    // keyed to the new duration by a stale SharedPreferences record.
    DisposableEffect(song.uri.toString()) {
        onDispose {
            if (service != null) {
                saveProgress()
            }
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
                    .height(72.dp),
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
                        .size(72.dp),
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
                        Log.d("PlayerScreen", "Top home button clicked")
                        saveProgress()
                        onBack()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp)
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
                        moveToNextTrack(hideCurrent = true)
                    },
                    modifier = Modifier
                        .size(72.dp),
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
                        .width(90.dp)
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
                        .fillMaxHeight()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val currentTime = System.currentTimeMillis()
                                    val timeDiff = currentTime - lastTapTime
                                    lastTapTime = currentTime
                                    lastTapX = offset.x

                                    // Check if this is a double-tap (within 300ms of last tap)
                                    if (timeDiff < 300) {
                                        // Double tap left: rewind 45 seconds
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
                                        Log.d("PlayerScreen", "Single tap on background detected, calling onPlayPause")
                                        service?.let {
                                            onPlayPause(it)
                                            saveProgress()
                                        }
                                    }
                                }
                            )
                        },
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
                        .width(90.dp)
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
                            // Calculate progress: 0.0 to 1.0
                            // Clamp currentPosition to [0, duration] to ensure proportional bar
                            val clampedPosition = currentPosition.coerceIn(0L, duration)
                            val progress = clampedPosition.toFloat() / duration.toFloat()
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

                    // Time display - use only actual duration from service, not saved estimates
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            formatTime(currentPosition),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        // Display duration that matches the progress bar calculation
                        Text(
                            if (duration > 0) formatTime(duration) else "--:--",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                    }
                }

                // Home button - wide button below progress bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            Log.d("PlayerScreen", "Bottom home button clicked")
                            saveProgress()
                            onBack()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.15f)
                        )
                    ) {
                        Text("🏠 Home", fontSize = 16.sp, color = Color.White)
                    }

                    Button(
                        onClick = {
                            Log.d("PlayerScreen", "Bottom next button clicked. currentSong=${song.title} serviceConnected=$connected")
                            moveToNextTrack(hideCurrent = false)
                        },
                        modifier = Modifier
                            .width(120.dp)
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF10b981).copy(alpha = 0.25f)
                        ),
                        enabled = songs.isNotEmpty()
                    ) {
                        Text("Next ⏭", fontSize = 16.sp, color = Color.White)
                    }
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
