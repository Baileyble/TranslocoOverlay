package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
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

        // Find all translation locations for this key
        val (existingLocations, availableLocations) = findAllTranslationLocations(project, key)

        // Show dialog on EDT
        ApplicationManager.getApplication().invokeLater({
            val dialog = TranslocoEditDialog(
                project = project,
                translationKey = key,
                existingLocations = existingLocations.toMutableList(),
                availableLocations = availableLocations
            )

            dialog.show()
        }, ModalityState.defaultModalityState())
    }

    /**
     * Find all translation locations for a key across different file locations.
     * Returns a pair of (existing locations where key exists, available locations where key could be added).
     */
    private fun findAllTranslationLocations(
        project: Project,
        key: String
    ): Pair<List<TranslocoEditDialog.TranslationLocation>, List<TranslocoEditDialog.TranslationLocation>> {
        val existingLocations = mutableListOf<TranslocoEditDialog.TranslationLocation>()
        val availableLocations = mutableListOf<TranslocoEditDialog.TranslationLocation>()
        val processedPaths = mutableSetOf<String>()

        LOG.warn("TRANSLOCO-EDIT: Finding all locations for key '$key'")

        // Strategy 1: Try scoped resolution (for Nx monorepo patterns)
        val keyParts = key.split(".")
        if (keyParts.size >= 2) {
            val potentialScope = keyParts[0]
            val keyWithoutScope = keyParts.drop(1).joinToString(".")

            LOG.warn("TRANSLOCO-EDIT: Trying scoped resolution: scope='$potentialScope', key='$keyWithoutScope'")

            val scopedFiles = TranslationFileFinder.findScopedTranslationFiles(project, potentialScope)
            val scopedByPath = scopedFiles.groupBy { it.parent?.path ?: "" }

            for ((path, files) in scopedByPath) {
                if (path.isBlank() || processedPaths.contains(path)) continue
                processedPaths.add(path)

                val translations = mutableMapOf<String, TranslocoEditDialog.TranslationEntry>()
                var foundAny = false

                for (file in files) {
                    val lang = file.nameWithoutExtension
                    val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                    val value = JsonKeyNavigator.getStringValue(psiFile, keyWithoutScope)

                    if (value != null) {
                        translations[lang] = TranslocoEditDialog.TranslationEntry(value, file, true)
                        foundAny = true
                    } else {
                        translations[lang] = TranslocoEditDialog.TranslationEntry("", file, false)
                    }
                }

                if (translations.isNotEmpty()) {
                    val displayPath = getDisplayPath(path, project)
                    val location = TranslocoEditDialog.TranslationLocation(
                        displayPath = displayPath,
                        fullPath = path,
                        keyInFile = keyWithoutScope,
                        translations = translations,
                        isNewKey = !foundAny
                    )

                    if (foundAny) {
                        existingLocations.add(location)
                    } else {
                        availableLocations.add(location)
                    }
                }
            }
        }

        // Strategy 2: Try main translation files with full key
        val mainFiles = TranslationFileFinder.findTranslationFiles(project)
        val mainByPath = mainFiles.groupBy { it.parent?.path ?: "" }

        for ((path, files) in mainByPath) {
            if (path.isBlank() || processedPaths.contains(path)) continue
            processedPaths.add(path)

            val translations = mutableMapOf<String, TranslocoEditDialog.TranslationEntry>()
            var foundAny = false

            for (file in files) {
                val lang = file.nameWithoutExtension
                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                val value = JsonKeyNavigator.getStringValue(psiFile, key)

                if (value != null) {
                    translations[lang] = TranslocoEditDialog.TranslationEntry(value, file, true)
                    foundAny = true
                } else {
                    translations[lang] = TranslocoEditDialog.TranslationEntry("", file, false)
                }
            }

            if (translations.isNotEmpty()) {
                val displayPath = getDisplayPath(path, project)
                val location = TranslocoEditDialog.TranslationLocation(
                    displayPath = displayPath,
                    fullPath = path,
                    keyInFile = key,
                    translations = translations,
                    isNewKey = !foundAny
                )

                if (foundAny) {
                    existingLocations.add(location)
                } else {
                    availableLocations.add(location)
                }
            }
        }

        // Strategy 3: Try all translation files for a broader search
        val allFiles = TranslationFileFinder.findAllTranslationFiles(project)
        val allByPath = allFiles.groupBy { it.parent?.path ?: "" }

        for ((path, files) in allByPath) {
            if (path.isBlank() || processedPaths.contains(path)) continue
            processedPaths.add(path)

            val translations = mutableMapOf<String, TranslocoEditDialog.TranslationEntry>()
            var foundAny = false

            for (file in files) {
                val lang = file.nameWithoutExtension
                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                val value = JsonKeyNavigator.getStringValue(psiFile, key)

                if (value != null) {
                    translations[lang] = TranslocoEditDialog.TranslationEntry(value, file, true)
                    foundAny = true
                } else {
                    translations[lang] = TranslocoEditDialog.TranslationEntry("", file, false)
                }
            }

            if (translations.isNotEmpty()) {
                val displayPath = getDisplayPath(path, project)
                val location = TranslocoEditDialog.TranslationLocation(
                    displayPath = displayPath,
                    fullPath = path,
                    keyInFile = key,
                    translations = translations,
                    isNewKey = !foundAny
                )

                if (foundAny) {
                    existingLocations.add(location)
                } else {
                    availableLocations.add(location)
                }
            }
        }

        // Sort: existing by path, available with last used first
        val sortedExisting = existingLocations.sortedBy { it.displayPath }
        val lastUsed = TranslocoEditDialog.getLastUsedLocation()
        val sortedAvailable = availableLocations.sortedWith(compareBy(
            { it.fullPath != lastUsed },
            { it.displayPath }
        ))

        LOG.warn("TRANSLOCO-EDIT: Found ${sortedExisting.size} existing, ${sortedAvailable.size} available locations for key '$key'")
        return Pair(sortedExisting, sortedAvailable)
    }

    /**
     * Get a shortened display path for tabs.
     */
    private fun getDisplayPath(fullPath: String, project: Project): String {
        val basePath = project.basePath ?: return fullPath

        // Remove project base path
        var display = if (fullPath.startsWith(basePath)) {
            fullPath.removePrefix(basePath).removePrefix("/")
        } else {
            fullPath
        }

        // Shorten common patterns
        display = display
            .replace("libs/", "")
            .replace("apps/", "")
            .replace("/src/assets/i18n", "")
            .replace("/assets/i18n", "")
            .replace("src/", "")

        // If still too long, take last 2-3 path segments
        val parts = display.split("/").filter { it.isNotBlank() }
        if (parts.size > 3) {
            return ".../" + parts.takeLast(2).joinToString("/")
        }

        return display.ifBlank { "main" }
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
