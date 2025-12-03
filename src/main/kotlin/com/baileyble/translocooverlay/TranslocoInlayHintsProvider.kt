package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.codeInsight.hints.declarative.*
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * Provides inlay hints showing translation values inline in Angular templates.
 * Uses the declarative API which is more stable in IntelliJ 2023+.
 */
@Suppress("UnstableApiUsage")
class TranslocoInlayHintsProvider : InlayHintsProvider {

    companion object {
        const val PROVIDER_ID = "transloco.inlay.hints"

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
            Regex("""\.get\s*\(\s*['"]"""),
            Regex("""\.controls\s*\[\s*['"]"""),
            Regex("""\.value\s*\.\s*"""),
            Regex("""formControlName\s*=\s*['"]"""),
            Regex("""formGroupName\s*=\s*['"]"""),
            Regex("""formArrayName\s*=\s*['"]"""),
            Regex("""\[formControl]\s*="""),
            Regex("""\[formControlName]\s*="""),
            Regex("""\[formGroup]\s*="""),
            Regex("""\.patchValue\s*\("""),
            Regex("""\.setValue\s*\("""),
            Regex("""\.getRawValue\s*\("""),
            Regex("""\.hasError\s*\(\s*['"]"""),
            Regex("""\.getError\s*\(\s*['"]"""),
            Regex("""routerLink\s*=\s*['"]"""),
            Regex("""\[routerLink]\s*="""),
            Regex("""querySelector\s*\(\s*['"]"""),
            Regex("""getElementById\s*\(\s*['"]"""),
            Regex("""\.navigate\s*\(\s*\["""),
            Regex("""localStorage\.(get|set)Item\s*\(\s*['"]"""),
            Regex("""sessionStorage\.(get|set)Item\s*\(\s*['"]"""),
        )

        fun shouldExclude(context: String): Boolean {
            return EXCLUDE_PATTERNS.any { it.containsMatchIn(context) }
        }
    }

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        if (!file.name.lowercase().endsWith(".html")) return null

        // Only show hints when toggle is ON (controlled by Ctrl+Alt+T)
        val toggleService = TranslocoTranslationToggleService.getInstance(file.project)
        if (!toggleService.showTranslations) return null

        return TranslocoInlayCollector()
    }

    private class TranslocoInlayCollector : SharedBypassCollector {
        private val processedOffsets = mutableSetOf<Int>()
        private val translationCache = mutableMapOf<String, String?>()

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            // Only process leaf elements to avoid duplicates
            if (element.firstChild != null) return

            val project = element.project
            val text = element.text ?: return

            // Check for exclusions
            val immediateContext = getImmediateContext(element)
            if (shouldExclude(immediateContext)) return

            // Find all transloco patterns in this element
            findTranslocoKeys(text, element).forEach { (key, endOffset, scope) ->
                val absoluteOffset = element.textRange.startOffset + endOffset

                // Skip if already processed
                if (processedOffsets.contains(absoluteOffset)) return@forEach
                processedOffsets.add(absoluteOffset)

                val fullKey = if (scope != null) "$scope.$key" else key
                val translation = getCachedTranslation(project, fullKey)

                if (translation != null) {
                    val truncated = if (translation.length > 40) {
                        translation.take(37) + "..."
                    } else translation

                    sink.addPresentation(
                        position = InlineInlayPosition(absoluteOffset, false),
                        payloads = null,
                        tooltip = "Translation: $translation",
                        hasBackground = true
                    ) {
                        text(" = \"$truncated\"")
                    }
                }
            }
        }

        private fun getCachedTranslation(project: Project, key: String): String? {
            return translationCache.getOrPut(key) {
                resolveTranslation(project, key)
            }
        }

        private data class KeyMatch(val key: String, val endOffset: Int, val scope: String?)

        private fun findTranslocoKeys(text: String, element: PsiElement): List<KeyMatch> {
            val results = mutableListOf<KeyMatch>()

            PIPE_PATTERN.findAll(text).forEach { match ->
                val key = match.groupValues[1]
                val endOffset = match.range.last + 1
                results.add(KeyMatch(key, endOffset, null))
            }

            T_FUNCTION_PATTERN.findAll(text).forEach { match ->
                val key = match.groupValues[1]
                val endOffset = match.range.last + 1
                val scope = findScopeInContext(element)
                results.add(KeyMatch(key, endOffset, scope))
            }

            DIRECT_ATTR_PATTERN.findAll(text).forEach { match ->
                val key = match.groupValues[1]
                val endOffset = match.range.last + 1
                results.add(KeyMatch(key, endOffset, null))
            }

            BINDING_ATTR_PATTERN.findAll(text).forEach { match ->
                val key = match.groupValues[1].trim('\'', '"')
                val endOffset = match.range.last + 1
                results.add(KeyMatch(key, endOffset, null))
            }

            return results
        }

        private fun findScopeInContext(element: PsiElement): String? {
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

        private fun resolveTranslation(project: Project, key: String): String? {
            // Try main files first
            val mainFiles = TranslationFileFinder.findTranslationFiles(project)
            for (file in mainFiles) {
                if (file.nameWithoutExtension != "en") continue
                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                val value = JsonKeyNavigator.getStringValue(psiFile, key)
                if (value != null) return value
            }

            for (file in mainFiles) {
                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                val value = JsonKeyNavigator.getStringValue(psiFile, key)
                if (value != null) return value
            }

            // Try scoped resolution
            val keyParts = key.split(".")
            if (keyParts.size >= 2) {
                val potentialScope = keyParts[0]
                val keyWithoutScope = keyParts.drop(1).joinToString(".")

                val scopedFiles = TranslationFileFinder.findScopedTranslationFiles(project, potentialScope)
                for (file in scopedFiles) {
                    if (file.nameWithoutExtension != "en") continue
                    val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                    val value = JsonKeyNavigator.getStringValue(psiFile, keyWithoutScope)
                    if (value != null) return value
                }

                for (file in scopedFiles) {
                    val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                    val value = JsonKeyNavigator.getStringValue(psiFile, keyWithoutScope)
                    if (value != null) return value
                }
            }

            return null
        }

        private fun getImmediateContext(element: PsiElement): String {
            val sb = StringBuilder()
            var current: PsiElement? = element

            repeat(5) {
                current?.text?.let { sb.append(it).append(" ") }
                current = current?.parent
            }

            return sb.toString()
        }
    }
}
