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
 */
class TranslocoGotoDeclarationHandler : GotoDeclarationHandler {

    companion object {
        private val LOG = Logger.getInstance(TranslocoGotoDeclarationHandler::class.java)

        // Patterns for extracting transloco keys
        private val PIPE_PATTERN = Regex("""['"]([^'"]+)['"]\s*\|\s*transloco""")
        private val DIRECT_ATTR_PATTERN = Regex("""(?<!\[)transloco\s*=\s*["']([^"']+)["']""")
        private val BINDING_ATTR_PATTERN = Regex("""\[transloco]\s*=\s*["']['"]?([^"']+)['"]?["']""")

        // Patterns to EXCLUDE (form controls, etc.)
        private val EXCLUDE_PATTERNS = listOf(
            Regex("""\.get\s*\(\s*['"]"""),           // .get('something')
            Regex("""\.controls\s*\[\s*['"]"""),      // .controls['something']
            Regex("""\.value\s*\.\s*"""),             // .value.something
            Regex("""formControlName\s*=\s*['"]"""),  // formControlName="something"
            Regex("""formGroupName\s*=\s*['"]"""),    // formGroupName="something"
            Regex("""formArrayName\s*=\s*['"]"""),    // formArrayName="something"
            Regex("""\[formControl]\s*="""),          // [formControl]="something"
            Regex("""\[formGroup]\s*="""),            // [formGroup]="something"
        )
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

        // Get immediate context to check for exclusions
        val immediateContext = getImmediateContext(sourceElement)

        // Check if this is a form control or other excluded pattern
        if (shouldExclude(immediateContext)) {
            LOG.warn("TRANSLOCO-GOTO: Excluded pattern detected, skipping")
            return null
        }

        // Get the transloco context by looking for 'transloco' keyword
        val context = getTranslocoContext(sourceElement)

        if (context == null) {
            return null
        }

        LOG.warn("TRANSLOCO-GOTO: Ctrl+Click in ${file.name}")
        LOG.warn("TRANSLOCO-GOTO: Context: '${context.take(100)}'")

        // Extract the key from the context
        val key = extractKeyFromContext(context)
        LOG.warn("TRANSLOCO-GOTO: Extracted key: $key")

        if (key == null) {
            return null
        }

        // Check if the clicked position is on the key
        if (!isClickedOnKey(sourceElement, key)) {
            LOG.warn("TRANSLOCO-GOTO: Click not on key, ignoring")
            return null
        }

        // Find the translation in JSON files
        val targets = findTranslationTargets(sourceElement, key)
        LOG.warn("TRANSLOCO-GOTO: Found ${targets.size} targets")

        return if (targets.isNotEmpty()) targets.toTypedArray() else null
    }

    /**
     * Get immediate context around the element (for exclusion checks).
     */
    private fun getImmediateContext(element: PsiElement): String {
        val sb = StringBuilder()
        var current: PsiElement? = element

        // Go up 3 levels to get enough context
        repeat(3) {
            current?.text?.let { sb.append(it).append(" ") }
            current = current?.parent
        }

        return sb.toString()
    }

    /**
     * Check if this context should be excluded (form controls, etc.).
     */
    private fun shouldExclude(context: String): Boolean {
        return EXCLUDE_PATTERNS.any { it.containsMatchIn(context) }
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

        // Try binding: [transloco]="'key'" or [transloco]="key"
        BINDING_ATTR_PATTERN.find(context)?.let {
            return it.groupValues[1].trim('\'', '"')
        }

        // Try direct attribute: transloco="key"
        DIRECT_ATTR_PATTERN.find(context)?.let {
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

        val targets = mutableListOf<PsiElement>()

        for (file in translationFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile
            if (psiFile == null) {
                continue
            }

            val navResult = JsonKeyNavigator.navigateToKey(psiFile, key)

            if (navResult.found && navResult.property != null) {
                LOG.warn("TRANSLOCO-GOTO: Found key '$key' in ${file.name}")
                targets.add(navResult.property)
            }
        }

        return targets
    }
}
