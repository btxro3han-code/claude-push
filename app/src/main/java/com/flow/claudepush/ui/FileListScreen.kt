package com.flow.claudepush.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flow.claudepush.ReceivedFile
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(
    ip: String,
    wifiName: String? = null,
    hasWifi: Boolean = true,
    isRunning: Boolean,
    files: List<ReceivedFile>,
    macConnected: Boolean,
    onToggleServer: () -> Unit,
    onFileClick: (ReceivedFile) -> Unit,
    onDeleteFile: (ReceivedFile) -> Unit,
    onDeleteFiles: (List<ReceivedFile>) -> Unit,
    onHideFile: (ReceivedFile) -> Unit,
    onSendToMac: () -> Unit,
    onSendClipboard: () -> Unit,
    onSendScreenshot: () -> Unit,
) {
    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    // Exit select mode when files change
    LaunchedEffect(files) {
        if (selectMode && selected.none { name -> files.any { it.name == name } }) {
            selectMode = false
            selected = emptySet()
        }
    }

    Scaffold(
        topBar = {
            if (selectMode) {
                TopAppBar(
                    title = { Text("已选 ${selected.size} 项") },
                    navigationIcon = {
                        IconButton(onClick = { selectMode = false; selected = emptySet() }) {
                            Text("✕", style = MaterialTheme.typography.titleMedium)
                        }
                    },
                    actions = {
                        TextButton(onClick = {
                            selected = if (selected.size == files.size)
                                emptySet()
                            else
                                files.map { it.name }.toSet()
                        }) {
                            Text(if (selected.size == files.size) "取消全选" else "全选")
                        }
                        TextButton(
                            onClick = {
                                val toDelete = files.filter { it.name in selected }
                                onDeleteFiles(toDelete)
                                selectMode = false
                                selected = emptySet()
                            },
                            enabled = selected.isNotEmpty()
                        ) {
                            Text("删除", color = if (selected.isNotEmpty())
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Claude Push") },
                    actions = {
                        if (isRunning) {
                            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                Text(" ON ", color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Screenshot button
                SmallFloatingActionButton(
                    onClick = onSendScreenshot,
                    containerColor = if (macConnected)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text("📸")
                }
                // Clipboard button
                SmallFloatingActionButton(
                    onClick = onSendClipboard,
                    containerColor = if (macConnected)
                        MaterialTheme.colorScheme.secondary
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text("📋")
                }
                // File picker button
                FloatingActionButton(
                    onClick = onSendToMac,
                    containerColor = if (macConnected)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text("⬆", style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Status card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (isRunning) {
                        if (!hasWifi) {
                            Text(
                                "⚠️ 需要WiFi连接",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "请连接WiFi网络，仅蜂窝网络无法使用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            if (wifiName != null) {
                                Text(
                                    "📶 $wifiName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "$ip:${com.flow.claudepush.PushService.SERVER_PORT}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                if (macConnected) "💻 Mac connected" else "💻 Searching for Mac...",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (macConnected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            "Server stopped",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onToggleServer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isRunning) "Stop Server" else "Start Server")
                    }
                }
            }

            // File count
            if (files.isNotEmpty()) {
                Text(
                    "${files.size} file(s)",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // File list
            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Waiting for files from Claude Code...\n\nUse /push <file> to send files here",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files, key = { it.name }) { file ->
                        FileRow(
                            file = file,
                            selectMode = selectMode,
                            isSelected = file.name in selected,
                            onClick = {
                                if (selectMode) {
                                    selected = if (file.name in selected)
                                        selected - file.name
                                    else
                                        selected + file.name
                                } else {
                                    onFileClick(file)
                                }
                            },
                            onLongClick = {
                                if (!selectMode) {
                                    selectMode = true
                                    selected = setOf(file.name)
                                }
                            },
                            onDelete = { onDeleteFile(file) },
                            onHide = { onHideFile(file) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: ReceivedFile,
    selectMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onHide: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    val icon = when (file.type) {
        "apk" -> "\uD83D\uDCE6"  // package
        "image" -> "\uD83D\uDDBC"  // picture
        "text" -> "\uD83D\uDCC4"  // document
        else -> "\uD83D\uDCC1"  // folder
    }
    val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    Box {
        ListItem(
            modifier = Modifier.combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (!selectMode) onLongClick()
                    else showMenu = true
                }
            ),
            headlineContent = {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text("${formatSize(file.size)}  |  ${sdf.format(Date(file.timestamp))}")
            },
            leadingContent = {
                if (selectMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() }
                    )
                } else {
                    Text(icon, style = MaterialTheme.typography.headlineMedium)
                }
            },
            trailingContent = {
                if (!selectMode) {
                    Row {
                        if (file.type == "apk") {
                            FilledTonalButton(onClick = onClick) {
                                Text("Install")
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        IconButton(onClick = { showMenu = true }) {
                            Text("\u2716")
                        }
                    }
                }
            }
        )
        if (!selectMode) {
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("从列表移除") },
                    onClick = { showMenu = false; onHide() }
                )
                DropdownMenuItem(
                    text = { Text("删除文件") },
                    onClick = { showMenu = false; onDelete() }
                )
            }
        }
    }
    HorizontalDivider()
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
