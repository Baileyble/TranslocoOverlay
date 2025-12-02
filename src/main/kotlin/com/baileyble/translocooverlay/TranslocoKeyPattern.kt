package com.baileyble.translocooverlay

/**
 * Patterns for detecting Transloco translation keys in Angular templates.
 *
 * Supported patterns:
 * - Pipe syntax: {{ 'key.path' | transloco }}
 * - Pipe with params: {{ 'key.path' | transloco:params }}
 * - Attribute binding: [transloco]="'key.path'"
 * - Direct attribute: transloco="key.path"
 * - Structural directive: *transloco="let t; read: 'scope'"
 * - Service usage: this.transloco.translate('key.path')
 */
object TranslocoKeyPattern {

    /**
     * Pattern to match transloco pipe usage.
     * Matches: 'key.path' | transloco or "key.path" | transloco
     * Group 1: The key string (without quotes)
     */
    val PIPE_PATTERN = Regex(
        """['"]([^'"]+)['"]\s*\|\s*transloco(?:\s*:\s*[^}|]+)?"""
    )

    /**
     * Pattern to match [transloco] attribute binding.
     * Matches: [transloco]="'key.path'" or [transloco]='"key.path"'
     * Group 1: The key string (without quotes)
     */
    val ATTRIBUTE_BINDING_PATTERN = Regex(
        """\[transloco]\s*=\s*["']\\?['"]([^'"\\]+)\\?['"]["']"""
    )

    /**
     * Pattern to match direct transloco attribute.
     * Matches: transloco="key.path"
     * Group 1: The key string
     */
    val DIRECT_ATTRIBUTE_PATTERN = Regex(
        """(?<!\[)transloco\s*=\s*["']([^'"]+)["']"""
    )

    /**
     * Pattern to match structural directive with read scope.
     * Matches: *transloco="let t; read: 'scope'"
     * Group 1: The scope name
     */
    val STRUCTURAL_DIRECTIVE_PATTERN = Regex(
        """\*transloco\s*=\s*["'][^"']*read\s*:\s*['"]([^'"]+)['"][^"']*["']"""
    )

    /**
     * Pattern to match service translate calls in TypeScript.
     * Matches: .translate('key.path') or .translate("key.path")
     * Group 1: The key string
     */
    val SERVICE_TRANSLATE_PATTERN = Regex(
        """\.translate[(<]\s*['"]([^'"]+)['"]"""
    )

    /**
     * Pattern to match selectTranslate calls.
     * Matches: .selectTranslate('key.path')
     * Group 1: The key string
     */
    val SERVICE_SELECT_TRANSLATE_PATTERN = Regex(
        """\.selectTranslate[(<]\s*['"]([^'"]+)['"]"""
    )

    /**
     * Pattern to match translateObject calls.
     * Matches: .translateObject('key.path')
     * Group 1: The key string
     */
    val SERVICE_TRANSLATE_OBJECT_PATTERN = Regex(
        """\.translateObject[(<]\s*['"]([^'"]+)['"]"""
    )

    /**
     * Pattern for t() function calls in structural directive context.
     * Matches: t('key.path')
     * Group 1: The key string
     */
    val T_FUNCTION_PATTERN = Regex(
        """t\s*\(\s*['"]([^'"]+)['"]"""
    )

    /**
     * All patterns for HTML template context.
     */
    val HTML_PATTERNS = listOf(
        PIPE_PATTERN,
        ATTRIBUTE_BINDING_PATTERN,
        DIRECT_ATTRIBUTE_PATTERN,
        STRUCTURAL_DIRECTIVE_PATTERN,
        T_FUNCTION_PATTERN
    )

    /**
     * All patterns for TypeScript context.
     */
    val TYPESCRIPT_PATTERNS = listOf(
        SERVICE_TRANSLATE_PATTERN,
        SERVICE_SELECT_TRANSLATE_PATTERN,
        SERVICE_TRANSLATE_OBJECT_PATTERN
    )

    /**
     * All patterns combined.
     */
    val ALL_PATTERNS = HTML_PATTERNS + TYPESCRIPT_PATTERNS

    /**
     * Result of a pattern match.
     */
    data class MatchResult(
        val key: String,
        val startOffset: Int,
        val endOffset: Int,
        val patternType: PatternType
    )

    /**
     * Type of pattern that was matched.
     */
    enum class PatternType {
        PIPE,
        ATTRIBUTE_BINDING,
        DIRECT_ATTRIBUTE,
        STRUCTURAL_DIRECTIVE,
        SERVICE_TRANSLATE,
        T_FUNCTION
    }

    /**
     * Find all transloco keys in a text string.
     *
     * @param text The text to search
     * @param isTypeScript Whether this is TypeScript (vs HTML)
     * @return List of match results
     */
    fun findAllKeys(text: String, isTypeScript: Boolean = false): List<MatchResult> {
        val results = mutableListOf<MatchResult>()
        val patterns = if (isTypeScript) TYPESCRIPT_PATTERNS + T_FUNCTION_PATTERN else HTML_PATTERNS

        for (pattern in patterns) {
            for (match in pattern.findAll(text)) {
                val key = match.groupValues.getOrNull(1) ?: continue
                val keyStart = match.range.first + match.value.indexOf(key)

                results.add(MatchResult(
                    key = key,
                    startOffset = keyStart,
                    endOffset = keyStart + key.length,
                    patternType = getPatternType(pattern)
                ))
            }
        }

        return results.distinctBy { it.key to it.startOffset }
    }

    /**
     * Check if text at a position contains a transloco key.
     *
     * @param text The full text
     * @param offset The cursor offset
     * @return The key at that position, or null
     */
    fun getKeyAtOffset(text: String, offset: Int): String? {
        val allMatches = findAllKeys(text, isTypeScript = text.contains("@Component"))

        return allMatches.find { match ->
            offset >= match.startOffset && offset <= match.endOffset
        }?.key
    }

    /**
     * Extract just the key string from a quoted string.
     * Handles both single and double quotes.
     */
    fun extractKeyFromQuotedString(quotedString: String): String? {
        val trimmed = quotedString.trim()
        if (trimmed.length < 2) return null

        val firstChar = trimmed.first()
        val lastChar = trimmed.last()

        return if ((firstChar == '\'' || firstChar == '"') && firstChar == lastChar) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            null
        }
    }

    /**
     * Validate a translation key format.
     * Valid keys are dot-separated identifiers.
     */
    fun isValidKey(key: String): Boolean {
        if (key.isBlank()) return false

        // Key should be dot-separated segments of valid identifiers
        val segments = key.split(".")
        val identifierPattern = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*$""")

        return segments.all { it.matches(identifierPattern) }
    }

    private fun getPatternType(pattern: Regex): PatternType {
        return when (pattern) {
            PIPE_PATTERN -> PatternType.PIPE
            ATTRIBUTE_BINDING_PATTERN -> PatternType.ATTRIBUTE_BINDING
            DIRECT_ATTRIBUTE_PATTERN -> PatternType.DIRECT_ATTRIBUTE
            STRUCTURAL_DIRECTIVE_PATTERN -> PatternType.STRUCTURAL_DIRECTIVE
            SERVICE_TRANSLATE_PATTERN,
            SERVICE_SELECT_TRANSLATE_PATTERN,
            SERVICE_TRANSLATE_OBJECT_PATTERN -> PatternType.SERVICE_TRANSLATE
            T_FUNCTION_PATTERN -> PatternType.T_FUNCTION
            else -> PatternType.PIPE
        }
    }
}
