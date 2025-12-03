package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.swing.*

/**
 * Dialog for editing Transloco translations across all languages.
 * Features:
 * - View/edit all language translations for a key
 * - Create new keys if they don't exist
 * - Google Translate integration for auto-translation
 */
class TranslocoEditDialog(
    private val project: Project,
    private val translationKey: String,
    private val keyInFile: String,  // The actual key path in JSON (might differ for scoped)
    private val translations: MutableMap<String, TranslationEntry>,
    private val isNewKey: Boolean = false
) : DialogWrapper(project, true) {

    companion object {
        private val LOG = Logger.getInstance(TranslocoEditDialog::class.java)

        // Common languages to show
        private val COMMON_LANGUAGES = listOf("en", "es", "fr", "de", "it", "pt", "zh", "ja", "ko", "ru")

        // Language display names
        private val LANGUAGE_NAMES = mapOf(
            "en" to "English",
            "es" to "Spanish",
            "fr" to "French",
            "de" to "German",
            "it" to "Italian",
            "pt" to "Portuguese",
            "zh" to "Chinese",
            "ja" to "Japanese",
            "ko" to "Korean",
            "ru" to "Russian",
            "ar" to "Arabic",
            "hi" to "Hindi",
            "nl" to "Dutch",
            "pl" to "Polish",
            "tr" to "Turkish",
            "vi" to "Vietnamese"
        )
    }

    data class TranslationEntry(
        var value: String,
        val file: VirtualFile?,
        val exists: Boolean
    )

    private val textFields = mutableMapOf<String, JBTextField>()
    private val translateButtons = mutableMapOf<String, JButton>()

    init {
        title = if (isNewKey) "Create Translation: $translationKey" else "Edit Translation: $translationKey"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, JBUI.scale(12)))
        mainPanel.preferredSize = Dimension(JBUI.scale(650), JBUI.scale(450))
        mainPanel.border = JBUI.Borders.empty(8)

        // Header with key info
        val headerPanel = JPanel(GridBagLayout())
        headerPanel.border = JBUI.Borders.emptyBottom(12)
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }

        // Key label with monospace font for the key itself
        val keyPrefix = JBLabel("Key: ")
        keyPrefix.font = keyPrefix.font.deriveFont(Font.BOLD)

        val keyValue = JBLabel(translationKey)
        keyValue.font = Font(Font.MONOSPACED, Font.PLAIN, keyValue.font.size)
        keyValue.foreground = JBColor(Color(0, 102, 153), Color(102, 178, 255))

        val keyPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        keyPanel.add(keyPrefix)
        keyPanel.add(keyValue)
        headerPanel.add(keyPanel, gbc)

        if (isNewKey) {
            gbc.gridy = 1
            gbc.insets = JBUI.insets(4, 0, 0, 0)
            val infoLabel = JBLabel("This key doesn't exist yet. Fill in values to create it.")
            infoLabel.foreground = JBColor(Color(80, 140, 80), Color(120, 180, 120))
            infoLabel.font = infoLabel.font.deriveFont(Font.ITALIC)
            headerPanel.add(infoLabel, gbc)
        }

        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // Translations panel with scroll
        val translationsPanel = JPanel(GridBagLayout())
        translationsPanel.border = JBUI.Borders.empty(8)
        val rowGbc = GridBagConstraints()
        var row = 0

        // Add English first (source for translations)
        addLanguageRow(translationsPanel, "en", true, rowGbc, row++)

        // Add separator with label
        rowGbc.apply {
            gridx = 0
            gridy = row++
            gridwidth = 3
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(12, 0, 8, 0)
            weightx = 1.0
        }
        val separatorPanel = JPanel(BorderLayout())
        separatorPanel.add(JSeparator(), BorderLayout.CENTER)
        translationsPanel.add(separatorPanel, rowGbc)

        // Add "Translate All" button row
        rowGbc.apply {
            gridy = row++
            gridwidth = 3
            insets = JBUI.insets(0, 0, 12, 0)
        }
        val translateAllButton = JButton("Translate All from English")
        translateAllButton.toolTipText = "Auto-translate empty fields using Google Translate"
        translateAllButton.addActionListener { translateAllFromEnglish() }
        val translateAllPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        translateAllPanel.add(translateAllButton)
        translationsPanel.add(translateAllPanel, rowGbc)

        // Add other languages
        val otherLangs = translations.keys.filter { it != "en" }.sorted()
        for (lang in otherLangs) {
            addLanguageRow(translationsPanel, lang, false, rowGbc, row++)
        }

        // Add any common languages not already present
        for (lang in COMMON_LANGUAGES) {
            if (lang != "en" && !translations.containsKey(lang)) {
                val langFiles = TranslationFileFinder.findTranslationFilesForLanguage(project, lang)
                if (langFiles.isNotEmpty()) {
                    translations[lang] = TranslationEntry("", langFiles.first(), false)
                    addLanguageRow(translationsPanel, lang, false, rowGbc, row++)
                }
            }
        }

        // Add vertical glue at the end
        rowGbc.apply {
            gridy = row
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        }
        translationsPanel.add(Box.createVerticalGlue(), rowGbc)

        val scrollPane = JBScrollPane(translationsPanel)
        scrollPane.border = JBUI.Borders.customLine(JBColor.border(), 1)
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        mainPanel.add(scrollPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun addLanguageRow(panel: JPanel, lang: String, isSource: Boolean, gbc: GridBagConstraints, row: Int) {
        val entry = translations[lang]
        val langName = LANGUAGE_NAMES[lang] ?: lang.uppercase()

        // Column 0: Language label
        gbc.apply {
            gridx = 0
            gridy = row
            gridwidth = 1
            weightx = 0.0
            weighty = 0.0
            fill = GridBagConstraints.NONE
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(2, 0, 2, 8)
        }

        val labelPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        val label = JBLabel("$langName ($lang)")
        label.preferredSize = Dimension(JBUI.scale(110), JBUI.scale(24))
        if (isSource) {
            label.font = label.font.deriveFont(Font.BOLD)
        }
        labelPanel.add(label)

        // Status indicator (inline with label)
        if (entry?.exists == true) {
            val existsLabel = JBLabel("✓")
            existsLabel.foreground = JBColor(Color(60, 150, 60), Color(100, 200, 100))
            existsLabel.toolTipText = "Key exists in this language file"
            labelPanel.add(existsLabel)
        } else if (entry?.file != null) {
            val newLabel = JBLabel("new")
            newLabel.font = newLabel.font.deriveFont(Font.ITALIC, newLabel.font.size - 1f)
            newLabel.foreground = JBColor(Color(180, 130, 40), Color(220, 180, 80))
            newLabel.toolTipText = "Key will be created when you save"
            labelPanel.add(newLabel)
        }

        panel.add(labelPanel, gbc)

        // Column 1: Text field
        gbc.apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(2, 0, 2, 8)
        }
        val textField = JBTextField(entry?.value ?: "")
        textField.preferredSize = Dimension(JBUI.scale(350), JBUI.scale(28))
        if (isSource) {
            textField.toolTipText = "Source text (English) - other languages translate from this"
        }
        textFields[lang] = textField
        panel.add(textField, gbc)

        // Column 2: Translate button (for non-English)
        gbc.apply {
            gridx = 2
            weightx = 0.0
            fill = GridBagConstraints.NONE
            insets = JBUI.insets(2, 0, 2, 0)
        }

        if (!isSource) {
            val translateBtn = JButton("Translate")
            translateBtn.preferredSize = Dimension(JBUI.scale(85), JBUI.scale(26))
            translateBtn.toolTipText = "Translate from English using Google Translate"
            translateBtn.addActionListener { translateSingleLanguage(lang) }
            translateButtons[lang] = translateBtn
            panel.add(translateBtn, gbc)
        } else {
            // Add placeholder for alignment
            val placeholder = JPanel()
            placeholder.preferredSize = Dimension(JBUI.scale(85), JBUI.scale(26))
            panel.add(placeholder, gbc)
        }
    }

    private fun translateSingleLanguage(targetLang: String) {
        val englishText = textFields["en"]?.text ?: return
        if (englishText.isBlank()) {
            Messages.showWarningDialog(project, "Please enter English text first.", "No Source Text")
            return
        }

        translateWithGoogle(englishText, targetLang) { translatedText ->
            SwingUtilities.invokeLater {
                textFields[targetLang]?.text = translatedText
            }
        }
    }

    private fun translateAllFromEnglish() {
        val englishText = textFields["en"]?.text ?: return
        if (englishText.isBlank()) {
            Messages.showWarningDialog(project, "Please enter English text first.", "No Source Text")
            return
        }

        for ((lang, textField) in textFields) {
            if (lang != "en" && (textField.text.isBlank() || textField.text == translations[lang]?.value)) {
                translateWithGoogle(englishText, lang) { translatedText ->
                    SwingUtilities.invokeLater {
                        textField.text = translatedText
                    }
                }
            }
        }
    }

    /**
     * Translate text using Google Translate (free API).
     */
    private fun translateWithGoogle(text: String, targetLang: String, callback: (String) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val encodedText = URLEncoder.encode(text, "UTF-8")
                val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=$targetLang&dt=t&q=$encodedText")

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }

                // Parse the response - it's a nested JSON array
                // Format: [[["translated text","original text",null,null,10]],null,"en",...]
                val translated = parseGoogleTranslateResponse(response)
                callback(translated ?: text)

            } catch (e: Exception) {
                LOG.warn("TRANSLOCO-TRANSLATE: Failed to translate to $targetLang: ${e.message}")
                callback(text) // Return original text on failure
            }
        }
    }

    private fun parseGoogleTranslateResponse(response: String): String? {
        try {
            // Simple parsing of the Google Translate response
            // The response is like: [[["Hola","Hello",null,null,10]],null,"en",...]
            val result = StringBuilder()
            var i = 0
            var depth = 0
            var inString = false
            var escape = false
            var currentString = StringBuilder()
            var foundFirst = false

            while (i < response.length) {
                val c = response[i]

                if (escape) {
                    if (inString) currentString.append(c)
                    escape = false
                } else when (c) {
                    '\\' -> {
                        escape = true
                        if (inString) currentString.append(c)
                    }
                    '"' -> {
                        if (inString) {
                            if (depth == 3 && !foundFirst) {
                                result.append(currentString)
                                foundFirst = true
                            } else if (depth == 3 && foundFirst) {
                                // Check if this is a continuation
                                val nextNonSpace = response.substring(i + 1).trimStart()
                                if (nextNonSpace.startsWith(",null") || nextNonSpace.startsWith("]")) {
                                    // This was the source text, we already have what we need
                                }
                            }
                            currentString = StringBuilder()
                        }
                        inString = !inString
                    }
                    '[' -> if (!inString) depth++
                    ']' -> if (!inString) depth--
                    else -> if (inString) currentString.append(c)
                }
                i++
            }

            return if (result.isNotEmpty()) result.toString() else null
        } catch (e: Exception) {
            LOG.warn("TRANSLOCO-TRANSLATE: Failed to parse response: ${e.message}")
            return null
        }
    }

    override fun doOKAction() {
        // Save all translations
        WriteCommandAction.runWriteCommandAction(project, "Update Translations", null, {
            for ((lang, textField) in textFields) {
                val newValue = textField.text
                val entry = translations[lang] ?: continue
                val file = entry.file ?: continue

                if (newValue.isBlank()) continue

                // Only update if value changed or it's a new key
                if (newValue != entry.value || !entry.exists) {
                    if (entry.exists) {
                        updateTranslation(file, keyInFile, newValue)
                    } else {
                        createTranslation(file, keyInFile, newValue)
                    }
                }
            }
        })

        super.doOKAction()
    }

    private fun updateTranslation(file: VirtualFile, keyPath: String, newValue: String) {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: return
        val navResult = JsonKeyNavigator.navigateToKey(psiFile, keyPath)

        if (navResult.found && navResult.value is JsonStringLiteral) {
            val generator = JsonElementGenerator(project)
            val newLiteral = generator.createStringLiteral(newValue)
            navResult.value.replace(newLiteral)
            LOG.warn("TRANSLOCO-EDIT: Updated '$keyPath' in ${file.name}")
        }
    }

    private fun createTranslation(file: VirtualFile, keyPath: String, newValue: String) {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: return
        val rootObject = psiFile.topLevelValue as? JsonObject ?: return

        val keyParts = keyPath.split(".")
        var currentObject = rootObject

        // Navigate/create nested objects
        for (i in 0 until keyParts.size - 1) {
            val part = keyParts[i]
            val existing = currentObject.findProperty(part)

            if (existing != null && existing.value is JsonObject) {
                currentObject = existing.value as JsonObject
            } else if (existing == null) {
                // Create new nested object
                val generator = JsonElementGenerator(project)
                val newProp = generator.createProperty(part, "{}")
                val added = currentObject.addBefore(newProp, currentObject.lastChild) as? JsonProperty
                currentObject = added?.value as? JsonObject ?: return
            } else {
                LOG.warn("TRANSLOCO-EDIT: Cannot create nested key, path blocked at '$part'")
                return
            }
        }

        // Add the final property
        val finalKey = keyParts.last()
        val generator = JsonElementGenerator(project)
        val escapedValue = newValue.replace("\\", "\\\\").replace("\"", "\\\"")
        val newProp = generator.createProperty(finalKey, "\"$escapedValue\"")

        // Add before the closing brace
        currentObject.addBefore(newProp, currentObject.lastChild)
        LOG.warn("TRANSLOCO-EDIT: Created '$keyPath' in ${file.name}")
    }
}
