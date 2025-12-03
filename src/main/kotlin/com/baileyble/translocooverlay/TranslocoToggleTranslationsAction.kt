package com.baileyble.translocooverlay

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsPassFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
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

        val service = TranslocoTranslationToggleService.getInstance(project)
        service.showTranslations = !service.showTranslations

        LOG.debug("TRANSLOCO-TOGGLE: Translations display set to ${service.showTranslations}")

        // Refresh the editor to update inlay hints
        ApplicationManager.getApplication().invokeLater({
            // Restart daemon analyzer to trigger re-collection of hints
            DaemonCodeAnalyzer.getInstance(project).restart(psiFile)

            // Also try to restart declarative inlay hints specifically
            try {
                @Suppress("UnstableApiUsage")
                DeclarativeInlayHintsPassFactory.Companion.resetModificationStamp()
            } catch (ex: Exception) {
                LOG.debug("Could not reset declarative hints: ${ex.message}")
            }

            // Force repaint of all editors showing this file
            val virtualFile = psiFile.virtualFile
            if (virtualFile != null) {
                FileEditorManager.getInstance(project).getAllEditors(virtualFile).forEach { editor ->
                    (editor as? com.intellij.openapi.fileEditor.TextEditor)?.editor?.component?.repaint()
                }
            }
        }, ModalityState.defaultModalityState())
    }
}
