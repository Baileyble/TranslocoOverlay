package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
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
     * Edit a translation key. Shows the full dialog with all languages.
     */
    fun editTranslation(project: Project, element: PsiElement) {
        val key = extractTranslocoKey(element)
        if (key == null) {
            LOG.warn("TRANSLOCO-EDIT: No key found at element")
            return
        }

        LOG.warn("TRANSLOCO-EDIT: Opening editor for key '$key'")

        // Find all translation data for this key
        val (translations, keyInFile, isNewKey) = findAllTranslations(project, key)

        // Show dialog on EDT
        ApplicationManager.getApplication().invokeLater({
            val dialog = TranslocoEditDialog(
                project = project,
                translationKey = key,
                keyInFile = keyInFile,
                translations = translations,
                isNewKey = isNewKey
            )

            dialog.show()
        }, ModalityState.defaultModalityState())
    }

    /**
     * Find all translations for a key across all language files.
     * Returns: (translations map, keyInFile, isNewKey)
     */
    private fun findAllTranslations(
        project: Project,
        key: String
    ): Triple<MutableMap<String, TranslocoEditDialog.TranslationEntry>, String, Boolean> {
        val translations = mutableMapOf<String, TranslocoEditDialog.TranslationEntry>()
        var keyInFile = key
        var foundAny = false

        LOG.warn("TRANSLOCO-EDIT: Finding translations for key '$key'")

        // Strategy 1: Try scoped resolution FIRST (for Nx monorepo patterns)
        val keyParts = key.split(".")
        if (keyParts.size >= 2) {
            val potentialScope = keyParts[0]
            val keyWithoutScope = keyParts.drop(1).joinToString(".")

            LOG.warn("TRANSLOCO-EDIT: Trying scoped resolution: scope='$potentialScope', key='$keyWithoutScope'")

            val scopedFiles = TranslationFileFinder.findScopedTranslationFiles(project, potentialScope)
            LOG.warn("TRANSLOCO-EDIT: Found ${scopedFiles.size} scoped files for '$potentialScope'")

            for (file in scopedFiles) {
                val lang = file.nameWithoutExtension
                LOG.warn("TRANSLOCO-EDIT: Checking scoped file: ${file.path}")

                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                val value = JsonKeyNavigator.getStringValue(psiFile, keyWithoutScope)

                LOG.warn("TRANSLOCO-EDIT: Value for '$keyWithoutScope' in $lang: ${value?.take(50) ?: "null"}")

                if (value != null) {
                    translations[lang] = TranslocoEditDialog.TranslationEntry(value, file, true)
                    keyInFile = keyWithoutScope
                    foundAny = true
                } else {
                    translations[lang] = TranslocoEditDialog.TranslationEntry("", file, false)
                    keyInFile = keyWithoutScope
                }
            }
        }

        // Strategy 2: Try main translation files with full key
        if (!foundAny) {
            val mainFiles = TranslationFileFinder.findTranslationFiles(project)
            LOG.warn("TRANSLOCO-EDIT: Trying ${mainFiles.size} main translation files")

            for (file in mainFiles) {
                val lang = file.nameWithoutExtension
                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                val value = JsonKeyNavigator.getStringValue(psiFile, key)

                LOG.warn("TRANSLOCO-EDIT: Value for '$key' in ${file.name}: ${value?.take(50) ?: "null"}")

                if (value != null) {
                    translations[lang] = TranslocoEditDialog.TranslationEntry(value, file, true)
                    foundAny = true
                } else if (!translations.containsKey(lang)) {
                    // Only add empty entry if we don't already have an entry for this lang
                    translations[lang] = TranslocoEditDialog.TranslationEntry("", file, false)
                }
            }
        }

        // Strategy 3: Try all translation files for a broader search
        if (!foundAny) {
            LOG.warn("TRANSLOCO-EDIT: Trying broader search in all translation files")
            val allFiles = TranslationFileFinder.findAllTranslationFiles(project)

            for (file in allFiles) {
                val lang = file.nameWithoutExtension
                if (translations.containsKey(lang) && translations[lang]?.exists == true) continue

                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                val value = JsonKeyNavigator.getStringValue(psiFile, key)

                if (value != null) {
                    translations[lang] = TranslocoEditDialog.TranslationEntry(value, file, true)
                    foundAny = true
                }
            }
        }

        LOG.warn("TRANSLOCO-EDIT: Final result - foundAny=$foundAny, translations=${translations.keys}, keyInFile='$keyInFile'")

        return Triple(translations, keyInFile, !foundAny)
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
}
