package com.grappim.mukk.ui.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpOffset
import com.grappim.mukk.data.FileEntry
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

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
            text = { Text("Copy file") },
            onClick = {
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(FileTransferable(listOf(entry.file)), null)
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

private class FileTransferable(
    private val files: List<File>
) : Transferable {
    private val uriList = files.joinToString("\r\n") { it.toURI().toString() }

    private val flavors = arrayOf(
        DataFlavor("x-special/gnome-copied-files;class=java.io.InputStream", "GNOME Copied Files"),
        DataFlavor("text/uri-list;class=java.io.InputStream", "URI List"),
        DataFlavor.javaFileListFlavor
    )

    override fun getTransferDataFlavors(): Array<DataFlavor> = flavors

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        flavors.any { it.match(flavor) }

    override fun getTransferData(flavor: DataFlavor): Any = when {
        flavors[0].match(flavor) -> "copy\n$uriList".byteInputStream()
        flavors[1].match(flavor) -> uriList.byteInputStream()
        DataFlavor.javaFileListFlavor.match(flavor) -> files
        else -> throw UnsupportedFlavorException(flavor)
    }
}
