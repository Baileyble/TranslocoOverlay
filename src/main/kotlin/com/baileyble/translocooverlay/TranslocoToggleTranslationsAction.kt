package com.baileyble.translocooverlay

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

/**
 * Service to track the toggle state for showing translations inline.
 */
@Service(Service.Level.PROJECT)
class TranslocoTranslationToggleService {
    var showTranslations: Boolean = false

    companion object {
        fun getInstance(project: Project): TranslocoTranslationToggleService {
            return project.service()
        }
    }
}

/**
 * Action to toggle showing translation values inline in Angular templates.
 * Can be triggered from toolbar, menu, or keyboard shortcut.
 */
class TranslocoToggleTranslationsAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(TranslocoToggleTranslationsAction::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.PSI_FILE)

        // Only enable for HTML files
        val isHtml = file?.name?.lowercase()?.endsWith(".html") == true
        e.presentation.isEnabledAndVisible = project != null && isHtml

        if (project != null) {
            val service = TranslocoTranslationToggleService.getInstance(project)
            e.presentation.text = if (service.showTranslations) {
                "Hide Transloco Translations"
            } else {
                "Show Transloco Translations"
            }
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)

        val service = TranslocoTranslationToggleService.getInstance(project)
        service.showTranslations = !service.showTranslations

        LOG.warn("TRANSLOCO-TOGGLE: Translations display set to ${service.showTranslations}")

        // Refresh the editor to update inlay hints
        ApplicationManager.getApplication().invokeLater({
            // Restart daemon analyzer
            DaemonCodeAnalyzer.getInstance(project).restart(psiFile)

            // Also trigger inlay hints refresh by clearing and repainting
            if (editor != null) {
                // Clear existing inlay hints and trigger refresh
                editor.inlayModel.getInlineElementsInRange(0, editor.document.textLength)
                    .filter { it.renderer.toString().contains("=") }
                    .forEach { it.dispose() }

                // Force editor repaint
                editor.component.repaint()
            }

            // If editor wasn't available, try to find it from the file
            val virtualFile = psiFile.virtualFile
            if (virtualFile != null && editor == null) {
                val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                if (document != null) {
                    EditorFactory.getInstance().getEditors(document, project).forEach { ed ->
                        ed.inlayModel.getInlineElementsInRange(0, ed.document.textLength)
                            .filter { it.renderer.toString().contains("=") }
                            .forEach { it.dispose() }
                        ed.component.repaint()
                    }
                }
            }
        }, ModalityState.defaultModalityState())
    }
}
