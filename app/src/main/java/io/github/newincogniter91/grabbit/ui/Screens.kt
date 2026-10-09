@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.newincogniter91.grabbit.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.newincogniter91.grabbit.MainViewModel
import io.github.newincogniter91.grabbit.OutFormat
import io.github.newincogniter91.grabbit.UiState

const val DISCLAIMER =
    "Grabbit is a general-purpose download front end for yt-dlp. Use it only for content you own, " +
        "content that is licensed for free redistribution, or content you have explicit permission " +
        "to download. Downloading copyrighted material without permission may violate copyright law " +
        "and the terms of service of the sites you use. You are solely responsible for how you use " +
        "this app. Grabbit is not affiliated with YouTube or any other service."

@Composable
fun GrabbitRoot(vm: MainViewModel, onExit: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showSettings) { showSettings = false }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.safeDrawingPadding()) {
            if (showSettings) {
                SettingsScreen(state, vm, onBack = { showSettings = false })
            } else {
                MainScreen(state, vm, onSettings = { showSettings = true })
            }
        }
    }

    if (!state.disclaimerAccepted) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Before you start") },
            text = { Text(DISCLAIMER) },
            confirmButton = { TextButton(onClick = vm::acceptDisclaimer) { Text("I understand") } },
            dismissButton = { TextButton(onClick = onExit) { Text("Exit") } },
        )
    }
}

@Composable
fun MainScreen(state: UiState, vm: MainViewModel, onSettings: () -> Unit) {
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildAnnotatedString {
                    append("grab")
                    withStyle(SpanStyle(color = Red)) { append("bit") }
                },
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }

        Spacer(Modifier.height(48.dp))
        Text(
            text = "Download from any link",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Paste a URL, pick a format, done. Everything runs locally on your phone.",
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(32.dp))

        Surface(shape = RoundedCornerShape(20.dp), color = Panel, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = vm::setUrl,
                    placeholder = { Text("URL…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(onClick = { clipboard.getText()?.text?.let(vm::setUrl) }) {
                            Text("Paste")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.download() }),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FormatPicker(state.format, onPick = vm::setFormat, enabled = !state.busy)
                    Button(
                        onClick = vm::download,
                        enabled = state.ready && !state.busy && state.url.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Red),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                    ) {
                        Text("Download", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (!state.ready && state.initError == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("Preparing engine (first launch can take a moment)…", color = Muted)
            }
        }
        state.initError?.let { Text(it, color = Red) }

        if (state.busy) {
            if (state.progress <= 0f) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Text(state.status, color = Muted)
            TextButton(onClick = vm::cancel) { Text("Cancel") }
        } else if (state.status.isNotEmpty()) {
            Text(state.status, color = if (state.isError) Red else Muted)
        }

        Spacer(Modifier.height(32.dp))
        Text(
            text = "Files are saved to ${state.saveFolderLabel}.",
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun FormatPicker(selected: OutFormat, onPick: (OutFormat) -> Unit, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        Button(
            onClick = { open = true },
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = BlueBg, contentColor = Blue),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.height(52.dp),
        ) {
            Text(selected.label, fontWeight = FontWeight.Bold)
            Text(" ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            OutFormat.entries.forEach { format ->
                DropdownMenuItem(
                    text = { Text(format.label) },
                    onClick = {
                        onPick(format)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(state: UiState, vm: MainViewModel, onBack: () -> Unit) {
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setSaveFolder(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(24.dp))
        Text("Appearance", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.darkTheme,
                onClick = { vm.setDarkTheme(true) },
                label = { Text("Night") },
            )
            FilterChip(
                selected = !state.darkTheme,
                onClick = { vm.setDarkTheme(false) },
                label = { Text("Day") },
            )
        }

        Spacer(Modifier.height(32.dp))
        Text("Save location", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(state.saveFolderLabel, color = Muted)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { folderPicker.launch(null) },
                colors = ButtonDefaults.buttonColors(containerColor = Red),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Choose folder")
            }
            if (state.customFolder) {
                TextButton(onClick = vm::resetSaveFolder) { Text("Use default") }
            }
        }

        Spacer(Modifier.height(32.dp))
        Text("Download engine", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("yt-dlp version: ${state.engineVersion.ifBlank { "unknown" }}", color = Muted)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = vm::updateEngine,
            enabled = state.ready && !state.updating && !state.busy,
            colors = ButtonDefaults.buttonColors(containerColor = Red),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(if (state.updating) "Updating…" else "Update yt-dlp")
        }
        if (state.updateMessage.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(state.updateMessage, color = Muted)
        }

        Spacer(Modifier.height(32.dp))
        Text("Disclaimer", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(DISCLAIMER, color = Muted)
    }
}
