package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import javax.swing.JList

/**
 * Data class to hold information about a key usage location.
 */
data class KeyUsageInfo(
    val element: PsiElement,
    val fileName: String,
    val lineNumber: Int,
    val contextSnippet: String,
    val virtualFile: VirtualFile?
)

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
            LOG.debug("TRANSLOCO-GOTO: Detected t() function call")
            LOG.debug("TRANSLOCO-GOTO: Key from t(): '$tFunctionKey'")

            // Look for scope from *transloco directive
            val scope = findTranslocoScope(sourceElement)
            val fullKey = if (scope != null) "$scope.$tFunctionKey" else tFunctionKey

            LOG.debug("TRANSLOCO-GOTO: Scope: $scope, Full key: '$fullKey'")

            val targets = findTranslationTargets(sourceElement, fullKey)

            // If no results with scope, try without scope
            if (targets.isEmpty() && scope != null) {
                LOG.debug("TRANSLOCO-GOTO: No results with scope, trying without")
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

        LOG.debug("TRANSLOCO-GOTO: Ctrl+Click in ${file.name}")
        LOG.debug("TRANSLOCO-GOTO: Context: '${context.take(150)}'")

        // Extract the key from the context
        val key = extractKeyFromContext(context, sourceElement)
        LOG.debug("TRANSLOCO-GOTO: Extracted key: $key")

        if (key == null) {
            return null
        }

        // Check if the clicked position is on the key
        if (!isClickedOnKey(sourceElement, key)) {
            LOG.debug("TRANSLOCO-GOTO: Click not on key, ignoring")
            return null
        }

        // Find the translation in JSON files
        val targets = findTranslationTargets(sourceElement, key)
        LOG.debug("TRANSLOCO-GOTO: Found ${targets.size} targets")

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
        LOG.debug("TRANSLOCO-GOTO: Searching for '$key' in ${mainTranslationFiles.size} main files")

        for (file in mainTranslationFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile
                ?: continue

            val navResult = JsonKeyNavigator.navigateToKey(psiFile, key)

            if (navResult.found && navResult.property != null) {
                LOG.debug("TRANSLOCO-GOTO: Found '$key' in ${file.name}")
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

            LOG.debug("TRANSLOCO-GOTO: Trying scoped resolution: scope='$potentialScope', key='$keyWithoutScope'")

            // Find scoped translation files (files in directories matching the scope)
            val scopedFiles = TranslationFileFinder.findScopedTranslationFiles(project, potentialScope)
            LOG.debug("TRANSLOCO-GOTO: Found ${scopedFiles.size} scoped files for '$potentialScope'")

            for (file in scopedFiles) {
                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile
                    ?: continue

                LOG.debug("TRANSLOCO-GOTO: Checking scoped file: ${file.path}")

                val navResult = JsonKeyNavigator.navigateToKey(psiFile, keyWithoutScope)

                if (navResult.found && navResult.property != null) {
                    LOG.debug("TRANSLOCO-GOTO: Found '$keyWithoutScope' in scoped file ${file.name}")
                    targets.add(navResult.property)
                }
            }
        }

        // Strategy 3: Try all translation files with the full key (broader search)
        if (targets.isEmpty()) {
            LOG.debug("TRANSLOCO-GOTO: Trying broader search in all translation files")
            val allFiles = TranslationFileFinder.findAllTranslationFiles(project)

            for (file in allFiles) {
                // Skip files we already checked
                if (mainTranslationFiles.any { it.path == file.path }) continue

                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile
                    ?: continue

                val navResult = JsonKeyNavigator.navigateToKey(psiFile, key)

                if (navResult.found && navResult.property != null) {
                    LOG.debug("TRANSLOCO-GOTO: Found '$key' in ${file.path}")
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
     * Shows a custom popup with clear file context when multiple usages are found.
     * Always returns emptyArray() (not null) for JSON files to prevent IntelliJ's default handler.
     */
    private fun findUsagesInTemplates(element: PsiElement): Array<PsiElement> {
        val jsonProperty = getJsonProperty(element)
        if (jsonProperty == null) {
            LOG.debug("TRANSLOCO-USAGES: Not a JSON property, skipping")
            return emptyArray()
        }
        val project = element.project

        // Build the full key path
        val keyPath = buildKeyPath(jsonProperty)
        if (keyPath.isBlank()) {
            LOG.debug("TRANSLOCO-USAGES: Empty key path, skipping")
            return emptyArray()
        }

        // Determine if this is a scoped file
        val sourceFile = element.containingFile?.virtualFile
        if (sourceFile == null) {
            LOG.debug("TRANSLOCO-USAGES: No source file, skipping")
            return emptyArray()
        }
        val scopePrefix = getScopeFromFilePath(sourceFile.path)

        LOG.debug("TRANSLOCO-USAGES: Looking for usages of key '$keyPath' (scope: $scopePrefix)")

        val usageInfos = mutableListOf<KeyUsageInfo>()

        // Build all possible key variations to search for
        val searchKeys = mutableSetOf<String>()
        searchKeys.add(keyPath)  // e.g., "householdContactInformation.removeSecondPhone"
        if (scopePrefix != null) {
            searchKeys.add("$scopePrefix.$keyPath")  // e.g., "eligibility.householdContactInformation.removeSecondPhone"
        }

        // Search in all HTML files
        val htmlFiles = FilenameIndex.getAllFilesByExt(project, "html", GlobalSearchScope.projectScope(project))

        for (htmlFile in htmlFiles) {
            // Skip JSON files (shouldn't happen but be safe)
            if (htmlFile.path == sourceFile.path) continue

            val psiFile = PsiManager.getInstance(project).findFile(htmlFile) ?: continue
            val text = psiFile.text

            for (searchKey in searchKeys) {
                if (text.contains(searchKey)) {
                    collectUsageInfos(psiFile, searchKey, htmlFile).forEach { info ->
                        usageInfos.add(info)
                    }
                }
            }
        }

        // Also search TypeScript files for service usage
        val tsFiles = FilenameIndex.getAllFilesByExt(project, "ts", GlobalSearchScope.projectScope(project))
        for (tsFile in tsFiles) {
            // Skip the source file
            if (tsFile.path == sourceFile.path) continue

            val psiFile = PsiManager.getInstance(project).findFile(tsFile) ?: continue
            val text = psiFile.text

            for (searchKey in searchKeys) {
                if (text.contains(searchKey)) {
                    collectUsageInfos(psiFile, searchKey, tsFile).forEach { info ->
                        usageInfos.add(info)
                    }
                }
            }
        }

        LOG.debug("TRANSLOCO-USAGES: Found ${usageInfos.size} usages for key '$keyPath'")

        if (usageInfos.isEmpty()) {
            // Show "no usages found" balloon and return empty to prevent default handling
            showNoUsagesMessage(project, keyPath)
            return emptyArray()
        }

        // If only one usage, navigate directly
        if (usageInfos.size == 1) {
            return arrayOf(usageInfos[0].element)
        }

        // Show custom popup with file context
        showUsagesPopup(project, keyPath, usageInfos)

        // Return empty array to indicate we handled this (prevents IntelliJ's default popup)
        return emptyArray()
    }

    /**
     * Show a balloon message when no usages are found.
     */
    private fun showNoUsagesMessage(project: com.intellij.openapi.project.Project, keyPath: String) {
        ApplicationManager.getApplication().invokeLater {
            val statusBar = com.intellij.openapi.wm.WindowManager.getInstance().getStatusBar(project)
            val component = statusBar?.component ?: return@invokeLater
            JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder("No usages found for '$keyPath'", MessageType.INFO, null)
                .setFadeoutTime(3000)
                .createBalloon()
                .show(com.intellij.ui.awt.RelativePoint.getCenterOf(component), Balloon.Position.atRight)
        }
    }

    /**
     * Show a custom popup with clear file context for choosing between usages.
     */
    private fun showUsagesPopup(project: com.intellij.openapi.project.Project, keyPath: String, usages: List<KeyUsageInfo>) {
        ApplicationManager.getApplication().invokeLater {
            val popup = JBPopupFactory.getInstance()
                .createPopupChooserBuilder(usages)
                .setTitle("Usages of '$keyPath'")
                .setRenderer(object : ColoredListCellRenderer<KeyUsageInfo>() {
                    override fun customizeCellRenderer(
                        list: JList<out KeyUsageInfo>,
                        value: KeyUsageInfo?,
                        index: Int,
                        selected: Boolean,
                        hasFocus: Boolean
                    ) {
                        if (value == null) return

                        // Show file name prominently
                        append(value.fileName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)

                        // Show line number
                        append(":${value.lineNumber}", SimpleTextAttributes.GRAYED_ATTRIBUTES)

                        // Show context snippet
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        val snippet = value.contextSnippet.take(60)
                        append(snippet, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        if (value.contextSnippet.length > 60) {
                            append("...", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                })
                .setItemChosenCallback { selected ->
                    // Navigate to the selected usage
                    val virtualFile = selected.virtualFile ?: return@setItemChosenCallback
                    val descriptor = OpenFileDescriptor(
                        project,
                        virtualFile,
                        selected.element.textOffset
                    )
                    FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
                }
                .setNamerForFiltering { it.fileName + " " + it.contextSnippet }
                .createPopup()

            popup.showInFocusCenter()
        }
    }

    /**
     * Collect usage info for occurrences of a key in a file.
     */
    private fun collectUsageInfos(psiFile: com.intellij.psi.PsiFile, key: String, virtualFile: VirtualFile): List<KeyUsageInfo> {
        val infos = mutableListOf<KeyUsageInfo>()
        val text = psiFile.text
        val processedOffsets = mutableSetOf<Int>()

        var index = 0
        while (true) {
            val foundIndex = text.indexOf(key, index)
            if (foundIndex < 0) break

            if (foundIndex !in processedOffsets) {
                processedOffsets.add(foundIndex)

                val element = psiFile.findElementAt(foundIndex)
                if (element != null) {
                    // Calculate line number (1-based)
                    val lineNumber = text.substring(0, foundIndex).count { it == '\n' } + 1

                    // Get context snippet (the line containing the key)
                    val lineStart = text.lastIndexOf('\n', foundIndex).let { if (it < 0) 0 else it + 1 }
                    val lineEnd = text.indexOf('\n', foundIndex).let { if (it < 0) text.length else it }
                    val contextSnippet = text.substring(lineStart, lineEnd).trim()

                    infos.add(KeyUsageInfo(
                        element = element,
                        fileName = virtualFile.name,
                        lineNumber = lineNumber,
                        contextSnippet = contextSnippet,
                        virtualFile = virtualFile
                    ))
                }
            }

            index = foundIndex + 1
        }

        return infos
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
