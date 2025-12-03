package com.baileyble.translocooverlay.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Utility to detect TRANSLOCO_SCOPE providers in Angular component files.
 *
 * This detector looks for patterns like:
 * ```typescript
 * {
 *   provide: TRANSLOCO_SCOPE,
 *   useValue: {
 *     scope: 'eligibility',
 *     loader,
 *   },
 * }
 * ```
 *
 * Or simpler patterns:
 * ```typescript
 * { provide: TRANSLOCO_SCOPE, useValue: 'eligibility' }
 * ```
 */
object TranslocoScopeDetector {

    private val LOG = Logger.getInstance(TranslocoScopeDetector::class.java)

    /**
     * Result of scope detection.
     */
    data class ScopeDetectionResult(
        val scope: String?,
        val componentFile: VirtualFile?,
        val found: Boolean
    ) {
        companion object {
            val NOT_FOUND = ScopeDetectionResult(null, null, false)
        }
    }

    // Regex patterns for detecting TRANSLOCO_SCOPE providers

    // Pattern 1: useValue: { scope: 'scopeName' ... }
    private val SCOPE_OBJECT_PATTERN = Regex(
        """provide\s*:\s*TRANSLOCO_SCOPE[\s\S]*?useValue\s*:\s*\{[\s\S]*?scope\s*:\s*['"]([^'"]+)['"]""",
        RegexOption.MULTILINE
    )

    // Pattern 2: useValue: 'scopeName' (simple string)
    private val SCOPE_STRING_PATTERN = Regex(
        """provide\s*:\s*TRANSLOCO_SCOPE[\s\S]*?useValue\s*:\s*['"]([^'"]+)['"]"""
    )

    // Pattern 3: { scope: 'scopeName' } in providers array
    private val PROVIDERS_SCOPE_PATTERN = Regex(
        """TRANSLOCO_SCOPE[\s\S]*?scope\s*:\s*['"]([^'"]+)['"]"""
    )

    /**
     * Detect the TRANSLOCO_SCOPE for an HTML template file.
     *
     * @param project The IntelliJ project
     * @param htmlFile The HTML template file
     * @return ScopeDetectionResult containing the detected scope if found
     */
    fun detectScopeForHtmlFile(project: Project, htmlFile: VirtualFile): ScopeDetectionResult {
        // Find the associated component TypeScript file
        val componentFile = findComponentFileForHtml(project, htmlFile)
        if (componentFile == null) {
            LOG.debug("TRANSLOCO-SCOPE: No component file found for ${htmlFile.name}")
            return ScopeDetectionResult.NOT_FOUND
        }

        return detectScopeInFile(project, componentFile)
    }

    /**
     * Detect the TRANSLOCO_SCOPE for a PsiFile (HTML template).
     */
    fun detectScopeForPsiFile(psiFile: PsiFile): ScopeDetectionResult {
        val virtualFile = psiFile.virtualFile ?: return ScopeDetectionResult.NOT_FOUND
        return detectScopeForHtmlFile(psiFile.project, virtualFile)
    }

    /**
     * Detect TRANSLOCO_SCOPE in a TypeScript component file.
     *
     * @param project The IntelliJ project
     * @param componentFile The component TypeScript file
     * @return ScopeDetectionResult containing the detected scope if found
     */
    fun detectScopeInFile(project: Project, componentFile: VirtualFile): ScopeDetectionResult {
        val content = try {
            String(componentFile.contentsToByteArray())
        } catch (e: Exception) {
            LOG.debug("TRANSLOCO-SCOPE: Failed to read ${componentFile.name}: ${e.message}")
            return ScopeDetectionResult(null, componentFile, false)
        }

        // Check if file contains TRANSLOCO_SCOPE
        if (!content.contains("TRANSLOCO_SCOPE")) {
            LOG.debug("TRANSLOCO-SCOPE: No TRANSLOCO_SCOPE found in ${componentFile.name}")
            return ScopeDetectionResult(null, componentFile, false)
        }

        // Try different patterns to extract the scope
        val scope = extractScope(content)

        return if (scope != null) {
            LOG.debug("TRANSLOCO-SCOPE: Found scope '$scope' in ${componentFile.name}")
            ScopeDetectionResult(scope, componentFile, true)
        } else {
            LOG.debug("TRANSLOCO-SCOPE: TRANSLOCO_SCOPE found but couldn't extract scope value in ${componentFile.name}")
            ScopeDetectionResult(null, componentFile, false)
        }
    }

