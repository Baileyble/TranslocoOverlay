package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.codeInsight.completion.*
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText
import com.intellij.util.ProcessingContext

/**
 * Provides autocomplete suggestions for Transloco translation keys.
 */
class TranslocoCompletionContributor : CompletionContributor() {

    companion object {
        private val LOG = Logger.getInstance(TranslocoCompletionContributor::class.java)
    }

    init {
        // Register completion for XML attribute values (e.g., transloco="..." or [transloco]="...")
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inside(XmlAttributeValue::class.java),
            TranslocoCompletionProvider()
        )

        // Register completion for XML text content (e.g., {{ '...' | transloco }})
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inside(XmlText::class.java),
            TranslocoCompletionProvider()
        )
    }
}

/**
 * Provider that generates completion suggestions for Transloco keys.
 */
class TranslocoCompletionProvider : CompletionProvider<CompletionParameters>() {

    companion object {
        private val LOG = Logger.getInstance(TranslocoCompletionProvider::class.java)

        // Pattern to detect if we're in a transloco context
        private val TRANSLOCO_CONTEXT_PATTERNS = listOf(
            Regex("""['"][^'"]*$"""),  // Inside quotes that might be followed by | transloco
            Regex("""transloco\s*=\s*["'][^"']*$"""),  // transloco="...
            Regex("""\[transloco]\s*=\s*["']['"]?[^"']*$"""),  // [transloco]="'...
            Regex("""t\s*\(\s*['"][^'"]*$"""),  // t('...
        )
    }

    override fun addCompletions(
        parameters: CompletionParameters,
        context: ProcessingContext,
        result: CompletionResultSet
    ) {
        val position = parameters.position
        val file = parameters.originalFile
        val fileName = file.name.lowercase()

        // Only provide completions in HTML files
        if (!fileName.endsWith(".html")) {
            return
        }

        // Check if we're in a transloco context
        if (!isTranslocoContext(position)) {
            return
        }

        LOG.warn("TRANSLOCO-COMPLETE: Providing completions in ${file.name}")

        val project = parameters.editor.project ?: return

        // Get the current prefix being typed
        val prefix = getCurrentPrefix(position, parameters.offset)
        LOG.warn("TRANSLOCO-COMPLETE: Current prefix: '$prefix'")

        // Find the primary translation file
        val primaryFile = TranslationFileFinder.findPrimaryTranslationFile(project)
        if (primaryFile == null) {
            LOG.warn("TRANSLOCO-COMPLETE: No primary translation file found")
            // Try all translation files
            val allFiles = TranslationFileFinder.findAllTranslationFiles(project)
            if (allFiles.isEmpty()) {
                return
            }
            // Use the first one found
            addCompletionsFromFile(allFiles.first(), project, result, prefix)
            return
        }

        addCompletionsFromFile(primaryFile, project, result, prefix)

        // Also check scoped translation files if prefix suggests a scope
        if (prefix.contains(".")) {
            val potentialScope = prefix.substringBefore(".")
            val scopedFiles = TranslationFileFinder.findScopedTranslationFiles(project, potentialScope)
            for (scopedFile in scopedFiles) {
                addCompletionsFromFile(scopedFile, project, result, prefix, potentialScope)
            }
        }
    }

    private fun addCompletionsFromFile(
        file: com.intellij.openapi.vfs.VirtualFile,
        project: com.intellij.openapi.project.Project,
        result: CompletionResultSet,
        prefix: String,
        scopePrefix: String? = null
    ) {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: return

        val allKeys = JsonKeyNavigator.getAllKeys(psiFile)
        LOG.warn("TRANSLOCO-COMPLETE: Found ${allKeys.size} keys in ${file.name}")

        for (keyPath in allKeys) {
            // If we have a scope prefix, add it to the key
            val fullKey = if (scopePrefix != null) "$scopePrefix.$keyPath" else keyPath

            // Filter by prefix if provided
            if (prefix.isNotEmpty() && !fullKey.lowercase().contains(prefix.lowercase())) {
                continue
            }

            val value = JsonKeyNavigator.getStringValue(psiFile, keyPath)
            val displayValue = value?.take(50)?.let {
                if (value.length > 50) "$it..." else it
            } ?: ""

            val element = LookupElementBuilder.create(fullKey)
                .withIcon(AllIcons.FileTypes.Json)
                .withTypeText(displayValue, true)
                .withTailText(" (${file.nameWithoutExtension})", true)
                .withPresentableText(fullKey)

            result.addElement(element)
        }
    }

    /**
     * Check if the current position is in a transloco context.
     */
    private fun isTranslocoContext(element: PsiElement): Boolean {
        // Walk up the tree to find transloco-related content
        var current: PsiElement? = element
        var depth = 0

        while (current != null && depth < 10) {
            val text = current.text ?: ""

            // Check for transloco patterns
            if (text.contains("transloco") || text.contains("| transloco")) {
                return true
            }

            // Check for t() function in *transloco directive context
            if (text.contains("*transloco")) {
                return true
            }

            current = current.parent
            depth++
        }

        // Also check siblings and nearby context
        val parent = element.parent
        if (parent != null) {
            val parentText = parent.text ?: ""
            if (parentText.contains("transloco")) {
                return true
            }
        }

        return false
    }

    /**
     * Get the current prefix being typed (text before cursor inside quotes).
     */
    private fun getCurrentPrefix(element: PsiElement, offset: Int): String {
        val text = element.text ?: return ""

        // Find the quote character and extract text after it
        val elementStart = element.textRange.startOffset
        val relativeOffset = offset - elementStart

        if (relativeOffset <= 0 || relativeOffset > text.length) {
            return ""
        }

        // Look backwards for opening quote
        val textBeforeCursor = text.substring(0, minOf(relativeOffset, text.length))
        val lastQuote = maxOf(textBeforeCursor.lastIndexOf('\''), textBeforeCursor.lastIndexOf('"'))

        return if (lastQuote >= 0 && lastQuote < textBeforeCursor.length) {
            textBeforeCursor.substring(lastQuote + 1)
                .replace("IntellijIdeaRulezzz", "")  // Remove completion placeholder
                .trim()
        } else {
            ""
        }
    }
}
