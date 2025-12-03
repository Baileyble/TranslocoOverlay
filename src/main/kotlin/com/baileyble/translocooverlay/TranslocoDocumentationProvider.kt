package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.codeInsight.documentation.DocumentationManagerProtocol
import com.intellij.json.psi.JsonFile
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager

/**
 * Provides hover documentation for Transloco translation keys.
 * Shows the translation value(s) when hovering over a key in HTML templates.
 */
class TranslocoDocumentationProvider : AbstractDocumentationProvider() {

    companion object {
        private val LOG = Logger.getInstance(TranslocoDocumentationProvider::class.java)

        // Patterns for extracting transloco keys
        // Matches: 'key' | transloco or 'key' | transloco:params or 'key' | transloco:{ obj }
        private val PIPE_PATTERN = Regex("""['"]([^'"]+)['"]\s*\|\s*transloco(?:\s*:\s*(?:\{[^}]*\}|[^}|\s]+))?""")
        private val DIRECT_ATTR_PATTERN = Regex("""(?<!\[)transloco\s*=\s*["']([^"']+)["']""")
        private val BINDING_ATTR_PATTERN = Regex("""\[transloco]\s*=\s*["']['"]?([^"']+)['"]?["']""")
        // Matches: t('key') or t('key', params) or t('key', { obj })
        private val T_FUNCTION_PATTERN = Regex("""t\s*\(\s*['"]([^'"]+)['"](?:\s*,\s*(?:\{[^}]*\}|[^)]+))?\s*\)""")
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

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val targetElement = originalElement ?: element ?: return null

        val file = targetElement.containingFile ?: return null
        val fileName = file.name.lowercase()

        // Only handle HTML files
        if (!fileName.endsWith(".html")) {
            return null
        }

        // Check for exclusions first
        val immediateContext = getImmediateContext(targetElement)
        if (shouldExclude(immediateContext)) {
            return null
        }

        // Try to extract the translation key
        val key = extractTranslocoKey(targetElement) ?: return null

        LOG.warn("TRANSLOCO-DOC: Generating doc for key '$key'")

        // Find translations for this key
        val translations = findTranslations(targetElement, key)

        if (translations.isEmpty()) {
            return null
        }

        // Build HTML documentation
        return buildDocumentation(key, translations)
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val targetElement = originalElement ?: element ?: return null

        val file = targetElement.containingFile ?: return null
        if (!file.name.lowercase().endsWith(".html")) {
            return null
        }

        // Check for exclusions
        val immediateContext = getImmediateContext(targetElement)
        if (shouldExclude(immediateContext)) {
            return null
        }

        val key = extractTranslocoKey(targetElement) ?: return null
        val translations = findTranslations(targetElement, key)

        if (translations.isEmpty()) {
            return null
        }

        // Return a simple single-line preview
        val firstTranslation = translations.entries.firstOrNull()
        return if (firstTranslation != null) {
            "${firstTranslation.key}: \"${firstTranslation.value}\""
        } else null
    }

    /**
     * Get immediate context around the element (for exclusion checks).
     */
    private fun getImmediateContext(element: PsiElement): String {
        val sb = StringBuilder()
        var current: PsiElement? = element

        repeat(5) {
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
     * Extract the translation key from the element context.
     */
    private fun extractTranslocoKey(element: PsiElement): String? {
        // First try t() function pattern
        val tFunctionKey = extractTFunctionKey(element)
        if (tFunctionKey != null) {
            val scope = findTranslocoScope(element)
            return if (scope != null) "$scope.$tFunctionKey" else tFunctionKey
        }

        // Try other patterns by looking at parent context
        val context = getTranslocoContext(element) ?: return null

        // Try t() in context
        T_FUNCTION_PATTERN.find(context)?.let { match ->
            val key = match.groupValues[1]
            val scope = findTranslocoScope(element)
            return if (scope != null) "$scope.$key" else key
        }

        // Try pipe syntax
        PIPE_PATTERN.find(context)?.let {
            return it.groupValues[1]
        }

        // Try binding
        BINDING_ATTR_PATTERN.find(context)?.let {
            return it.groupValues[1].trim('\'', '"')
        }

        // Try direct attribute
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
     * Find translations for the given key across all translation files.
     * Returns a map of language code to translation value.
     */
    private fun findTranslations(element: PsiElement, key: String): Map<String, String> {
        val project = element.project
        val translations = mutableMapOf<String, String>()

        // Try main translation files first
        val mainFiles = TranslationFileFinder.findTranslationFiles(project)
        for (file in mainFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
            val value = JsonKeyNavigator.getStringValue(psiFile, key)
            if (value != null) {
                val lang = file.nameWithoutExtension
                translations[lang] = value
            }
        }

        // If not found, try scoped resolution
        if (translations.isEmpty()) {
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
                        translations[lang] = value
                    }
                }
            }
        }

        // Broader search if still empty
        if (translations.isEmpty()) {
            val allFiles = TranslationFileFinder.findAllTranslationFiles(project)
            for (file in allFiles) {
                if (mainFiles.any { it.path == file.path }) continue
                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                val value = JsonKeyNavigator.getStringValue(psiFile, key)
                if (value != null) {
                    val lang = file.nameWithoutExtension
                    translations[lang] = value
                }
            }
        }

        return translations
    }

    /**
     * Build HTML documentation for the hover popup.
     */
    private fun buildDocumentation(key: String, translations: Map<String, String>): String {
        val sb = StringBuilder()

        sb.append("""
            <html>
            <head>
                <style>
                    body { font-family: sans-serif; margin: 0; padding: 8px; }
                    .key-section { margin-bottom: 12px; }
                    .key-label { color: #808080; font-size: 11px; margin-bottom: 2px; }
                    .key-value { font-family: monospace; color: #6897BB; font-size: 12px; word-break: break-all; }
                    .translations { margin-top: 8px; }
                    .translation-row { display: flex; margin: 4px 0; align-items: baseline; }
                    .lang-code { font-weight: bold; min-width: 30px; color: #808080; }
                    .translation-value { color: #6A8759; margin-left: 8px; }
                    .actions { margin-top: 12px; padding-top: 8px; border-top: 1px solid #404040; }
                    .action-link { color: #589DF6; text-decoration: none; font-size: 11px; }
                </style>
            </head>
            <body>
        """.trimIndent())

        // Key section
        sb.append("""
            <div class="key-section">
                <div class="key-label">Translation Key:</div>
                <div class="key-value">${escapeHtml(key)}</div>
            </div>
        """.trimIndent())

        // Translations
        sb.append("<div class='translations'>")
        for ((lang, value) in translations.entries.sortedBy { it.key }) {
            sb.append("""
                <div class="translation-row">
                    <span class="lang-code">${lang.uppercase()}</span>
                    <span class="translation-value">"${escapeHtml(value)}"</span>
                </div>
            """.trimIndent())
        }
        sb.append("</div>")

        // Actions section with link - using PSI_ELEMENT protocol for navigation
        sb.append("""
            <div class="actions">
                <a href="${DocumentationManagerProtocol.PSI_ELEMENT_PROTOCOL}edit" class="action-link">✏️ Edit</a>
                &nbsp;&nbsp;
                <span style="color: #808080; font-size: 10px;">Jump to Source: F4</span>
            </div>
        """.trimIndent())

        sb.append("</body></html>")

        return sb.toString()
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
