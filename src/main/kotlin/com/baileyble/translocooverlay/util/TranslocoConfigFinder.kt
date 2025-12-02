package com.baileyble.translocooverlay.util

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Utility to find and parse Transloco configuration files.
 *
 * Transloco can be configured via:
 * - transloco.config.ts
 * - transloco.config.js
 *
 * The config typically contains:
 * ```typescript
 * export default {
 *   rootTranslationsPath: 'src/assets/i18n/',
 *   langs: ['en', 'es'],
 *   defaultLang: 'en',
 *   scopedLibs: [...]
 * }
 * ```
 */
object TranslocoConfigFinder {

    private val CONFIG_FILE_NAMES = listOf(
        "transloco.config.ts",
        "transloco.config.js"
    )

    /**
     * Parsed Transloco configuration.
     */
    data class TranslocoConfig(
        val rootTranslationsPath: String?,
        val langs: List<String>,
        val defaultLang: String?,
        val scopes: List<ScopeConfig>,
        val configFile: VirtualFile?
    ) {
        companion object {
            val EMPTY = TranslocoConfig(null, emptyList(), null, emptyList(), null)
        }
    }

    /**
     * Scoped library configuration.
     */
    data class ScopeConfig(
        val scope: String,
        val path: String
    )

    /**
     * Find the Transloco config file in the project.
     *
     * @param project The IntelliJ project
     * @return The config VirtualFile, or null if not found
     */
    fun findConfigFile(project: Project): VirtualFile? {
        val scope = GlobalSearchScope.projectScope(project)

        for (configName in CONFIG_FILE_NAMES) {
            val files = FilenameIndex.getVirtualFilesByName(configName, scope)
            // Return the first found config file (preferring project root)
            val configFile = files.minByOrNull { it.path.length }
            if (configFile != null) {
                return configFile
            }
        }

        return null
    }

    /**
     * Parse the Transloco configuration from the config file.
     *
     * @param project The IntelliJ project
     * @return Parsed TranslocoConfig
     */
    fun parseConfig(project: Project): TranslocoConfig {
        val configFile = findConfigFile(project) ?: return TranslocoConfig.EMPTY

        return try {
            parseConfigFile(configFile)
        } catch (e: Exception) {
            TranslocoConfig.EMPTY.copy(configFile = configFile)
        }
    }

    /**
     * Parse configuration from file content using regex.
     * This is a simple parser that handles common config patterns.
     */
    private fun parseConfigFile(file: VirtualFile): TranslocoConfig {
        val content = String(file.contentsToByteArray())

        // Extract rootTranslationsPath
        val rootPath = extractStringValue(content, "rootTranslationsPath")
            ?: extractStringValue(content, "translocoDir")

        // Extract langs array
        val langs = extractArrayValues(content, "langs")
            ?: extractArrayValues(content, "availableLangs")
            ?: emptyList()

        // Extract default language
        val defaultLang = extractStringValue(content, "defaultLang")
            ?: extractStringValue(content, "defaultLanguage")

        // Extract scopes (simplified - may need more complex parsing)
        val scopes = extractScopes(content)

        return TranslocoConfig(
            rootTranslationsPath = rootPath,
            langs = langs,
            defaultLang = defaultLang,
            scopes = scopes,
            configFile = file
        )
    }

    /**
     * Extract a string value from config content.
     */
    private fun extractStringValue(content: String, key: String): String? {
        // Match patterns like: key: 'value' or key: "value"
        val regex = """$key\s*:\s*['"]([^'"]+)['"]""".toRegex()
        return regex.find(content)?.groupValues?.get(1)
    }

    /**
     * Extract array values from config content.
     */
    private fun extractArrayValues(content: String, key: String): List<String>? {
        // Match patterns like: key: ['en', 'es']
        val regex = """$key\s*:\s*\[([^\]]+)]""".toRegex()
        val match = regex.find(content) ?: return null

        val arrayContent = match.groupValues[1]
        val valueRegex = """['"]([^'"]+)['"]""".toRegex()

        return valueRegex.findAll(arrayContent)
            .map { it.groupValues[1] }
            .toList()
    }

    /**
     * Extract scope configurations.
     */
    private fun extractScopes(content: String): List<ScopeConfig> {
        val scopes = mutableListOf<ScopeConfig>()

        // Match patterns like: { scope: 'admin', path: 'libs/admin/i18n' }
        val regex = """\{\s*scope\s*:\s*['"]([^'"]+)['"]\s*,\s*path\s*:\s*['"]([^'"]+)['"]\s*}""".toRegex()

        for (match in regex.findAll(content)) {
            scopes.add(ScopeConfig(
                scope = match.groupValues[1],
                path = match.groupValues[2]
            ))
        }

        return scopes
    }

    /**
     * Get the translation root path, with fallback to default.
     */
    fun getTranslationRootPath(project: Project): String {
        val config = parseConfig(project)
        return config.rootTranslationsPath ?: "src/assets/i18n"
    }

    /**
     * Get available languages from config or discover from files.
     */
    fun getAvailableLanguages(project: Project): List<String> {
        val config = parseConfig(project)
        if (config.langs.isNotEmpty()) {
            return config.langs
        }

        // Fall back to discovering from files
        return TranslationFileFinder.getAvailableLanguages(project).toList()
    }
}
