package com.baileyble.translocooverlay

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project

/**
 * Action to open the Translation Maintenance Dialog.
 * Accessible from Tools menu or via search.
 */
class TranslocoMaintenanceAction : AnAction("Transloco Maintenance", "Scan and manage translations", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        TranslocoMaintenanceDialog(project).show()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
