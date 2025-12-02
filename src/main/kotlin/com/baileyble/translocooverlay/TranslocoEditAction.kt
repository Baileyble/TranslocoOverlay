package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * Utility class for editing Transloco translation values.
 * Can be invoked from various triggers (Ctrl+Shift+Click, intention action, etc.)
 */
object TranslocoEditUtil {

    private val LOG = Logger.getInstance(TranslocoEditUtil::class.java)

    private val PIPE_PATTERN = Regex("""['"]([^'"]+)['"]\s*\|\s*transloco""")
    private val DIRECT_ATTR_PATTERN = Regex("""(?<!\[)transloco\s*=\s*["']([^"']+)["']""")
    private val BINDING_ATTR_PATTERN = Regex("""\[transloco]\s*=\s*["']['"]?([^"']+)['"]?["']""")
    private val T_FUNCTION_PATTERN = Regex("""t\s*\(\s*['"]([^'"]+)['"]\s*[,)]""")
    private val STRUCTURAL_DIRECTIVE_PATTERN = Regex("""\*transloco\s*=\s*["']([^"']+)["']""")
    private val READ_SCOPE_PATTERN = Regex("""read\s*:\s*['"]([^'"]+)['"]""")

    /**
     * Data class for translation info.
     */
    data class TranslationInfo(
        val lang: String,
        val value: String?,
        val keyInFile: String
    )

    /**
     * Edit a translation key. Shows dialog and updates the JSON file.
     */
    fun editTranslation(project: Project, element: PsiElement) {
        val key = extractTranslocoKey(element)
        if (key == null) {
            LOG.warn("TRANSLOCO-EDIT: No key found at element")
            return
        }

        LOG.warn("TRANSLOCO-EDIT: Editing key '$key'")

        // Find translation files and current values
        val translationData = findTranslationData(project, key)

        if (translationData.isEmpty()) {
            ApplicationManager.getApplication().invokeLater({
                Messages.showWarningDialog(
                    project,
                    "Could not find translation key '$key' in any translation file.",
                    "Translation Not Found"
                )
            }, ModalityState.defaultModalityState())
            return
        }

        // For simplicity, edit the primary (English) translation
        val primaryEntry = translationData.entries.firstOrNull { it.key.lang == "en" }
            ?: translationData.entries.first()

        val currentValue = primaryEntry.key.value ?: ""
        val langDisplay = primaryEntry.key.lang.uppercase()
        val file = primaryEntry.value
        val keyInFile = primaryEntry.key.keyInFile

        // Show input dialog on EDT with proper modality
        ApplicationManager.getApplication().invokeLater({
            val newValue = Messages.showInputDialog(
                project,
                "Edit translation for '$key' ($langDisplay):",
                "Edit Translation",
                Messages.getQuestionIcon(),
                currentValue,
                null
            )

            if (newValue != null && newValue != currentValue) {
                // Write the new value in a write action
                WriteCommandAction.runWriteCommandAction(project, "Edit Transloco Translation", null, {
                    updateTranslation(project, file, keyInFile, newValue)
                })
                LOG.warn("TRANSLOCO-EDIT: Updated '$key' to '$newValue'")
            }
        }, ModalityState.defaultModalityState())
    }

    /**
     * Check if element is on a transloco key.
     */
    fun isOnTranslocoKey(element: PsiElement): Boolean {
        return extractTranslocoKey(element) != null
    }

    /**
     * Extract the translation key from the element context.
     */
    fun extractTranslocoKey(element: PsiElement): String? {
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
     * Find translation data for the given key.
     */
    private fun findTranslationData(
        project: Project,
        key: String
    ): Map<TranslationInfo, VirtualFile> {
        val result = mutableMapOf<TranslationInfo, VirtualFile>()

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
        file: VirtualFile,
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
            val generator = JsonElementGenerator(project)
            val newLiteral = generator.createStringLiteral(newValue)
            currentValue.replace(newLiteral)
            LOG.warn("TRANSLOCO-EDIT: Successfully updated value in ${file.name}")
        } else {
            LOG.warn("TRANSLOCO-EDIT: Value is not a string literal, cannot update")
        }
    }
}
