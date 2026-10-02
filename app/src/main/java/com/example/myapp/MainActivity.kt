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
import com.example.myapp.data.SongScanner
import com.example.myapp.service.PlayerService
import com.example.myapp.ui.screens.FilePickerScreen
import com.example.myapp.ui.screens.SongListScreen
import com.example.myapp.ui.screens.PlayerScreen
import com.example.myapp.ui.theme.MyAppTheme
import com.example.myapp.viewmodel.PlayerViewModel

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
                    
                    // Save folder URI
                    val storageManager = com.example.myapp.data.StorageManager(context)
                    storageManager.setFolderUri(it.toString())
                    
                    // Restore last active track if it exists
                    val lastActiveTrackName = storageManager.getLastActiveTrack()
                    val lastActiveSong = found.find { song -> song.title == lastActiveTrackName }
                    if (lastActiveSong != null) {
                        playerViewModel.setCurrentSong(lastActiveSong)
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
            val savedFolder = storageManager.getFolderUri()
            
            if (savedFolder != null) {
                try {
                    val scanner = SongScanner(context)
                    val found = scanner.scanFolderForMp3s(savedFolder)
                    
                    if (found.isNotEmpty()) {
                        playerViewModel.setSongs(found)
                        
                        // Restore last active track
                        val lastActiveTrackName = storageManager.getLastActiveTrack()
                        val lastActiveSong = found.find { song -> song.title == lastActiveTrackName }
                        if (lastActiveSong != null) {
                            playerViewModel.setCurrentSong(lastActiveSong)
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
            SongListScreen(
                songs = songs,
                currentSongId = currentSong?.id,
                onSongClick = { song ->
                    // Save current track progress before switching
                    if (currentSong != null && currentSong?.id != song.id) {
                        playerViewModel.setCurrentSong(song)
                        navController.navigate("player")
                    } else if (currentSong?.id == song.id) {
                        // Same song, just navigate
                        navController.navigate("player")
                    } else {
                        // First song
                        playerViewModel.setCurrentSong(song)
                        navController.navigate("player")
                    }
                },
                onFolderButtonClick = {
                    // Launch folder picker directly
                    folderPickerLauncher.launch(null)
                }
            )
        }

        composable("player") {
            if (currentSong != null) {
                val context = LocalContext.current
                val songToPlay = currentSong
                LaunchedEffect(songToPlay) {
                    val storageManager = com.example.myapp.data.StorageManager(context)
                    storageManager.setLastActiveTrack(songToPlay?.title)
                }
                PlayerScreen(
                    song = songToPlay!!,
                    isPlaying = isPlaying,
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
                        val newPos = (service.getCurrentPosition() - 45_000L).coerceAtLeast(0L)
                        service.seekTo(newPos)
                    },
                    onFastForward = { service ->
                        val duration = service.getDuration()
                        val newPos = (service.getCurrentPosition() + 9_000L)
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
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startService(Intent(this, PlayerService::class.java))

        setContent {
            MyAppTheme {
                NavigationHost(context = this)
            }
        }
    }
}
