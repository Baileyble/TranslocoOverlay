package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Provides inlay hints showing translation values inline in Angular templates.
 */
@Suppress("UnstableApiUsage")
class TranslocoInlayHintsProvider : InlayHintsProvider<TranslocoInlayHintsProvider.Settings> {

    companion object {
        private val PIPE_PATTERN = Regex("""['"]([^'"]+)['"]\s*\|\s*transloco""")
        private val DIRECT_ATTR_PATTERN = Regex("""(?<!\[)transloco\s*=\s*["']([^"']+)["']""")
        private val BINDING_ATTR_PATTERN = Regex("""\[transloco]\s*=\s*["']['"]?([^"']+)['"]?["']""")
        private val T_FUNCTION_PATTERN = Regex("""t\s*\(\s*['"]([^'"]+)['"]\s*[,)]""")
        private val STRUCTURAL_DIRECTIVE_PATTERN = Regex("""\*transloco\s*=\s*["']([^"']+)["']""")
        private val READ_SCOPE_PATTERN = Regex("""read\s*:\s*['"]([^'"]+)['"]""")
    }

    data class Settings(var enabled: Boolean = false)

    override val key: SettingsKey<Settings> = SettingsKey("transloco.inlay.hints")
    override val name: String = "Transloco Translations"
    override val previewText: String = """{{ 'common.hello' | transloco }}"""

    override fun createSettings(): Settings = Settings()

    override fun createConfigurable(settings: Settings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = JPanel()
        }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: Settings,
        sink: InlayHintsSink
    ): InlayHintsCollector? {
        if (!file.name.lowercase().endsWith(".html")) return null

        // Only show hints when toggle is ON (controlled by Ctrl+Alt+T)
        val toggleService = TranslocoTranslationToggleService.getInstance(file.project)
        if (!toggleService.showTranslations) return null

        return TranslocoInlayHintsCollector(editor, file)
    }

    private class TranslocoInlayHintsCollector(
        private val editor: Editor,
        private val file: PsiFile
    ) : InlayHintsCollector {

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            val project = element.project
            val text = element.text ?: return true

            // Find all transloco patterns in this element
            findTranslocoKeys(text, element).forEach { (key, offset, scope) ->
                val fullKey = if (scope != null) "$scope.$key" else key
                val translation = resolveTranslation(project, fullKey)

                if (translation != null) {
                    val factory = PresentationFactory(editor)
                    val truncated = if (translation.length > 40) {
                        translation.take(37) + "..."
                    } else translation

                    val presentation = factory.roundWithBackground(
                        factory.smallText(" = \"$truncated\"")
                    )

                    val absoluteOffset = element.textRange.startOffset + offset
                    sink.addInlineElement(absoluteOffset, false, presentation, false)
                }
            }

            return true
        }

        private data class KeyMatch(val key: String, val endOffset: Int, val scope: String?)

        private fun findTranslocoKeys(text: String, element: PsiElement): List<KeyMatch> {
            val results = mutableListOf<KeyMatch>()

            // Find pipe syntax: 'key' | transloco
            PIPE_PATTERN.findAll(text).forEach { match ->
                val key = match.groupValues[1]
                val endOffset = match.range.last + 1
                results.add(KeyMatch(key, endOffset, null))
            }

            // Find t() function: t('key')
            T_FUNCTION_PATTERN.findAll(text).forEach { match ->
                val key = match.groupValues[1]
                val endOffset = match.range.last + 1
                val scope = findScopeInContext(element)
                results.add(KeyMatch(key, endOffset, scope))
            }

            // Find direct attribute: transloco="key"
            DIRECT_ATTR_PATTERN.findAll(text).forEach { match ->
                val key = match.groupValues[1]
                val endOffset = match.range.last + 1
                results.add(KeyMatch(key, endOffset, null))
            }

            // Find binding: [transloco]="'key'"
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
                if (file.nameWithoutExtension != "en") continue // Prefer English
                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                val value = JsonKeyNavigator.getStringValue(psiFile, key)
                if (value != null) return value
            }

            // Try any main file
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

                // Try any scoped file
                for (file in scopedFiles) {
                    val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                    val value = JsonKeyNavigator.getStringValue(psiFile, keyWithoutScope)
                    if (value != null) return value
                }
            }

            return null
        }
    }
}
