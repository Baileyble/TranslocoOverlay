package com.baileyble.translocooverlay.util

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

/**
 * Utility to navigate nested JSON structures using dot-notation paths.
 *
 * For example, given key "user.profile.name" and JSON:
 * ```json
 * {
 *   "user": {
 *     "profile": {
 *       "name": "John"
 *     }
 *   }
 * }
 * ```
 * This will navigate to the "name" property and return its value.
 */
object JsonKeyNavigator {

    /**
     * Result of navigating to a JSON key.
     */
    data class NavigationResult(
        val property: JsonProperty?,
        val value: JsonValue?,
        val stringValue: String?,
        val found: Boolean
    ) {
        companion object {
            val NOT_FOUND = NavigationResult(null, null, null, false)
        }
    }

    /**
     * Navigate to a nested JSON value using dot-notation.
     *
     * @param jsonFile The JSON file PSI element
     * @param keyPath The dot-notation path (e.g., "user.profile.name")
     * @return NavigationResult containing the found property/value
     */
    fun navigateToKey(jsonFile: JsonFile, keyPath: String): NavigationResult {
        val rootValue = jsonFile.topLevelValue
        if (rootValue !is JsonObject) {
            return NavigationResult.NOT_FOUND
        }

        return navigateToKey(rootValue, keyPath)
    }

    /**
     * Navigate to a nested JSON value from a JsonObject.
     *
     * @param rootObject The root JSON object
     * @param keyPath The dot-notation path
     * @return NavigationResult containing the found property/value
     */
    fun navigateToKey(rootObject: JsonObject, keyPath: String): NavigationResult {
        val keys = keyPath.split(".")
        var currentObject: JsonObject = rootObject

        for ((index, key) in keys.withIndex()) {
            val property = currentObject.findProperty(key)
                ?: return NavigationResult.NOT_FOUND

            val value = property.value

            // If this is the last key, return the result
            if (index == keys.lastIndex) {
                val stringValue = when (value) {
                    is com.intellij.json.psi.JsonStringLiteral -> value.value
                    else -> value?.text
                }
                return NavigationResult(property, value, stringValue, true)
            }

            // Otherwise, continue navigating
            if (value !is JsonObject) {
                return NavigationResult.NOT_FOUND
            }
            currentObject = value
        }

        return NavigationResult.NOT_FOUND
    }

    /**
     * Navigate to a key in a VirtualFile.
     *
     * @param project The IntelliJ project
     * @param file The JSON virtual file
     * @param keyPath The dot-notation path
     * @return NavigationResult containing the found property/value
     */
    fun navigateToKey(project: Project, file: VirtualFile, keyPath: String): NavigationResult {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile
            ?: return NavigationResult.NOT_FOUND

        return navigateToKey(psiFile, keyPath)
    }

    /**
     * Get all keys from a JSON file with their paths.
     *
     * @param jsonFile The JSON file
     * @param prefix Optional prefix for nested keys
     * @return List of all key paths in the file
     */
    fun getAllKeys(jsonFile: JsonFile, prefix: String = ""): List<String> {
        val rootValue = jsonFile.topLevelValue
        if (rootValue !is JsonObject) {
            return emptyList()
        }

        return getAllKeys(rootValue, prefix)
    }

    /**
     * Get all keys from a JSON object recursively.
     */
    fun getAllKeys(jsonObject: JsonObject, prefix: String = ""): List<String> {
        val result = mutableListOf<String>()

        for (property in jsonObject.propertyList) {
            val name = property.name
            val fullPath = if (prefix.isEmpty()) name else "$prefix.$name"
            val value = property.value

            when (value) {
                is JsonObject -> {
                    // Recurse into nested object
                    result.addAll(getAllKeys(value, fullPath))
                }
                else -> {
                    // Leaf value - add to result
                    result.add(fullPath)
                }
            }
        }

        return result
    }

    /**
     * Check if a key exists in the JSON file.
     */
    fun keyExists(jsonFile: JsonFile, keyPath: String): Boolean {
        return navigateToKey(jsonFile, keyPath).found
    }

    /**
     * Get the string value for a key, or null if not found.
     */
    fun getStringValue(jsonFile: JsonFile, keyPath: String): String? {
        return navigateToKey(jsonFile, keyPath).stringValue
    }
}