    /**
     * Extract the scope value from TypeScript content.
     */
    private fun extractScope(content: String): String? {
        // Try Pattern 1: useValue: { scope: 'scopeName' }
        SCOPE_OBJECT_PATTERN.find(content)?.let { match ->
            return match.groupValues[1]
        }

        // Try Pattern 2: useValue: 'scopeName'
        SCOPE_STRING_PATTERN.find(content)?.let { match ->
            return match.groupValues[1]
        }

        // Try Pattern 3: General scope extraction near TRANSLOCO_SCOPE
        PROVIDERS_SCOPE_PATTERN.find(content)?.let { match ->
            return match.groupValues[1]
        }

        return null
    }

    /**
     * Find the component TypeScript file for an HTML template.
     *
     * Angular conventions:
     * - foo.component.html -> foo.component.ts
     * - foo.html (inline template) -> look for templateUrl reference
     */
    fun findComponentFileForHtml(project: Project, htmlFile: VirtualFile): VirtualFile? {
        val htmlName = htmlFile.nameWithoutExtension
        val htmlDir = htmlFile.parent ?: return null

        // Convention 1: foo.component.html -> foo.component.ts
        if (htmlName.endsWith(".component")) {
            val tsFileName = "$htmlName.ts"
            val tsFile = htmlDir.findChild(tsFileName)
            if (tsFile != null) {
                return tsFile
            }
        }

        // Convention 2: Look for any .ts file in the same directory that references this HTML
        val tsFiles = htmlDir.children.filter {
            it.extension == "ts" && !it.name.endsWith(".spec.ts")
        }

        for (tsFile in tsFiles) {
            val content = try {
                String(tsFile.contentsToByteArray())
            } catch (e: Exception) {
                continue
            }

            // Check if this TS file references the HTML file as templateUrl
            if (content.contains("templateUrl") && content.contains(htmlFile.name)) {
                return tsFile
            }
        }

        // Convention 3: Same name different extension (foo.html -> foo.ts)
        val directTsFile = htmlDir.findChild("$htmlName.ts")
        if (directTsFile != null) {
            return directTsFile
        }

        // Convention 4: Look for component.ts in same directory
        val componentTs = htmlDir.children.find {
            it.name.endsWith(".component.ts")
        }
        if (componentTs != null) {
            return componentTs
        }

        return null
    }

    /**
     * Check if a translation file location matches a detected scope.
     *
     * @param locationPath The full path of the translation file location
     * @param scope The detected scope name
     * @return true if the location matches the scope
     */
    fun locationMatchesScope(locationPath: String, scope: String): Boolean {
        val pathLower = locationPath.lowercase()
        val scopeLower = scope.lowercase()

        // Check if the scope name appears in the path
        // Match patterns like:
        // - /eligibility/i18n/
        // - /i18n/eligibility/
        // - /eligibility/assets/i18n/
        // - /libs/eligibility/
        return pathLower.contains("/$scopeLower/") ||
               pathLower.contains("\\$scopeLower\\") ||
               pathLower.endsWith("/$scopeLower") ||
               pathLower.endsWith("\\$scopeLower")
    }

    /**
     * Find translation files that match a specific scope.
     *
     * @param project The IntelliJ project
     * @param scope The scope name to match
     * @return List of VirtualFiles that match the scope
     */
    fun findTranslationFilesForScope(project: Project, scope: String): List<VirtualFile> {
        val allFiles = TranslationFileFinder.findAllTranslationFiles(project)
        return allFiles.filter { file ->
            locationMatchesScope(file.parent?.path ?: "", scope)
        }
    }
}
