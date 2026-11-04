package com.surgemusic.app.settings

import com.surgemusic.app.IsMacOs
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.io.File
import javax.swing.JFileChooser

/**
 * Native directory chooser on desktop, per OS:
 *
 * - macOS: AWT's [FileDialog] plus the `apple.awt.fileDialogForDirectories` property
 *   renders the real Aqua folder picker — Swing's JFileChooser would look foreign.
 * - Windows/Linux: that property is macOS-only and FileDialog hands back a FILE path as
 *   the "folder", so Swing's [JFileChooser] in `DIRECTORIES_ONLY` mode is used instead,
 *   and the result is validated (`isDirectory`) before it is returned — anything else
 *   (cancel, non-directory) yields null.
 *
 * Both dialogs are native-modal: [pick] must be called on the AWT EDT (Compose Desktop's
 * Main dispatcher), where `setVisible(true)` / `showOpenDialog` block while pumping their
 * own events until the user confirms or cancels.
 */
object DirectoryPicker {

    /** Shows the chooser; returns the chosen folder's absolute path, or null when cancelled. */
    fun pick(title: String): String? = if (IsMacOs) pickWithFileDialog(title) else pickWithSwingChooser(title)

    /** macOS Aqua folder picker via FileDialog (`apple.awt.fileDialogForDirectories` is macOS-only). */
    private fun pickWithFileDialog(title: String): String? {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        val parent = Window.getWindows().firstOrNull { it is Frame } as? Frame
        val dialog = FileDialog(parent, title, FileDialog.LOAD)
        try {
            dialog.isVisible = true
            val name = dialog.file ?: return null
            val parentDir = dialog.directory ?: return null
            return File(parentDir, name).absolutePath
        } finally {
            dialog.dispose()
        }
    }

    /** Windows/Linux: FileDialog cannot pick directories — Swing chooser, DIRECTORIES_ONLY. */
    private fun pickWithSwingChooser(title: String): String? {
        val parent = Window.getWindows().firstOrNull { it is Frame }
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
        }
        val result = if (parent != null) chooser.showOpenDialog(parent) else chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) return null
        val dir = chooser.selectedFile ?: return null
        return if (dir.isDirectory) dir.absolutePath else null
    }
}
