package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * Handles Ctrl+Click navigation for Transloco translation keys.
 *
 * This is called when the user Ctrl+Clicks on any element in the editor.
 * We check if it's a transloco key and navigate to the JSON definition.
 */
class TranslocoGotoDeclarationHandler : GotoDeclarationHandler {

    companion object {
        private val LOG = Logger.getInstance(TranslocoGotoDeclarationHandler::class.java)

        private val PIPE_PATTERN = Regex("""['"]([^'"]+)['"]\s*\|\s*transloco""")
        private val DIRECT_ATTR_PATTERN = Regex("""transloco\s*=\s*["']([^"']+)["']""")
        private val BINDING_ATTR_PATTERN = Regex("""\[transloco]\s*=\s*["']'([^']+)'["']""")
    }

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (sourceElement == null) return null

        val file = sourceElement.containingFile ?: return null
        val fileName = file.name.lowercase()

        // Only handle HTML files
        if (!fileName.endsWith(".html")) {
            return null
        }

        LOG.warn("TRANSLOCO-GOTO: Ctrl+Click detected in ${file.name}")
        LOG.warn("TRANSLOCO-GOTO: Element type: ${sourceElement.javaClass.simpleName}")
        LOG.warn("TRANSLOCO-GOTO: Element text: '${sourceElement.text?.take(50)}'")

        // Get the context around the clicked element
        val context = getTranslocoContext(sourceElement)
        LOG.warn("TRANSLOCO-GOTO: Context: '${context?.take(100)}'")

        if (context == null) {
            return null
        }

        // Extract the key from the context
        val key = extractKeyFromContext(context)
        LOG.warn("TRANSLOCO-GOTO: Extracted key: $key")

        if (key == null) {
            return null
        }

        // Check if the clicked position is on the key
        val elementText = sourceElement.text ?: ""
        if (!elementText.contains(key) && !isClickedOnKey(sourceElement, key)) {
            LOG.warn("TRANSLOCO-GOTO: Click not on key, ignoring")
            return null
        }

        // Find the translation in JSON files
        val targets = findTranslationTargets(sourceElement, key)
        LOG.warn("TRANSLOCO-GOTO: Found ${targets.size} targets")

        return if (targets.isNotEmpty()) targets.toTypedArray() else null
    }

    /**
     * Get the transloco context by looking at parent elements.
     */
    private fun getTranslocoContext(element: PsiElement): String? {
        var current: PsiElement? = element
        var depth = 0

        while (current != null && depth < 10) {
            val text = current.text ?: ""

            // Check if this element contains transloco
            if (text.contains("transloco")) {
                return text
            }

            current = current.parent
            depth++
        }

        return null
    }

    /**
     * Check if the clicked element is part of the key.
     */
    private fun isClickedOnKey(element: PsiElement, key: String): Boolean {
        var current: PsiElement? = element
        var depth = 0

        while (current != null && depth < 5) {
            val text = current.text ?: ""
            if (text.contains(key)) {
                return true
            }
            current = current.parent
            depth++
        }

        return false
    }

    /**
     * Extract the translation key from the context.
     */
    private fun extractKeyFromContext(context: String): String? {
        // Try pipe syntax first: 'key' | transloco
        PIPE_PATTERN.find(context)?.let {
            return it.groupValues[1]
        }

        // Try direct attribute: transloco="key"
        DIRECT_ATTR_PATTERN.find(context)?.let {
            return it.groupValues[1]
        }

        // Try binding: [transloco]="'key'"
        BINDING_ATTR_PATTERN.find(context)?.let {
            return it.groupValues[1]
        }

        return null
    }

    /**
     * Find translation targets in JSON files.
     */
    private fun findTranslationTargets(element: PsiElement, key: String): List<PsiElement> {
        val project = element.project
        val translationFiles = TranslationFileFinder.findTranslationFiles(project)

        LOG.warn("TRANSLOCO-GOTO: Searching in ${translationFiles.size} translation files")
        translationFiles.forEach {
            LOG.warn("TRANSLOCO-GOTO: File: ${it.path}")
        }

        val targets = mutableListOf<PsiElement>()

        for (file in translationFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile
            if (psiFile == null) {
                LOG.warn("TRANSLOCO-GOTO: Could not open ${file.path} as JsonFile")
                continue
            }

            val navResult = JsonKeyNavigator.navigateToKey(psiFile, key)
            LOG.warn("TRANSLOCO-GOTO: Key '$key' in ${file.name}: found=${navResult.found}")

            if (navResult.found && navResult.property != null) {
                targets.add(navResult.property)
            }
        }

        return targets
    }
}
