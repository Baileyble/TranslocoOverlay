package com.baileyble.translocooverlay

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import java.awt.event.MouseEvent

/**
 * Mouse listener that handles Ctrl+Shift+Click to edit translations.
 */
class TranslocoEditMouseListener : EditorMouseListener {

    companion object {
        private val LOG = Logger.getInstance(TranslocoEditMouseListener::class.java)
    }

    override fun mouseClicked(event: EditorMouseEvent) {
        val mouseEvent = event.mouseEvent

        // Check for Ctrl+Shift+Click (or Cmd+Shift+Click on Mac)
        if (!isCtrlShiftClick(mouseEvent)) {
            return
        }

        val editor = event.editor
        val project = editor.project ?: return

        // Get the file
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return

        // Only handle HTML files
        if (!file.name.lowercase().endsWith(".html")) {
            return
        }

        // Get PSI file and element at cursor
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return

        // Check if on a transloco key
        if (!TranslocoEditUtil.isOnTranslocoKey(element)) {
            return
        }

        LOG.warn("TRANSLOCO-EDIT: Ctrl+Shift+Click detected on transloco key")

        // Consume the event to prevent default handling
        event.consume()

        // Edit the translation
        TranslocoEditUtil.editTranslation(project, element)
    }

    private fun isCtrlShiftClick(event: MouseEvent): Boolean {
        // Check for Ctrl+Shift (or Cmd+Shift on Mac)
        val isCtrlOrCmd = event.isControlDown || event.isMetaDown
        return isCtrlOrCmd && event.isShiftDown && event.clickCount == 1
    }
}
