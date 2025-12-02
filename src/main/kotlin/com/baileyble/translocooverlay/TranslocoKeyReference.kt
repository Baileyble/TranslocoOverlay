package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*

/**
 * PSI Reference for Transloco translation keys.
 *
 * This reference links a translation key usage in a template
 * to its definition in a JSON translation file.
 *
 * Provides:
 * - Ctrl+Click navigation to JSON definition
 * - Rename refactoring support
 * - Find usages support
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
        LOG.info("TranslocoKeyReference: Created reference for key '$key' in element ${element.javaClass.simpleName}")
    }

    /**
     * Resolve to multiple targets (one per language file).
     */
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        LOG.info("TranslocoKeyReference: multiResolve called for key '$key'")

        val project = element.project
        val translationFiles = TranslationFileFinder.findTranslationFiles(project)

        LOG.info("TranslocoKeyReference: Found ${translationFiles.size} translation files")
        translationFiles.forEach { file ->
            LOG.info("TranslocoKeyReference: Translation file: ${file.path}")
        }

        val results = mutableListOf<ResolveResult>()

        for (file in translationFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile
            if (psiFile == null) {
                LOG.warn("TranslocoKeyReference: Could not open ${file.path} as JsonFile")
                continue
            }

            val navResult = JsonKeyNavigator.navigateToKey(psiFile, key)
            LOG.info("TranslocoKeyReference: Navigation result for '$key' in ${file.name}: found=${navResult.found}, value=${navResult.stringValue}")

            if (navResult.found && navResult.property != null) {
                results.add(PsiElementResolveResult(navResult.property))
            }
        }

        LOG.info("TranslocoKeyReference: Resolved to ${results.size} targets")
        return results.toTypedArray()
    }

    /**
     * Resolve to a single target (primary language file).
     */
    override fun resolve(): PsiElement? {
        LOG.info("TranslocoKeyReference: resolve() called for key '$key'")
        val results = multiResolve(false)
        val result = results.firstOrNull()?.element
        LOG.info("TranslocoKeyReference: resolve() returning: ${result?.javaClass?.simpleName ?: "null"}")
        return result
    }

    /**
     * Get variants for code completion.
     * Returns all available translation keys.
     */
    override fun getVariants(): Array<Any> {
        LOG.info("TranslocoKeyReference: getVariants() called")

        val project = element.project
        val primaryFile = TranslationFileFinder.findPrimaryTranslationFile(project)
        if (primaryFile == null) {
            LOG.warn("TranslocoKeyReference: No primary translation file found")
            return emptyArray()
        }

        LOG.info("TranslocoKeyReference: Primary translation file: ${primaryFile.path}")

        val psiFile = PsiManager.getInstance(project).findFile(primaryFile) as? JsonFile
        if (psiFile == null) {
            LOG.warn("TranslocoKeyReference: Could not open primary file as JsonFile")
            return emptyArray()
        }

        val allKeys = JsonKeyNavigator.getAllKeys(psiFile)
        LOG.info("TranslocoKeyReference: Found ${allKeys.size} keys for completion")

        return allKeys.map { keyPath ->
            val value = JsonKeyNavigator.getStringValue(psiFile, keyPath)
            TranslocoLookupElement(keyPath, value)
        }.toTypedArray()
    }

    /**
     * Handle element rename (refactoring).
     */
    override fun handleElementRename(newElementName: String): PsiElement {
        LOG.info("TranslocoKeyReference: handleElementRename called with '$newElementName'")
        return element
    }

    /**
     * Get the canonical text (the key itself).
     */
    override fun getCanonicalText(): String = key

    /**
     * Check if this reference resolves to the given element.
     */
    override fun isReferenceTo(element: PsiElement): Boolean {
        val resolved = multiResolve(false)
        return resolved.any { it.element == element }
    }

    /**
     * Check if the reference is soft (doesn't show error if unresolved).
     */
    override fun isSoft(): Boolean = true
}

/**
 * Lookup element for code completion.
 */
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
