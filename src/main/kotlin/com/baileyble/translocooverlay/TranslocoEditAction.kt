package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.util.IncorrectOperationException

/**
 * Intention action to edit Transloco translation values inline.
 * Triggered via Alt+Enter when cursor is on a translation key.
 */
class TranslocoEditAction : PsiElementBaseIntentionAction(), IntentionAction {

    companion object {
        private val LOG = Logger.getInstance(TranslocoEditAction::class.java)

        private val PIPE_PATTERN = Regex("""['"]([^'"]+)['"]\s*\|\s*transloco""")
        private val DIRECT_ATTR_PATTERN = Regex("""(?<!\[)transloco\s*=\s*["']([^"']+)["']""")
        private val BINDING_ATTR_PATTERN = Regex("""\[transloco]\s*=\s*["']['"]?([^"']+)['"]?["']""")
        private val T_FUNCTION_PATTERN = Regex("""t\s*\(\s*['"]([^'"]+)['"]\s*[,)]""")
        private val STRUCTURAL_DIRECTIVE_PATTERN = Regex("""\*transloco\s*=\s*["']([^"']+)["']""")
        private val READ_SCOPE_PATTERN = Regex("""read\s*:\s*['"]([^'"]+)['"]""")
    }

    override fun getText(): String = "Edit Transloco translation"

    override fun getFamilyName(): String = "Transloco"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val file = element.containingFile ?: return false
        if (!file.name.lowercase().endsWith(".html")) {
            return false
        }

