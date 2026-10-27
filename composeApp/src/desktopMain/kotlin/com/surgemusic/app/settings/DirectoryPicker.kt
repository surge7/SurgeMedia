package com.surgemusic.app.settings

import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.io.File

/**
 * Native directory chooser on desktop. On macOS, AWT's [FileDialog] plus the
 * `apple.awt.fileDialogForDirectories` property renders the real Aqua folder picker —
 * Swing's JFileChooser would look foreign. The dialog is native-modal: [pick] must be
 * called on the AWT EDT (Compose Desktop's Main dispatcher), where `setVisible(true)`
 * blocks while pumping its own events until the user confirms or cancels.
 */
object DirectoryPicker {

    /** Shows the chooser; returns the chosen folder's absolute path, or null when cancelled. */
    fun pick(title: String): String? {
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
}
