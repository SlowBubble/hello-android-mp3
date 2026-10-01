package com.example.myapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

    NavHost(navController = navController, startDestination = "filePicker") {
        composable("filePicker") {
            FilePickerScreen(
                onFolderSelected = { folderUri ->
                    Log.d("MainActivity", "Folder selected: $folderUri")
                    try {
                        val scanner = SongScanner(context)
                        val found = scanner.scanFolderForMp3s(folderUri)
                        Log.d("MainActivity", "Found ${found.size} MP3 files")

                        if (found.isNotEmpty()) {
                            playerViewModel.setSongs(found)
                            
                            // Save folder URI for persistence
                            val storageManager = com.example.myapp.data.StorageManager(context)
                            storageManager.setFolderUri(folderUri)
                            
                            // Restore last active track if it exists
                            val lastActiveTrackName = storageManager.getLastActiveTrack()
                            val lastActiveSong = found.find { it.title == lastActiveTrackName }
                            if (lastActiveSong != null) {
                                playerViewModel.setCurrentSong(lastActiveSong)
                            }
                            
                            navController.navigate("songList") {
                                popUpTo("filePicker") { inclusive = true }
                            }
                        } else {
                            Toast.makeText(context, "No MP3 files found in that folder", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Scan error", e)
                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        composable("songList") {
            SongListScreen(
                songs = songs,
                currentSongId = currentSong?.id,
                onSongClick = { song ->
                    playerViewModel.setCurrentSong(song)
                    navController.navigate("player")
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
