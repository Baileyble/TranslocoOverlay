package com.baileyble.translocooverlay.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Utility to find Transloco translation JSON files in a project.
 *
 * Searches for translation files in common locations:
 * - assets/i18n/{lang}.json
 * - src/assets/i18n/{lang}.json
 * - Custom paths defined in transloco.config.ts
 */
object TranslationFileFinder {

    // Common translation file patterns
    private val DEFAULT_I18N_PATHS = listOf(
        "assets/i18n",
        "src/assets/i18n",
        "public/i18n",
        "src/i18n"
    )

    // Paths to exclude from search
    private val EXCLUDED_PATHS = listOf(
        "node_modules",
        ".git",
        "dist",
        "build",
        ".angular"
    )

    // Common language file names
    private val COMMON_LANG_FILES = listOf(
        "en.json", "es.json", "fr.json", "de.json", "it.json",
        "pt.json", "zh.json", "ja.json", "ko.json", "ru.json"
    )

    /**
     * Find all translation JSON files in the project.
     *
     * @param project The IntelliJ project
     * @return List of VirtualFiles representing translation JSON files
     */
    fun findTranslationFiles(project: Project): List<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)
        val result = mutableListOf<VirtualFile>()

        // Search for common language files
        for (langFile in COMMON_LANG_FILES) {
            val files = FilenameIndex.getVirtualFilesByName(langFile, scope)
            for (file in files) {
                if (isTranslationFile(file)) {
                    result.add(file)
                }
            }
        }

        return result.distinctBy { it.path }
    }

    /**
     * Find translation files for a specific language.
     *
     * @param project The IntelliJ project
     * @param lang The language code (e.g., "en", "es")
     * @return List of VirtualFiles for that language
     */
    fun findTranslationFilesForLanguage(project: Project, lang: String): List<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)
        val fileName = "$lang.json"

        return FilenameIndex.getVirtualFilesByName(fileName, scope)
            .filter { isTranslationFile(it) }
    }

    /**
     * Find the primary/default translation file (usually English).
     *
     * @param project The IntelliJ project
     * @return The primary translation file, or null if not found
     */
    fun findPrimaryTranslationFile(project: Project): VirtualFile? {
        val englishFiles = findTranslationFilesForLanguage(project, "en")
        return englishFiles.firstOrNull()
    }

    /**
     * Check if a file is likely a translation file based on its path.
     */
    private fun isTranslationFile(file: VirtualFile): Boolean {
        val path = file.path.lowercase()

        // Must be a JSON file
        if (file.extension?.lowercase() != "json") {
            return false
        }

        // Exclude files in node_modules, dist, etc.
        if (EXCLUDED_PATHS.any { excluded ->
            path.contains("/$excluded/") || path.contains("\\$excluded\\")
        }) {
            return false
        }

        // Check if in a common i18n directory
        return DEFAULT_I18N_PATHS.any { i18nPath ->
            path.contains("/$i18nPath/") || path.contains("\\$i18nPath\\")
        }
    }

    /**
     * Get all available languages based on found translation files.
     *
     * @param project The IntelliJ project
     * @return Set of language codes found in the project
     */
    fun getAvailableLanguages(project: Project): Set<String> {
        return findTranslationFiles(project)
            .mapNotNull { file ->
                file.nameWithoutExtension.takeIf { it.length in 2..5 }
            }
            .toSet()
    }

    /**
     * Find scoped translation files (e.g., for lazy-loaded modules).
     * Searches for patterns like:
     * - i18n/eligibility/en.json
     * - assets/i18n/eligibility/en.json
     * - eligibility/i18n/en.json
     *
     * @param project The IntelliJ project
     * @param scope The scope name (e.g., "admin", "dashboard", "eligibility")
     * @return List of VirtualFiles for the scope
     */
    fun findScopedTranslationFiles(project: Project, scope: String): List<VirtualFile> {
        val searchScope = GlobalSearchScope.projectScope(project)
        val result = mutableListOf<VirtualFile>()
        val scopeLower = scope.lowercase()

        for (langFile in COMMON_LANG_FILES) {
            val files = FilenameIndex.getVirtualFilesByName(langFile, searchScope)
            for (file in files) {
                val path = file.path.lowercase()

                // Exclude node_modules, etc.
                if (EXCLUDED_PATHS.any { excluded ->
                    path.contains("/$excluded/") || path.contains("\\$excluded\\")
                }) {
                    continue
                }

                // Check if the scope name is in the path
                // Match patterns like: /eligibility/en.json, /i18n/eligibility/en.json
                if (path.contains("/$scopeLower/") || path.contains("\\$scopeLower\\")) {
                    result.add(file)
                }
            }
        }

        return result.distinctBy { it.path }
    }

    /**
     * Find all translation files including those in scope subdirectories.
     * This is a broader search that includes scoped translations.
     *
     * @param project The IntelliJ project
     * @return List of all VirtualFiles that could be translation files
     */
    fun findAllTranslationFiles(project: Project): List<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)
        val result = mutableListOf<VirtualFile>()

        for (langFile in COMMON_LANG_FILES) {
            val files = FilenameIndex.getVirtualFilesByName(langFile, scope)
            for (file in files) {
                val path = file.path.lowercase()

                // Exclude node_modules, dist, etc.
                if (EXCLUDED_PATHS.any { excluded ->
                    path.contains("/$excluded/") || path.contains("\\$excluded\\")
                }) {
                    continue
                }

                // Include if it's in an i18n-related directory or has i18n in path
                val isI18nRelated = DEFAULT_I18N_PATHS.any { i18nPath ->
                    path.contains("/$i18nPath/") || path.contains("\\$i18nPath\\")
                } || path.contains("/i18n/") || path.contains("\\i18n\\")

                if (isI18nRelated) {
                    result.add(file)
                }
            }
        }

        return result.distinctBy { it.path }
    }
}
