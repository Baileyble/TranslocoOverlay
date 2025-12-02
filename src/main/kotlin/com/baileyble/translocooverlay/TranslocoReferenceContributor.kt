package com.baileyble.translocooverlay

import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.*
import com.intellij.psi.xml.XmlAttributeValue
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

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        // Register for XML attribute values (handles most HTML template cases)
        registrar.registerReferenceProvider(
            xmlAttributeValuePattern(),
            TranslocoReferenceProvider()
        )

        // Register for string literals in HTML interpolations
        registrar.registerReferenceProvider(
            xmlTextPattern(),
            TranslocoReferenceProvider()
        )
    }

    /**
     * Pattern for XML attribute values.
     * Matches attributes like: transloco="key" or [transloco]="'key'"
     */
    private fun xmlAttributeValuePattern(): PsiElementPattern.Capture<XmlAttributeValue> {
        return PlatformPatterns.psiElement(XmlAttributeValue::class.java)
    }

    /**
     * Pattern for XML text/interpolation content.
     * Matches content like: {{ 'key' | transloco }}
     */
    private fun xmlTextPattern(): PsiElementPattern.Capture<XmlToken> {
        return PlatformPatterns.psiElement(XmlToken::class.java)
    }
}

/**
 * Reference provider that creates references for Transloco keys.
 */
class TranslocoReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext
    ): Array<PsiReference> {
        val text = element.text ?: return PsiReference.EMPTY_ARRAY
        val file = element.containingFile ?: return PsiReference.EMPTY_ARRAY

        // Only process HTML and TypeScript files
        val fileName = file.name.lowercase()
        if (!fileName.endsWith(".html") && !fileName.endsWith(".ts")) {
            return PsiReference.EMPTY_ARRAY
        }

        val isTypeScript = fileName.endsWith(".ts")
        val matches = TranslocoKeyPattern.findAllKeys(text, isTypeScript)

        if (matches.isEmpty()) {
            return PsiReference.EMPTY_ARRAY
        }

        return matches.map { match ->
            TranslocoKeyReference(
                element = element,
                key = match.key,
                rangeInElement = com.intellij.openapi.util.TextRange(
                    match.startOffset,
                    match.endOffset
                )
            )
        }.toTypedArray()
    }
}
