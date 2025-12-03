package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*

/**
 * PSI Reference for Transloco translation keys.
 */
class TranslocoKeyReference(
    element: PsiElement,
    private val key: String,
    private val rangeInElement: TextRange
) : PsiReferenceBase<PsiElement>(element, rangeInElement, true), PsiPolyVariantReference {

    companion object {
        private val LOG = Logger.getInstance(TranslocoKeyReference::class.java)
    }

    init {
        LOG.debug("TRANSLOCO-REF: Created reference for key '$key'")
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        LOG.debug("TRANSLOCO-REF: multiResolve() called for key '$key'")

        val project = element.project
        val translationFiles = TranslationFileFinder.findTranslationFiles(project)

        LOG.debug("TRANSLOCO-REF: Found ${translationFiles.size} translation files")
        translationFiles.forEach { file ->
            LOG.debug("TRANSLOCO-REF: Translation file: ${file.path}")
        }

        if (translationFiles.isEmpty()) {
            LOG.debug("TRANSLOCO-REF: NO TRANSLATION FILES FOUND! Check your i18n directory structure.")
            return ResolveResult.EMPTY_ARRAY
        }

        val results = mutableListOf<ResolveResult>()

        for (file in translationFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile
            if (psiFile == null) {
                LOG.debug("TRANSLOCO-REF: Could not open ${file.path} as JsonFile")
                continue
            }

            val navResult = JsonKeyNavigator.navigateToKey(psiFile, key)
            LOG.debug("TRANSLOCO-REF: Key '$key' in ${file.name}: found=${navResult.found}, value=${navResult.stringValue}")

            if (navResult.found && navResult.property != null) {
                results.add(PsiElementResolveResult(navResult.property))
                LOG.debug("TRANSLOCO-REF: Added resolve result for ${file.name}")
            }
        }

        LOG.debug("TRANSLOCO-REF: Resolved to ${results.size} targets")
        return results.toTypedArray()
    }

    override fun resolve(): PsiElement? {
        LOG.debug("TRANSLOCO-REF: resolve() called for key '$key'")
        val results = multiResolve(false)
        val result = results.firstOrNull()?.element
        LOG.debug("TRANSLOCO-REF: resolve() returning: ${result?.javaClass?.simpleName ?: "null"}")
        return result
    }

    override fun getVariants(): Array<Any> {
        LOG.debug("TRANSLOCO-REF: getVariants() called for completion")

        val project = element.project
        val primaryFile = TranslationFileFinder.findPrimaryTranslationFile(project)
        if (primaryFile == null) {
            LOG.debug("TRANSLOCO-REF: No primary translation file found for completion")
            return emptyArray()
        }

        val psiFile = PsiManager.getInstance(project).findFile(primaryFile) as? JsonFile
            ?: return emptyArray()

        val allKeys = JsonKeyNavigator.getAllKeys(psiFile)
        LOG.debug("TRANSLOCO-REF: Found ${allKeys.size} keys for completion")

        return allKeys.map { keyPath ->
            val value = JsonKeyNavigator.getStringValue(psiFile, keyPath)
            TranslocoLookupElement(keyPath, value)
        }.toTypedArray()
    }

    override fun handleElementRename(newElementName: String): PsiElement = element

    override fun getCanonicalText(): String = key

    override fun isReferenceTo(element: PsiElement): Boolean {
        val resolved = multiResolve(false)
        return resolved.any { it.element == element }
    }

    override fun isSoft(): Boolean = true
}

class TranslocoLookupElement(
    private val key: String,
    private val value: String?
) {
    override fun toString(): String = key
    fun getKey(): String = key
    fun getValue(): String? = value
    fun getPresentableText(): String = key
    fun getTailText(): String? = value?.let { " = \"$it\"" }
}
