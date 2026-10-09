package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import androidx.media3.common.util.UnstableApi
import com.example.myapp.data.Song
import com.example.myapp.data.SongScanner
import com.example.myapp.service.PlayerService
import com.example.myapp.ui.screens.FilePickerScreen
import com.example.myapp.ui.screens.SongListScreen
import com.example.myapp.ui.screens.PlayerScreen
import com.example.myapp.ui.theme.MyAppTheme
import com.example.myapp.viewmodel.PlayerViewModel

@OptIn(UnstableApi::class)
@Composable
fun NavigationHost(
    context: android.content.Context,
    navController: NavHostController = rememberNavController(),
    playerViewModel: PlayerViewModel = viewModel()
) {
    // Collect StateFlow as Compose State so the UI recomposes on changes
    val songs by playerViewModel.songs.collectAsState()
    val currentSong by playerViewModel.currentSong.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    val selectionToken by playerViewModel.selectionToken.collectAsState()

    // Folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        uri?.let {
            try {
                // Take a persistent permission
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                
                // Scan folder
                val scanner = SongScanner(context)
                val found = scanner.scanFolderForMp3s(it.toString())
                Log.d("MainActivity", "Found ${found.size} MP3 files")

                if (found.isNotEmpty()) {
                    playerViewModel.setSongs(found)
                    
                    // Save folder URI and add to history
                    val storageManager = com.example.myapp.data.StorageManager(context)
                    storageManager.setFolderUri(it.toString())
                    storageManager.addFolderToHistory(it.toString())
                    
                    // Restore last active track if it exists
                    val lastActiveTrackName = storageManager.getLastActiveTrack()
                    val lastActiveSong = found.find { song ->
                        song.id.toString() == lastActiveTrackName
                    }
                    if (lastActiveSong != null) {
                        playerViewModel.selectSong(lastActiveSong, playing = true)
                    }
                    
                    navController.navigate("songList") {
                        popUpTo("songList") { inclusive = true }
                    }
                } else {
                    Toast.makeText(context, "No MP3 files found in that folder", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Scan error", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Auto-load saved folder on app start
    var hasAutoLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!hasAutoLoaded) {
            hasAutoLoaded = true
            val storageManager = com.example.myapp.data.StorageManager(context)
            val savedFolder = storageManager.getCurrentFolderUri()
            
            if (savedFolder != null) {
                try {
                    val scanner = SongScanner(context)
                    val found = scanner.scanFolderForMp3s(savedFolder)
                    
                    if (found.isNotEmpty()) {
                        playerViewModel.setSongs(found)
                        
                        // Restore last active track
                        val lastActiveTrackName = storageManager.getLastActiveTrack()
                        val lastActiveSong = found.find { song ->
                            song.id.toString() == lastActiveTrackName
                        }
                        if (lastActiveSong != null) {
                            playerViewModel.selectSong(lastActiveSong, playing = true)
                        }
                        
                        navController.navigate("songList") {
                            popUpTo("start") { inclusive = true }
                        }
                    } else {
                        // Saved folder has no MP3s, launch picker
                        folderPickerLauncher.launch(null)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Auto-load error", e)
                    // On error, launch picker
                    folderPickerLauncher.launch(null)
                }
            } else {
                // No saved folder, launch picker
                folderPickerLauncher.launch(null)
            }
        }
    }

    // Canonical way to select/open a song: always update the view-model first,
    // then navigate. Old screens should never mutate the active song on their own.
    fun buildSongDebugInfo(song: Song, source: String): String {
        val activeSong = currentSong
        return buildString {
            append("source=$source\n")
            append("clickedSongId=${song.id}\n")
            append("clickedSongUri=${song.uri}\n")
            append("activeSongId=${activeSong?.id ?: "null"}\n")
            append("activeSongUri=${activeSong?.uri ?: "null"}\n")
            append("isPlaying=$isPlaying\n")
            append("selectionToken=$selectionToken\n")
            append("route=${navController.currentDestination?.route ?: "null"}\n")
            append("songsCount=${songs.size}\n")
            append("currentSongNull=${currentSong == null}")
        }
    }

    fun openPlayer(song: Song) {
        try {
            val isSameSelectedSong = currentSong?.id == song.id
            if (isSameSelectedSong && isPlaying) {
                navController.navigate("player")
                return
            }

            playerViewModel.selectSong(song, playing = true)
            navController.navigate("player")
        } catch (t: Throwable) {
            val debugInfo = buildSongDebugInfo(song, "openPlayer")
            Log.e("MainActivity", "openPlayer failed\n$debugInfo", t)
            Toast.makeText(
                context,
                "Pinned-track open failed\n${t::class.simpleName}: ${t.message}\n\nDebug:\n$debugInfo",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Newer selection wins over any stale screen state. Old screens should skip
    // any mutation when their selection token is no longer current.
    fun isCurrentSelection(song: Song): Boolean {
        return playerViewModel.isSelectionCurrent(song.id, selectionToken)
    }

    NavHost(navController = navController, startDestination = "start") {
        composable("start") {
            // Empty placeholder screen while loading
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color(0xFF667eea)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = androidx.compose.ui.graphics.Color.White)
            }
        }

        composable("songList") {
            val storageManager = remember { com.example.myapp.data.StorageManager(context) }
            val folderDisplayName = remember(songs) { storageManager.getCurrentFolderDisplayName() }
            val isShowingAllFolders = remember(songs) { storageManager.isShowAllFolders() }
            
            SongListScreen(
                songs = songs,
                currentSongId = currentSong?.id,
                onSongClick = { song ->
                    if (currentSong?.uri == song.uri && isPlaying) {
                        // Same song (by URI) and actively playing — just navigate, don't restart
                        navController.navigate("player")
                    } else {
                        // Different song, or same song but paused — load and play
                        openPlayer(song)
                    }
                },
                onPinnedTrackClick = { song ->
                    // M6b cleanup: all pinned-track navigations go through the same canonical selection path
                    openPlayer(song)
                },
                onFolderButtonClick = {
                    // Launch folder picker directly
                    folderPickerLauncher.launch(null)
                },
                onSwitchButtonClick = {
                    // Cycle to the next folder (or "All") in history and load its tracks
                    val storageManager = com.example.myapp.data.StorageManager(context)
                    val history = storageManager.getFolderHistory()
                    
                    if (history.size > 1) {
                        val currentIdx = storageManager.getCurrentFolderIndex()
                        val isCurrentlyShowingAll = storageManager.isShowAllFolders()
                        
                        // Determine next index
                        val nextIdx = if (isCurrentlyShowingAll) {
                            // Currently showing all, go back to first folder
                            storageManager.setShowAllFolders(false)
                            0
                        } else {
                            // Not showing all, check if next would be past the end
                            val tentativeIdx = (currentIdx + 1) % history.size
                            if (tentativeIdx == 0) {
                                // We've wrapped around, show "All" instead
                                storageManager.setShowAllFolders(true)
                                currentIdx // Keep same index internally, but show all
                            } else {
                                tentativeIdx
                            }
                        }
                        
                        try {
                            val scanner = SongScanner(context)
                            val found = if (storageManager.isShowAllFolders()) {
                                // Scan all folders and combine
                                scanner.scanMultipleFoldersForMp3s(history)
                            } else {
                                // Scan just the next folder
                                val nextFolderUri = history[nextIdx]
                                storageManager.setCurrentFolderIndex(nextIdx)
                                scanner.scanFolderForMp3s(nextFolderUri)
                            }
                            
                            if (found.isNotEmpty()) {
                                playerViewModel.setSongs(found)
                                // Clear current song so no stale track from old folder is pinned
                                playerViewModel.clearCurrentSong()
                            } else {
                                Toast.makeText(context, "No MP3 files found", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Switch folder error", e)
                            Toast.makeText(context, "Error loading folder: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    // Only 1 (or 0) folders in history — do nothing
                },
                onSongsDeleted = { deletedSongs ->
                    playerViewModel.removeSongs(deletedSongs)
                },
                folderDisplayName = folderDisplayName,
                isShowingAllFolders = isShowingAllFolders
            )
        }

        composable("player") {
            if (currentSong != null) {
                val context = LocalContext.current
                val songToPlay = currentSong
                LaunchedEffect(songToPlay) {
                    val storageManager = com.example.myapp.data.StorageManager(context)
                    storageManager.setLastActiveTrack(songToPlay)
                }
                
                val storageManager = com.example.myapp.data.StorageManager(context)
                val hiddenTracks = storageManager.getHiddenTracks()
                val currentSongIndex = songs.indexOfFirst { it.uri == songToPlay?.uri }
                
                PlayerScreen(
                    song = songToPlay!!,
                    isPlaying = isPlaying,
                    songs = songs,
                    hiddenTracks = hiddenTracks,
                    currentSongIndex = currentSongIndex,
                    playerViewModel = playerViewModel,
                    onPlayPause = { service ->
                        if (isPlaying) {
                            service.pause()
                            playerViewModel.setIsPlaying(false)
                        } else {
                            service.play()
                            playerViewModel.setIsPlaying(true)
                        }
                    },
                    onRewind = { service ->
                        val newPos = (service.getCurrentPosition() - 7_000L).coerceAtLeast(0L)
                        service.seekTo(newPos)
                    },
                    onFastForward = { service ->
                        val duration = service.getDuration()
                        val newPos = (service.getCurrentPosition() + 7_000L)
                            .let { if (duration > 0) it.coerceAtMost(duration) else it }
                        service.seekTo(newPos)
                    },
                    onRewind45 = { service ->
                        val newPos = (service.getCurrentPosition() - 45_000L).coerceAtLeast(0L)
                        service.seekTo(newPos)
                    },
                    onFastForward45 = { service ->
                        val duration = service.getDuration()
                        val newPos = (service.getCurrentPosition() + 45_000L)
                            .let { if (duration > 0) it.coerceAtMost(duration) else it }
                        service.seekTo(newPos)
                    },
                    onBack = { navController.popBackStack() },
                    onTrackHideAndNext = { nextSong ->
                        playerViewModel.setLastLoadedSongUri("")
                        playerViewModel.selectSong(nextSong, playing = true)
                        if (navController.currentDestination?.route == "player") {
                            navController.popBackStack(route = "player", inclusive = true)
                        }
                        navController.navigate("player")
                    },
                    onSkipToNext = { nextSong ->
                        playerViewModel.setLastLoadedSongUri("")
                        playerViewModel.selectSong(nextSong, playing = true)
                        if (navController.currentDestination?.route == "player") {
                            navController.popBackStack(route = "player", inclusive = true)
                        }
                        navController.navigate("player")
                    }
                )
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logLifecycle("onCreate")
        startService(Intent(this, PlayerService::class.java))

        setContent {
            MyAppTheme {
                NavigationHost(context = this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        logLifecycle("onStart")
    }

    override fun onResume() {
        super.onResume()
        logLifecycle("onResume")
    }

    override fun onPause() {
        logLifecycle("onPause")
        super.onPause()
    }

    override fun onStop() {
        logLifecycle("onStop")
        super.onStop()
    }

    override fun onDestroy() {
        logLifecycle("onDestroy")
        super.onDestroy()
    }

    private fun logLifecycle(event: String) {
        Log.d(
            "MainActivity",
            "$event pid=${android.os.Process.myPid()} activity=${System.identityHashCode(this)} " +
                "finishing=$isFinishing changingConfigurations=$isChangingConfigurations"
        )
    }

}
