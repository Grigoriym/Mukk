package com.grappim.mukk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grappim.mukk.core.model.FolderTreeState
import com.grappim.mukk.ui.components.instantClickable
import com.grappim.mukk.core.model.ScanProgress
import java.io.File

private data class TreeItem(
    val path: String,
    val name: String,
    val depth: Int,
    val isExpanded: Boolean,
    val isSelected: Boolean,
    val isPlaying: Boolean,
    val hasChildren: Boolean
)

@Composable
fun FolderTreePanel(
    folderTreeState: FolderTreeState,
    playingFolderPath: String?,
    scanProgress: ScanProgress,
    onToggleExpand: (String) -> Unit,
    onSelectFolder: (String) -> Unit,
    onOpenFolderClick: () -> Unit,
    onRescanClick: () -> Unit,
    onSettingsClick: () -> Unit,
    getSubfolders: (String) -> List<Pair<File, Boolean>>,
    modifier: Modifier = Modifier
) {
    val rootPath = folderTreeState.rootPath

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        HeaderRow(
            onOpenFolderClick = onOpenFolderClick,
            onRescanClick = onRescanClick,
            onSettingsClick = onSettingsClick,
            showRescan = rootPath != null
        )

        if (scanProgress.isScanning) {
            if (scanProgress.total > 0) {
                LinearProgressIndicator(
                    progress = { scanProgress.scanned.toFloat() / scanProgress.total },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Scanning ${scanProgress.scanned} / ${scanProgress.total}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (rootPath == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Open a folder to browse",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val treeItems = remember(
                rootPath,
                folderTreeState.expandedPaths,
                folderTreeState.selectedPath,
                playingFolderPath
            ) {
                buildTreeItems(rootPath, folderTreeState, playingFolderPath, getSubfolders)
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(treeItems, key = { it.path }) { item ->
                    FolderRow(
                        item = item,
                        onToggleExpand = onToggleExpand,
                        onSelect = onSelectFolder
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderRow(
    onOpenFolderClick: () -> Unit,
    onRescanClick: () -> Unit,
    onSettingsClick: () -> Unit,
    showRescan: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Mukk",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onSettingsClick, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        if (showRescan) {
            IconButton(onClick = onRescanClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Rescan",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        IconButton(onClick = onOpenFolderClick, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.CreateNewFolder,
                contentDescription = "Open Folder",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun FolderRow(
    item: TreeItem,
    onToggleExpand: (String) -> Unit,
    onSelect: (String) -> Unit
) {
    val bgColor = when {
        item.isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        item.isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.surface
    }
    val textColor = when {
        item.isSelected -> MaterialTheme.colorScheme.primary
        item.isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .instantClickable(
                onClick = { onSelect(item.path) },
                onDoubleClick = { onToggleExpand(item.path) }
            )
            .background(bgColor)
            .padding(start = (12 + item.depth * 16).dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.hasChildren) {
            IconButton(
                onClick = { onToggleExpand(item.path) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = if (item.isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    contentDescription = if (item.isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        } else {
            Box(modifier = Modifier.size(24.dp))
        }

        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier
                .size(18.dp)
                .padding(end = 4.dp)
        )

        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
        )

        if (item.isPlaying && !item.isSelected) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Playing",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun buildTreeItems(
    rootPath: String,
    state: FolderTreeState,
    playingFolderPath: String?,
    getSubfolders: (String) -> List<Pair<File, Boolean>>
): List<TreeItem> {
    val items = mutableListOf<TreeItem>()
    val rootFile = File(rootPath)
    val rootHasChildren = getSubfolders(rootPath).isNotEmpty()

    items.add(
        TreeItem(
            path = rootPath,
            name = rootFile.name,
            depth = 0,
            isExpanded = rootPath in state.expandedPaths,
            isSelected = rootPath == state.selectedPath,
            isPlaying = rootPath == playingFolderPath,
            hasChildren = rootHasChildren
        )
    )

    if (rootPath in state.expandedPaths) {
        addChildren(rootPath, 1, state, playingFolderPath, getSubfolders, items)
    }

    return items
}

private fun addChildren(
    parentPath: String,
    depth: Int,
    state: FolderTreeState,
    playingFolderPath: String?,
    getSubfolders: (String) -> List<Pair<File, Boolean>>,
    items: MutableList<TreeItem>
) {
    val subfolders = getSubfolders(parentPath)
    for ((folder, hasChildren) in subfolders) {
        val path = folder.absolutePath
        val isExpanded = path in state.expandedPaths

        items.add(
            TreeItem(
                path = path,
                name = folder.name,
                depth = depth,
                isExpanded = isExpanded,
                isSelected = path == state.selectedPath,
                isPlaying = path == playingFolderPath,
                hasChildren = hasChildren
            )
        )

        if (isExpanded) {
            addChildren(path, depth + 1, state, playingFolderPath, getSubfolders, items)
        }
    }
}
