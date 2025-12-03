package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Handles Ctrl+Click navigation for Transloco translation keys.
 */
class TranslocoGotoDeclarationHandler : GotoDeclarationHandler {

    companion object {
        private val LOG = Logger.getInstance(TranslocoGotoDeclarationHandler::class.java)

        // Patterns for extracting transloco keys
        // Matches: 'key' | transloco or 'key' | transloco:params or 'key' | transloco:{ obj }
        private val PIPE_PATTERN = Regex("""['"]([^'"]+)['"]\s*\|\s*transloco(?:\s*:\s*(?:\{[^}]*\}|[^}|\s]+))?""")
        private val DIRECT_ATTR_PATTERN = Regex("""(?<!\[)transloco\s*=\s*["']([^"']+)["']""")
        private val BINDING_ATTR_PATTERN = Regex("""\[transloco]\s*=\s*["']['"]?([^"']+)['"]?["']""")

        // Matches: t('key') or t('key', params) or t('key', { obj })
        private val T_FUNCTION_PATTERN = Regex("""t\s*\(\s*['"]([^'"]+)['"](?:\s*,\s*(?:\{[^}]*\}|[^)]+))?\s*\)""")

        // Structural directive patterns
        private val STRUCTURAL_DIRECTIVE_PATTERN = Regex("""\*transloco\s*=\s*["']([^"']+)["']""")
        private val READ_SCOPE_PATTERN = Regex("""read\s*:\s*['"]([^'"]+)['"]""")

        // Patterns to EXCLUDE (form controls, reactive forms, etc.)
        private val EXCLUDE_PATTERNS = listOf(
            Regex("""\.get\s*\(\s*['"]"""),              // .get('something')
            Regex("""\.controls\s*\[\s*['"]"""),         // .controls['something']
            Regex("""\.value\s*\.\s*"""),                // .value.something
            Regex("""formControlName\s*=\s*['"]"""),     // formControlName="something"
            Regex("""formGroupName\s*=\s*['"]"""),       // formGroupName="something"
            Regex("""formArrayName\s*=\s*['"]"""),       // formArrayName="something"
            Regex("""\[formControl]\s*="""),             // [formControl]="something"
            Regex("""\[formControlName]\s*="""),         // [formControlName]="something"
            Regex("""\[formGroup]\s*="""),               // [formGroup]="something"
            Regex("""\.patchValue\s*\("""),              // .patchValue(
            Regex("""\.setValue\s*\("""),                // .setValue(
            Regex("""\.getRawValue\s*\("""),             // .getRawValue()
            Regex("""\.hasError\s*\(\s*['"]"""),         // .hasError('something')
            Regex("""\.getError\s*\(\s*['"]"""),         // .getError('something')
            Regex("""routerLink\s*=\s*['"]"""),          // routerLink="something"
            Regex("""\[routerLink]\s*="""),              // [routerLink]="something"
            Regex("""querySelector\s*\(\s*['"]"""),      // querySelector('something')
            Regex("""getElementById\s*\(\s*['"]"""),     // getElementById('something')
            Regex("""\.navigate\s*\(\s*\["""),           // .navigate([
            Regex("""localStorage\.(get|set)Item\s*\(\s*['"]"""), // localStorage operations
            Regex("""sessionStorage\.(get|set)Item\s*\(\s*['"]"""), // sessionStorage operations
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

        // Handle JSON files - find usages in templates
        if (fileName.endsWith(".json") && isTranslationFile(file.virtualFile)) {
            return findUsagesInTemplates(sourceElement)
        }

        // Only handle HTML files for template -> JSON navigation
        if (!fileName.endsWith(".html")) {
            return null
        }

        // Get immediate context to check for exclusions
        val immediateContext = getImmediateContext(sourceElement)

        // Check if this is a form control or other excluded pattern
        if (shouldExclude(immediateContext)) {
            return null
        }

        // First, try to detect t() function call pattern
        val tFunctionKey = extractTFunctionKey(sourceElement)
        if (tFunctionKey != null) {
            LOG.warn("TRANSLOCO-GOTO: Detected t() function call")
            LOG.warn("TRANSLOCO-GOTO: Key from t(): '$tFunctionKey'")

            // Look for scope from *transloco directive
            val scope = findTranslocoScope(sourceElement)
            val fullKey = if (scope != null) "$scope.$tFunctionKey" else tFunctionKey

            LOG.warn("TRANSLOCO-GOTO: Scope: $scope, Full key: '$fullKey'")

            val targets = findTranslationTargets(sourceElement, fullKey)

            // If no results with scope, try without scope
            if (targets.isEmpty() && scope != null) {
                LOG.warn("TRANSLOCO-GOTO: No results with scope, trying without")
                val targetsNoScope = findTranslationTargets(sourceElement, tFunctionKey)
                if (targetsNoScope.isNotEmpty()) {
                    return targetsNoScope.toTypedArray()
                }
            }

            if (targets.isNotEmpty()) {
                return targets.toTypedArray()
            }
        }

        // Fall back to transloco context detection (pipe, directive attribute)
        val context = getTranslocoContext(sourceElement)

        if (context == null) {
            return null
        }

        LOG.warn("TRANSLOCO-GOTO: Ctrl+Click in ${file.name}")
        LOG.warn("TRANSLOCO-GOTO: Context: '${context.take(150)}'")

        // Extract the key from the context
        val key = extractKeyFromContext(context, sourceElement)
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
     * Extract key from t('key') function call.
     */
    private fun extractTFunctionKey(element: PsiElement): String? {
        var current: PsiElement? = element
        var depth = 0

        while (current != null && depth < 5) {
            val text = current.text ?: ""

            // Check for t('key') pattern
            val match = T_FUNCTION_PATTERN.find(text)
            if (match != null) {
                return match.groupValues[1]
            }

            current = current.parent
            depth++
        }

        return null
    }

    /**
     * Find the scope from *transloco="let t; read: 'scope'" directive.
     */
    private fun findTranslocoScope(element: PsiElement): String? {
        var current: PsiElement? = element
        var depth = 0

        while (current != null && depth < 20) {
            val text = current.text ?: ""

            // Look for *transloco directive
            if (text.contains("*transloco")) {
                val directiveMatch = STRUCTURAL_DIRECTIVE_PATTERN.find(text)
                if (directiveMatch != null) {
                    val directiveContent = directiveMatch.groupValues[1]

                    // Extract read: 'scope' from directive content
                    val scopeMatch = READ_SCOPE_PATTERN.find(directiveContent)
                    if (scopeMatch != null) {
                        return scopeMatch.groupValues[1]
                    }
                }
                // Found *transloco but no read scope
                return null
            }

            current = current.parent
            depth++
        }

        return null
    }

    /**
     * Get immediate context around the element (for exclusion checks).
     */
    private fun getImmediateContext(element: PsiElement): String {
        val sb = StringBuilder()
        var current: PsiElement? = element

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

        while (current != null && depth < 15) {
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
        // For scoped keys, also check just the last part
        val keyParts = key.split(".")
        val lastPart = keyParts.lastOrNull() ?: key

        var current: PsiElement? = element
        var depth = 0

        while (current != null && depth < 5) {
            val text = current.text ?: ""
            if (text.contains(key) || text.contains(lastPart)) {
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
    private fun extractKeyFromContext(context: String, element: PsiElement): String? {
        // Try t() function first
        T_FUNCTION_PATTERN.find(context)?.let { match ->
            val key = match.groupValues[1]
            val scope = findTranslocoScope(element)
            return if (scope != null) "$scope.$key" else key
        }

        // Try pipe syntax: 'key' | transloco
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
     * Tries multiple resolution strategies:
     * 1. Full key in main translation files
     * 2. If key has a scope prefix (e.g., "eligibility.foo.bar"), try scoped files
     */
    private fun findTranslationTargets(element: PsiElement, key: String): List<PsiElement> {
        val project = element.project
        val targets = mutableListOf<PsiElement>()

        // Strategy 1: Try full key in main translation files
        val mainTranslationFiles = TranslationFileFinder.findTranslationFiles(project)
        LOG.warn("TRANSLOCO-GOTO: Searching for '$key' in ${mainTranslationFiles.size} main files")

        for (file in mainTranslationFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile
                ?: continue

            val navResult = JsonKeyNavigator.navigateToKey(psiFile, key)

            if (navResult.found && navResult.property != null) {
                LOG.warn("TRANSLOCO-GOTO: Found '$key' in ${file.name}")
                targets.add(navResult.property)
            }
        }

        // If found in main files, return early
        if (targets.isNotEmpty()) {
            return targets
        }

        // Strategy 2: Try scoped resolution
        // Key like "eligibility.householdContactInformation.addSecondPhone"
        // -> scope = "eligibility", keyWithoutScope = "householdContactInformation.addSecondPhone"
        val keyParts = key.split(".")
        if (keyParts.size >= 2) {
            val potentialScope = keyParts[0]
            val keyWithoutScope = keyParts.drop(1).joinToString(".")

            LOG.warn("TRANSLOCO-GOTO: Trying scoped resolution: scope='$potentialScope', key='$keyWithoutScope'")

            // Find scoped translation files (files in directories matching the scope)
            val scopedFiles = TranslationFileFinder.findScopedTranslationFiles(project, potentialScope)
            LOG.warn("TRANSLOCO-GOTO: Found ${scopedFiles.size} scoped files for '$potentialScope'")

            for (file in scopedFiles) {
                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile
                    ?: continue

                LOG.warn("TRANSLOCO-GOTO: Checking scoped file: ${file.path}")

                val navResult = JsonKeyNavigator.navigateToKey(psiFile, keyWithoutScope)

                if (navResult.found && navResult.property != null) {
                    LOG.warn("TRANSLOCO-GOTO: Found '$keyWithoutScope' in scoped file ${file.name}")
                    targets.add(navResult.property)
                }
            }
        }

        // Strategy 3: Try all translation files with the full key (broader search)
        if (targets.isEmpty()) {
            LOG.warn("TRANSLOCO-GOTO: Trying broader search in all translation files")
            val allFiles = TranslationFileFinder.findAllTranslationFiles(project)

            for (file in allFiles) {
                // Skip files we already checked
                if (mainTranslationFiles.any { it.path == file.path }) continue

                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile
                    ?: continue

                val navResult = JsonKeyNavigator.navigateToKey(psiFile, key)

                if (navResult.found && navResult.property != null) {
                    LOG.warn("TRANSLOCO-GOTO: Found '$key' in ${file.path}")
                    targets.add(navResult.property)
                }
            }
        }

        return targets
    }

    /**
     * Check if the given file is a translation file.
     */
    private fun isTranslationFile(virtualFile: VirtualFile?): Boolean {
        if (virtualFile == null) return false
        val path = virtualFile.path.lowercase()
        return path.contains("i18n") || path.contains("locale") || path.contains("translations")
    }

    /**
     * Find usages of the JSON key in HTML templates.
     */
    private fun findUsagesInTemplates(element: PsiElement): Array<PsiElement>? {
        val jsonProperty = getJsonProperty(element) ?: return null
        val project = element.project

        // Build the full key path
        val keyPath = buildKeyPath(jsonProperty)
        if (keyPath.isBlank()) return null

        // Determine if this is a scoped file
        val file = element.containingFile?.virtualFile ?: return null
        val scopePrefix = getScopeFromFilePath(file.path)

        LOG.warn("TRANSLOCO-USAGES: Looking for usages of key '$keyPath' (scope: $scopePrefix)")

        val usages = mutableListOf<PsiElement>()

        // Build all possible key variations to search for
        val searchKeys = mutableSetOf<String>()
        searchKeys.add(keyPath)  // e.g., "householdContactInformation.removeSecondPhone"
        if (scopePrefix != null) {
            searchKeys.add("$scopePrefix.$keyPath")  // e.g., "eligibility.householdContactInformation.removeSecondPhone"
        }

        // Search in all HTML files
        val htmlFiles = FilenameIndex.getAllFilesByExt(project, "html", GlobalSearchScope.projectScope(project))

        for (htmlFile in htmlFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(htmlFile) ?: continue
            val text = psiFile.text

            for (searchKey in searchKeys) {
                // Check for the key in any string context
                if (text.contains(searchKey)) {
                    findKeyOccurrences(psiFile, searchKey).forEach { occurrence ->
                        usages.add(occurrence)
                    }
                }
            }
        }

        // Also search TypeScript files for service usage
        val tsFiles = FilenameIndex.getAllFilesByExt(project, "ts", GlobalSearchScope.projectScope(project))
        for (tsFile in tsFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(tsFile) ?: continue
            val text = psiFile.text

            for (searchKey in searchKeys) {
                if (text.contains(searchKey)) {
                    findKeyOccurrences(psiFile, searchKey).forEach { occurrence ->
                        usages.add(occurrence)
                    }
                }
            }
        }

        LOG.warn("TRANSLOCO-USAGES: Found ${usages.size} usages for key '$keyPath'")

        return if (usages.isNotEmpty()) usages.toTypedArray() else null
    }

    /**
     * Get the scope prefix from the file path (for scoped translations).
     * Handles paths like: libs/external/eligibility/assets/i18n/en.json -> "eligibility"
     */
    private fun getScopeFromFilePath(path: String): String? {
        val commonFolders = listOf("assets", "src", "app", "libs", "apps", "i18n", "locale", "translations", "external")
        val parts = path.replace("\\", "/").split("/").filter { it.isNotBlank() }

        // Find i18n folder and look backwards for a meaningful scope name
        val i18nIndex = parts.indexOfFirst { it.equals("i18n", ignoreCase = true) }

        if (i18nIndex > 0) {
            // Walk backwards from i18n to find a non-common folder name
            for (i in i18nIndex - 1 downTo 0) {
                val folder = parts[i]
                if (folder.lowercase() !in commonFolders && !folder.contains(".")) {
                    return folder
                }
            }
        }

        return null
    }

    /**
     * Find occurrences of a key in a PSI file.
     */
    private fun findKeyOccurrences(file: com.intellij.psi.PsiFile, key: String): List<PsiElement> {
        val occurrences = mutableListOf<PsiElement>()
        val text = file.text
        val processedOffsets = mutableSetOf<Int>()

        // Find all occurrences of the key (with quotes or as part of larger string)
        var index = 0
        while (true) {
            val foundIndex = text.indexOf(key, index)
            if (foundIndex < 0) break

            // Avoid duplicates
            if (foundIndex !in processedOffsets) {
                processedOffsets.add(foundIndex)

                // Find the PSI element at this offset
                val element = file.findElementAt(foundIndex)
                if (element != null) {
                    occurrences.add(element)
                }
            }

            index = foundIndex + 1
        }

        return occurrences
    }

    /**
     * Build the full key path from a JSON property.
     */
    private fun buildKeyPath(property: JsonProperty): String {
        val parts = mutableListOf<String>()
        var current: PsiElement? = property

        while (current != null) {
            if (current is JsonProperty) {
                parts.add(0, current.name)
            }
            current = current.parent

            // Stop at the file level or JsonFile
            if (current is JsonFile || current == null) break
        }

        return parts.joinToString(".")
    }

    /**
     * Get the JsonProperty from an element (handles clicking on key or value).
     */
    private fun getJsonProperty(element: PsiElement): JsonProperty? {
        var current: PsiElement? = element
        var depth = 0

        while (current != null && depth < 10) {
            if (current is JsonProperty) {
                return current
            }
            current = current.parent
            depth++
        }

        return null
    }
}
