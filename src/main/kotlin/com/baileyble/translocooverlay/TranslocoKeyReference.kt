package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.PsiElementResolveResult

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

    /**
     * Resolve to multiple targets (one per language file).
     */
    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        val project = element.project
        val translationFiles = TranslationFileFinder.findTranslationFiles(project)

        val results = mutableListOf<ResolveResult>()

        for (file in translationFiles) {
            val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile
                ?: continue

            val navResult = JsonKeyNavigator.navigateToKey(psiFile, key)
            if (navResult.found && navResult.property != null) {
                results.add(PsiElementResolveResult(navResult.property))
            }
        }

        return results.toTypedArray()
    }

    /**
     * Resolve to a single target (primary language file).
     */
    override fun resolve(): PsiElement? {
        val results = multiResolve(false)
        return results.firstOrNull()?.element
    }

    /**
     * Get variants for code completion.
     * Returns all available translation keys.
     */
    override fun getVariants(): Array<Any> {
        val project = element.project
        val primaryFile = TranslationFileFinder.findPrimaryTranslationFile(project)
            ?: return emptyArray()

        val psiFile = PsiManager.getInstance(project).findFile(primaryFile) as? JsonFile
            ?: return emptyArray()

        val allKeys = JsonKeyNavigator.getAllKeys(psiFile)

        return allKeys.map { keyPath ->
            val value = JsonKeyNavigator.getStringValue(psiFile, keyPath)
            TranslocoLookupElement(keyPath, value)
        }.toTypedArray()
    }

    /**
     * Handle element rename (refactoring).
     */
    override fun handleElementRename(newElementName: String): PsiElement {
        // The new name should be the last segment of the key path
        // For now, we don't support renaming the full key path
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
