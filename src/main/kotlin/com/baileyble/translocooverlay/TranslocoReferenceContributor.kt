package com.baileyble.translocooverlay

import com.intellij.openapi.diagnostic.Logger
import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.*
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText
import com.intellij.psi.xml.XmlToken
import com.intellij.util.ProcessingContext

/**
 * Registers reference providers for Transloco translation keys.
 *
 * This contributor registers patterns to detect transloco keys in:
 * - HTML templates (Angular)
 * - TypeScript files (service calls)
 */
class TranslocoReferenceContributor : PsiReferenceContributor() {

    companion object {
        private val LOG = Logger.getInstance(TranslocoReferenceContributor::class.java)
    }

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        LOG.info("TranslocoReferenceContributor: Registering reference providers")

        // Register for XML attribute values (handles most HTML template cases)
        registrar.registerReferenceProvider(
            xmlAttributeValuePattern(),
            TranslocoReferenceProvider()
        )

        // Register for XML text content (for interpolations like {{ 'key' | transloco }})
        registrar.registerReferenceProvider(
            xmlTextPattern(),
            TranslocoReferenceProvider()
        )

        LOG.info("TranslocoReferenceContributor: Reference providers registered successfully")
    }

    private fun xmlAttributeValuePattern(): PsiElementPattern.Capture<XmlAttributeValue> {
        return PlatformPatterns.psiElement(XmlAttributeValue::class.java)
    }

    private fun xmlTextPattern(): PsiElementPattern.Capture<XmlText> {
        return PlatformPatterns.psiElement(XmlText::class.java)
    }
}

/**
 * Reference provider that creates references for Transloco keys.
 */
class TranslocoReferenceProvider : PsiReferenceProvider() {

    companion object {
        private val LOG = Logger.getInstance(TranslocoReferenceProvider::class.java)
    }

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext
    ): Array<PsiReference> {
        val text = element.text ?: return PsiReference.EMPTY_ARRAY
        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY

        // Only process HTML files
        val fileName = file.name.lowercase()
        if (!fileName.endsWith(".html")) {
            return PsiReference.EMPTY_ARRAY
        }

        LOG.info("TranslocoReferenceProvider: Processing element in ${file.name}")
        LOG.info("TranslocoReferenceProvider: Element type: ${element.javaClass.simpleName}")
        LOG.info("TranslocoReferenceProvider: Element text: '$text'")

        // Check if this is a transloco-related element
        val isTranslocoContext = isTranslocoContext(element, text)
        LOG.info("TranslocoReferenceProvider: Is transloco context: $isTranslocoContext")

        if (!isTranslocoContext) {
            return PsiReference.EMPTY_ARRAY
        }

        // Extract the key from the element
        val key = extractTranslocoKey(element, text)
        LOG.info("TranslocoReferenceProvider: Extracted key: $key")

        if (key == null) {
            return PsiReference.EMPTY_ARRAY
        }

        // Calculate the range of the key within the element
        val keyStart = text.indexOf(key)
        if (keyStart < 0) {
            LOG.warn("TranslocoReferenceProvider: Could not find key '$key' in text '$text'")
            return PsiReference.EMPTY_ARRAY
        }

        LOG.info("TranslocoReferenceProvider: Creating reference for key '$key' at offset $keyStart")

        return arrayOf(
            TranslocoKeyReference(
                element = element,
                key = key,
                rangeInElement = com.intellij.openapi.util.TextRange(
                    keyStart,
                    keyStart + key.length
                )
            )
        )
    }

    /**
     * Check if the element is in a transloco context.
     */
    private fun isTranslocoContext(element: PsiElement, text: String): Boolean {
        // Check for pipe syntax: contains | transloco
        if (text.contains("| transloco") || text.contains("|transloco")) {
            LOG.info("TranslocoReferenceProvider: Found pipe syntax")
            return true
        }

        // Check for attribute binding: parent is [transloco] attribute
        if (element is XmlAttributeValue) {
            val parent = element.parent
            if (parent is XmlAttribute) {
                val attrName = parent.name
                LOG.info("TranslocoReferenceProvider: Attribute name: $attrName")
                if (attrName == "transloco" || attrName == "[transloco]") {
                    return true
                }
            }
        }

        // Check for transloco in text content
        if (text.contains("transloco")) {
            LOG.info("TranslocoReferenceProvider: Found transloco in text")
            return true
        }

        return false
    }

    /**
     * Extract the translation key from the element.
     */
    private fun extractTranslocoKey(element: PsiElement, text: String): String? {
        // For attribute values like transloco="key.path"
        if (element is XmlAttributeValue) {
            val parent = element.parent
            if (parent is XmlAttribute) {
                val attrName = parent.name
                if (attrName == "transloco") {
                    // Direct attribute: transloco="key.path"
                    // Remove quotes
                    val value = text.trim().removeSurrounding("\"").removeSurrounding("'")
                    LOG.info("TranslocoReferenceProvider: Direct attribute key: $value")
                    return value.takeIf { it.isNotEmpty() && !it.startsWith("{") }
                }
                if (attrName == "[transloco]") {
                    // Binding: [transloco]="'key.path'"
                    val innerQuotePattern = Regex("""['"]([^'"]+)['"]""")
                    val match = innerQuotePattern.find(text)
                    val key = match?.groupValues?.get(1)
                    LOG.info("TranslocoReferenceProvider: Binding attribute key: $key")
                    return key
                }
            }
        }

        // For pipe syntax: {{ 'key.path' | transloco }}
        val pipePattern = Regex("""['"]([^'"]+)['"]\s*\|\s*transloco""")
        val pipeMatch = pipePattern.find(text)
        if (pipeMatch != null) {
            val key = pipeMatch.groupValues[1]
            LOG.info("TranslocoReferenceProvider: Pipe syntax key: $key")
            return key
        }

        // For t() function: t('key.path')
        val tFunctionPattern = Regex("""t\s*\(\s*['"]([^'"]+)['"]""")
        val tMatch = tFunctionPattern.find(text)
        if (tMatch != null) {
            val key = tMatch.groupValues[1]
            LOG.info("TranslocoReferenceProvider: t() function key: $key")
            return key
        }

        return null
    }
}
