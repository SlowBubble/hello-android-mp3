package com.example.myapp.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun FilePickerScreen(
    onFolderSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            // Take a persistent permission so the URI survives app restarts
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            selectedFolder = it.toString()
            onFolderSelected(it.toString())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("MP3 Player", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Select a folder containing MP3 files")
        Spacer(modifier = Modifier.height(32.dp))

        Button(onClick = { launcher.launch(null) }) {
            Text("Choose Folder")
        }

        if (selectedFolder != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text("Selected: $selectedFolder", style = MaterialTheme.typography.bodySmall)
        }
    }
}