        // Check if we're on a transloco key
        return extractTranslocoKey(element) != null
    }

    @Throws(IncorrectOperationException::class)
    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val key = extractTranslocoKey(element) ?: return

        LOG.warn("TRANSLOCO-EDIT: Editing key '$key'")

        // Find translation files and current values
        val translationData = findTranslationData(project, key)

        if (translationData.isEmpty()) {
            Messages.showWarningDialog(
                project,
                "Could not find translation key '$key' in any translation file.",
                "Translation Not Found"
            )
            return
        }

        // For simplicity, edit the primary (English) translation
        val primaryEntry = translationData.entries.firstOrNull { it.key.lang == "en" }
            ?: translationData.entries.first()

        val currentValue = primaryEntry.key.value ?: ""
        val langDisplay = primaryEntry.key.lang.uppercase()

        // Show input dialog
        val newValue = Messages.showInputDialog(
            project,
            "Edit translation for '$key' ($langDisplay):",
            "Edit Translation",
            Messages.getQuestionIcon(),
            currentValue,
            null
        )

        if (newValue == null || newValue == currentValue) {
            return // Cancelled or no change
        }

        // Write the new value to the JSON file
        WriteCommandAction.runWriteCommandAction(project) {
            updateTranslation(project, primaryEntry.value, primaryEntry.key.keyInFile, newValue)
        }

        LOG.warn("TRANSLOCO-EDIT: Updated '$key' to '$newValue'")
    }

    /**
     * Extract the translation key from the element context.
     */
    private fun extractTranslocoKey(element: PsiElement): String? {
        // First try t() function pattern
        val tFunctionKey = extractTFunctionKey(element)
        if (tFunctionKey != null) {
            val scope = findTranslocoScope(element)
            return if (scope != null) "$scope.$tFunctionKey" else tFunctionKey
        }

        // Try other patterns
        val context = getTranslocoContext(element) ?: return null

        T_FUNCTION_PATTERN.find(context)?.let { match ->
            val key = match.groupValues[1]
            val scope = findTranslocoScope(element)
            return if (scope != null) "$scope.$key" else key
        }

        PIPE_PATTERN.find(context)?.let {
            return it.groupValues[1]
        }

        BINDING_ATTR_PATTERN.find(context)?.let {
            return it.groupValues[1].trim('\'', '"')
        }

        DIRECT_ATTR_PATTERN.find(context)?.let {
            return it.groupValues[1]
        }

        return null
    }

    private fun extractTFunctionKey(element: PsiElement): String? {
        var current: PsiElement? = element
        var depth = 0

        while (current != null && depth < 5) {
            val text = current.text ?: ""
            val match = T_FUNCTION_PATTERN.find(text)
            if (match != null) {
                return match.groupValues[1]
            }
            current = current.parent
            depth++
        }

        return null
    }

    private fun findTranslocoScope(element: PsiElement): String? {
        var current: PsiElement? = element
        var depth = 0

        while (current != null && depth < 20) {
            val text = current.text ?: ""

            if (text.contains("*transloco")) {
                val directiveMatch = STRUCTURAL_DIRECTIVE_PATTERN.find(text)
                if (directiveMatch != null) {
                    val directiveContent = directiveMatch.groupValues[1]
                    val scopeMatch = READ_SCOPE_PATTERN.find(directiveContent)
                    if (scopeMatch != null) {
                        return scopeMatch.groupValues[1]
                    }
                }
                return null
            }

            current = current.parent
            depth++
        }

        return null
    }

    private fun getTranslocoContext(element: PsiElement): String? {
        var current: PsiElement? = element
        var depth = 0

        while (current != null && depth < 15) {
            val text = current.text ?: ""
            if (text.contains("transloco")) {
                return text
            }
            current = current.parent
            depth++
        }

        return null
    }

    /**
     * Data class for translation info.
     */
    data class TranslationInfo(
        val lang: String,
        val value: String?,
        val keyInFile: String  // The actual key path in the JSON file (might differ from full key for scoped files)
    )

    /**
     * Find translation data for the given key.
     * Returns a map of TranslationInfo to the VirtualFile containing it.
     */
    private fun findTranslationData(
        project: Project,
        key: String
    ): Map<TranslationInfo, com.intellij.openapi.vfs.VirtualFile> {
        val result = mutableMapOf<TranslationInfo, com.intellij.openapi.vfs.VirtualFile>()

        // Try main translation files
        val mainFiles = TranslationFileFinder.findTranslationFiles(project)
        for (file in mainFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
            val value = JsonKeyNavigator.getStringValue(psiFile, key)
            if (value != null) {
                val lang = file.nameWithoutExtension
                result[TranslationInfo(lang, value, key)] = file
            }
        }

        // Try scoped resolution
        if (result.isEmpty()) {
            val keyParts = key.split(".")
            if (keyParts.size >= 2) {
                val potentialScope = keyParts[0]
                val keyWithoutScope = keyParts.drop(1).joinToString(".")

                val scopedFiles = TranslationFileFinder.findScopedTranslationFiles(project, potentialScope)
                for (file in scopedFiles) {
                    val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                    val value = JsonKeyNavigator.getStringValue(psiFile, keyWithoutScope)
                    if (value != null) {
                        val lang = file.nameWithoutExtension
                        result[TranslationInfo(lang, value, keyWithoutScope)] = file
                    }
                }
            }
        }

        return result
    }

    /**
     * Update the translation value in the JSON file.
     */
    private fun updateTranslation(
        project: Project,
        file: com.intellij.openapi.vfs.VirtualFile,
        keyPath: String,
        newValue: String
    ) {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: return

        val navResult = JsonKeyNavigator.navigateToKey(psiFile, keyPath)
        if (!navResult.found || navResult.value == null) {
            LOG.warn("TRANSLOCO-EDIT: Could not find key '$keyPath' for update")
            return
        }

        val currentValue = navResult.value
        if (currentValue is JsonStringLiteral) {
            // Create new string literal with escaped value
            val generator = JsonElementGenerator(project)
            val escapedValue = newValue
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")

            val newLiteral = generator.createStringLiteral(escapedValue)
            currentValue.replace(newLiteral)

            LOG.warn("TRANSLOCO-EDIT: Successfully updated value in ${file.name}")
        } else {
            LOG.warn("TRANSLOCO-EDIT: Value is not a string literal, cannot update")
        }
    }
}
