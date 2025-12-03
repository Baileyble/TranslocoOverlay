package com.baileyble.translocooverlay

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import java.awt.event.MouseEvent

/**
 * Mouse listener that handles Ctrl+Shift+Click:
 * - In HTML files: Opens the translation editor dialog
 * - In JSON translation files: Finds usages in templates
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
        val fileName = file.name.lowercase()

        // Get PSI file and element at cursor
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
        val offset = editor.caretModel.offset
        val element = psiFile.findElementAt(offset) ?: return

        when {
            // Handle HTML files - open translation editor
            fileName.endsWith(".html") -> {
                if (!TranslocoEditUtil.isOnTranslocoKey(element)) {
                    return
                }
                LOG.debug("TRANSLOCO-EDIT: Ctrl+Shift+Click detected on transloco key in HTML")
                event.consume()
                TranslocoEditUtil.editTranslation(project, element)
            }

            // Handle JSON translation files - find usages in templates
            fileName.endsWith(".json") && isTranslationFile(file.path) -> {
                LOG.debug("TRANSLOCO-EDIT: Ctrl+Shift+Click detected in JSON translation file")
                event.consume()
                TranslocoJsonUsagesFinder.findUsages(project, element)
            }
        }
    }

    private fun isCtrlShiftClick(event: MouseEvent): Boolean {
        // Check for Ctrl+Shift (or Cmd+Shift on Mac)
        val isCtrlOrCmd = event.isControlDown || event.isMetaDown
        return isCtrlOrCmd && event.isShiftDown && event.clickCount == 1
    }

    private fun isTranslationFile(path: String): Boolean {
        val lowerPath = path.lowercase()
        return lowerPath.contains("i18n") || lowerPath.contains("locale") || lowerPath.contains("translations")
    }
}
