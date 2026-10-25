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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
    val overlay by playerViewModel.overlay.collectAsState()

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
    var hasAutoLoaded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!hasAutoLoaded) {
            hasAutoLoaded = true
            val storageManager = com.example.myapp.data.StorageManager(context)
            // "All" is no longer part of folder cycling; clear any stale flag
            storageManager.setShowAllFolders(false)
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
                playerViewModel.dismissOverlay()
                return
            }

            playerViewModel.dismissOverlay()
            playerViewModel.selectSong(song, playing = true)
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

    /**
     * M8e: Cycle to the next folder that actually contains MP3s.
     *
     * Folders with no MP3s are skipped and we keep cycling forward. The current
     * folder index only moves when a non-empty folder is found, so the UI never
     * ends up pointing at an empty folder while still showing the old songs.
     * 
     * This now shows an overlay instead of clearing the current song, so playback
     * continues uninterrupted while browsing folders.
     */
    fun switchToNextFolder() {
        val storageManager = com.example.myapp.data.StorageManager(context)
        val history = storageManager.getFolderHistory()

        // Need more than one folder to cycle; otherwise there's nowhere to go.
        if (history.size <= 1) return

        // Skip "All" when cycling — only advance through real folders.
        storageManager.setShowAllFolders(false)

        val currentIdx = storageManager.getCurrentFolderIndex()
        val scanner = SongScanner(context)

        // Walk forward through every other folder, skipping any with no MP3s.
        val foldersToCheck = history.size - 1
        var nextIdx = currentIdx
        var foundSongs: List<Song> = emptyList()
        var checkedCount = 0
        while (foundSongs.isEmpty() && checkedCount < foldersToCheck) {
            nextIdx = (nextIdx + 1) % history.size
            checkedCount++
            try {
                foundSongs = scanner.scanFolderForMp3s(history[nextIdx])
            } catch (e: Exception) {
                Log.e("MainActivity", "Switch folder error at index $nextIdx", e)
                // Keep cycling — a folder that errors out is treated like an empty one.
            }
        }

        if (foundSongs.isNotEmpty()) {
            storageManager.setCurrentFolderIndex(nextIdx)
            playerViewModel.setSongs(foundSongs)
            // Show overlay instead of clearing the song, so playback continues
            playerViewModel.showFolderSwitchOverlay()
        } else {
            Toast.makeText(context, "No MP3 files found in other folders", Toast.LENGTH_SHORT).show()
        }
    }

    fun removeCurrentFolderAndCycle() {
        val storageManager = com.example.myapp.data.StorageManager(context)
        val history = storageManager.getFolderHistory()

        // Need at least one folder to be left
        if (history.isEmpty()) {
            Toast.makeText(context, "No folders to remove", Toast.LENGTH_SHORT).show()
            return
        }

        val currentIdx = storageManager.getCurrentFolderIndex()
        storageManager.removeFolderFromHistory(currentIdx)

        val updatedHistory = storageManager.getFolderHistory()
        if (updatedHistory.isEmpty()) {
            Toast.makeText(context, "Removed last folder. Please add a new one.", Toast.LENGTH_SHORT).show()
            playerViewModel.setSongs(emptyList())
            playerViewModel.clearCurrentSong()
            return
        }

        // After removal, cycle to the next available folder with songs
        val scanner = SongScanner(context)
        val newIdx = storageManager.getCurrentFolderIndex()
        var foundSongs: List<Song> = emptyList()
        var checkedCount = 0
        var nextIdx = newIdx

        // Try to find a folder with songs, checking up to the number of remaining folders
        while (foundSongs.isEmpty() && checkedCount < updatedHistory.size) {
            try {
                foundSongs = scanner.scanFolderForMp3s(updatedHistory[nextIdx])
            } catch (e: Exception) {
                Log.e("MainActivity", "Remove folder scan error at index $nextIdx", e)
            }
            if (foundSongs.isEmpty() && checkedCount < updatedHistory.size - 1) {
                nextIdx = (nextIdx + 1) % updatedHistory.size
                checkedCount++
            } else {
                break
            }
        }

        if (foundSongs.isNotEmpty()) {
            storageManager.setCurrentFolderIndex(nextIdx)
            playerViewModel.setSongs(foundSongs)
            // Show overlay instead of clearing the song, so playback continues
            playerViewModel.showFolderSwitchOverlay()
            Toast.makeText(context, "Removed folder, switched to next", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "No MP3 files found in remaining folders", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * M9b: When the final track of a folder ends, automatically switch to the next folder
     * and play the first track from that folder. If there's only one folder or no more folders,
     * return to the song list screen.
     */
    fun onQueueEnded() {
        val storageManager = com.example.myapp.data.StorageManager(context)
        val history = storageManager.getFolderHistory()

        // If only one folder or no folders, go back to song list
        if (history.size <= 1) {
            navController.popBackStack()
            return
        }

        // Try to find the next folder with songs
        val currentIdx = storageManager.getCurrentFolderIndex()
        val scanner = SongScanner(context)
        var foundSongs: List<Song> = emptyList()
        var nextIdx = currentIdx
        var checkedCount = 0
        val foldersToCheck = history.size - 1

        while (foundSongs.isEmpty() && checkedCount < foldersToCheck) {
            nextIdx = (nextIdx + 1) % history.size
            checkedCount++
            try {
                foundSongs = scanner.scanFolderForMp3s(history[nextIdx])
            } catch (e: Exception) {
                Log.e("MainActivity", "Auto-advance folder scan error at index $nextIdx", e)
            }
        }

        if (foundSongs.isNotEmpty()) {
            // Switch to the next folder
            storageManager.setCurrentFolderIndex(nextIdx)
            playerViewModel.setSongs(foundSongs)
            
            // Auto-play the first track from the new folder
            val firstSong = foundSongs.first()
            playerViewModel.selectSong(firstSong, playing = true)
            Log.d("MainActivity", "Auto-advanced to next folder, playing: ${firstSong.title}")
        } else {
            // No more folders with songs, go back to song list
            Log.d("MainActivity", "No more folders with songs, returning to song list")
            navController.popBackStack()
        }
    }

    if (currentSong == null) {
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
                            playerViewModel.dismissOverlay()
                        } else {
                            openPlayer(song)
                        }
                    },
                    onPinnedTrackClick = { song ->
                        openPlayer(song)
                    },
                    onFolderButtonClick = {
                        folderPickerLauncher.launch(null)
                    },
                    onSwitchButtonClick = { switchToNextFolder() },
                    onRemoveCurrentFolderClick = { removeCurrentFolderAndCycle() },
                    onSongsDeleted = { deletedSongs ->
                        playerViewModel.removeSongs(deletedSongs)
                    },
                    folderDisplayName = folderDisplayName,
                    isShowingAllFolders = isShowingAllFolders
                )
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            val storageManager = remember { com.example.myapp.data.StorageManager(context) }
            val hiddenTracks = storageManager.getHiddenTracks()
            val currentSongIndex = songs.indexOfFirst { it.uri == currentSong?.uri }

            PlayerScreen(
                song = currentSong!!,
                isPlaying = isPlaying,
                songs = songs,
                hiddenTracks = hiddenTracks,
                currentSongIndex = currentSongIndex,
                playerViewModel = playerViewModel,
                onPlayPause = { service ->
                    if (isPlaying) {
                        service.pause()
                        // Don't manually set ViewModel state - let the service state propagate back
                    } else {
                        service.play()
                        // Don't manually set ViewModel state - let the service state propagate back
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
                onBack = {
                    if (overlay != com.example.myapp.viewmodel.PlayerOverlay.NONE) {
                        playerViewModel.dismissOverlay()
                    } else {
                        navController.popBackStack()
                    }
                },
                onShowHome = {
                    if (overlay == com.example.myapp.viewmodel.PlayerOverlay.NONE) {
                        playerViewModel.showHomeOverlay()
                    } else {
                        playerViewModel.dismissOverlay()
                    }
                },
                onTrackHideAndNext = { nextSong ->
                    playerViewModel.setLastLoadedSongUri("")
                    playerViewModel.selectSong(nextSong, playing = true)
                    playerViewModel.dismissOverlay()
                },
                onSkipToNext = { nextSong ->
                    playerViewModel.setLastLoadedSongUri("")
                    playerViewModel.selectSong(nextSong, playing = true)
                    playerViewModel.dismissOverlay()
                },
                onQueueEnded = { onQueueEnded() },
                onSwitchFolder = { switchToNextFolder() }
            )

            if (overlay != com.example.myapp.viewmodel.PlayerOverlay.NONE) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.28f))
                ) {
                    val storageManager = remember { com.example.myapp.data.StorageManager(context) }
                    val folderDisplayName = remember(songs) { storageManager.getCurrentFolderDisplayName() }
                    val isShowingAllFolders = remember(songs) { storageManager.isShowAllFolders() }

                    SongListScreen(
                        songs = songs,
                        currentSongId = currentSong?.id,
                        onSongClick = { song ->
                            // Edge case: clicking a song in folder-B overlay stops old playback and starts new
                            // openPlayer already handles clearing the old song and selecting the new one
                            openPlayer(song)
                        },
                        onPinnedTrackClick = { song ->
                            openPlayer(song)
                        },
                        onFolderButtonClick = {
                            folderPickerLauncher.launch(null)
                        },
                        onSwitchButtonClick = { switchToNextFolder() },
                        onRemoveCurrentFolderClick = { removeCurrentFolderAndCycle() },
                        onSongsDeleted = { deletedSongs ->
                            playerViewModel.removeSongs(deletedSongs)
                        },
                        folderDisplayName = folderDisplayName,
                        isShowingAllFolders = isShowingAllFolders,
                        isHiddenOverlay = overlay == com.example.myapp.viewmodel.PlayerOverlay.HIDDEN,
                        onShowHome = { playerViewModel.showHomeOverlay() },
                        onShowHidden = { playerViewModel.showHiddenOverlay() },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 56.dp)
                    )
                }
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
