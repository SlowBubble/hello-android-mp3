package com.example.myapp.ui.screens

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.myapp.data.Song
import com.example.myapp.service.PlayerService

@Composable
fun PlayerScreen(
    song: Song,
    isPlaying: Boolean,
    onPlayPause: (PlayerService) -> Unit,
    onRewind: (PlayerService) -> Unit,
    onFastForward: (PlayerService) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var service by remember { mutableStateOf<PlayerService?>(null) }
    var connected by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val connection = remember(context) {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder is PlayerService.LocalBinder) {
                    service = binder.getService()
                    connected = true
                    service?.playUri(song.uri.toString())
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(song.title, style = MaterialTheme.typography.headlineMedium)
        Text(song.artist, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = { service?.let { onPlayPause(it) } }) {
            Text(if (isPlaying) "Pause" else "Play")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { service?.let { onRewind(it) } }) {
                Text("Rewind 45s")
            }
            Button(onClick = { service?.let { onFastForward(it) } }) {
                Text("Fast Forward 9s")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = onBack) {
            Text("Back to List")
        }
    }
}
