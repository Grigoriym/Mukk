package com.grappim.mukk.ui.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpOffset
import com.grappim.mukk.data.FileEntry
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@Composable
fun TrackContextDropdownMenu(
    showContextMenu: Boolean,
    entry: FileEntry,
    contextMenuOffset: DpOffset,
    setShowContextMenu: (Boolean) -> Unit,
) {
    DropdownMenu(
        expanded = showContextMenu,
        onDismissRequest = {
            setShowContextMenu(false)
        },
        offset = contextMenuOffset
    ) {
        DropdownMenuItem(
            text = { Text("Copy file path") },
            onClick = {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(StringSelection(entry.file.absolutePath), null)
                setShowContextMenu(false)
            }
        )
        DropdownMenuItem(
            text = { Text("Open file location") },
            onClick = {
                ProcessBuilder("xdg-open", entry.file.parentFile.absolutePath).start()
                setShowContextMenu(false)
            }
        )
    }
}
