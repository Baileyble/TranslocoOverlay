package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.baileyble.translocooverlay.util.TranslocoScopeDetector
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.prefs.Preferences
import javax.swing.*

/**
 * Dialog for creating a new Transloco translation from selected text in the template.
 * Features:
 * - Key name input field for the new translation key
 * - Translation method selector (Pipe or Directive)
 * - Automatic detection of existing *transloco directive context
 * - Parameter detection and naming for Angular interpolations
 * - English value pre-filled with the selected text
 * - Other language inputs with translate buttons
 * - Location selector for choosing where to create the translation
 * - On save: creates the translation in JSON files and updates the template
 */
class TranslocoCreateTranslationDialog(
    private val project: Project,
    private val editor: Editor,
    private val selectedText: String,
    private val selectionStart: Int,
    private val selectionEnd: Int
) : DialogWrapper(project, true) {

    companion object {
        private val LOG = Logger.getInstance(TranslocoCreateTranslationDialog::class.java)
        private val PREFS = Preferences.userNodeForPackage(TranslocoCreateTranslationDialog::class.java)
        private const val LAST_LOCATION_KEY = "lastTranslationLocation"
        private const val LAST_METHOD_KEY = "lastTranslationMethod"

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

        // Regex patterns for detecting transloco directives
        private val STRUCTURAL_DIRECTIVE_PATTERN = Regex("""\*transloco\s*=\s*["']([^"']+)["']""")
        private val LET_VAR_PATTERN = Regex("""let\s+(\w+)""")
        private val READ_SCOPE_PATTERN = Regex("""read\s*:\s*['"]([^'"]+)['"]""")

        // Regex for detecting Angular interpolations: {{expression}}
        private val INTERPOLATION_PATTERN = Regex("""\{\{\s*([^}]+?)\s*\}\}""")

        fun getLastUsedLocation(): String? = PREFS.get(LAST_LOCATION_KEY, null)
        fun setLastUsedLocation(path: String) = PREFS.put(LAST_LOCATION_KEY, path)
        fun getLastUsedMethod(): String = PREFS.get(LAST_METHOD_KEY, "pipe")
        fun setLastUsedMethod(method: String) = PREFS.put(LAST_METHOD_KEY, method)
    }

    /**
     * Represents a translation file location with all its language files.
     */
    data class TranslationLocation(
        val displayPath: String,
        val fullPath: String,
        val files: Map<String, VirtualFile>  // lang -> file
    )

    /**
     * Represents detected transloco directive context from the template.
     */
    data class DirectiveContext(
        val variableName: String,   // e.g., "t", "translate"
        val scope: String?,         // e.g., "admin", "dashboard" (from read: 'scope')
        val found: Boolean
    ) {
        companion object {
            val NOT_FOUND = DirectiveContext("t", null, false)
        }
    }

    /**
     * Represents a detected parameter (Angular interpolation) in the selected text.
     */
    data class DetectedParam(
        val originalExpression: String,  // e.g., "req.name.firstName"
        val fullMatch: String,           // e.g., "{{req.name.firstName}}"
        var paramName: String            // e.g., "firstName" (user can rename)
    )

    /**
     * Translation method options.
     */
    enum class TranslationMethod(val displayName: String, val description: String) {
        PIPE("Pipe", "{{ 'key' | transloco }}"),
        DIRECTIVE("Directive", "{{ t('key') }} with *transloco")
    }

    /**
     * Represents an existing translation that matches the selected text.
     */
    data class ExistingTranslation(
        val key: String,
        val value: String,
        val location: TranslationLocation,
        val file: VirtualFile
    )

    private lateinit var keyTextField: JBTextField
    private val languageTextFields = mutableMapOf<String, JBTextField>()
    private var selectedLocation: TranslationLocation? = null
    private lateinit var locationLabel: JBLabel
    private lateinit var locationButton: JButton
    private var availableLocations: List<TranslationLocation> = emptyList()

    // Method selection
    private lateinit var methodComboBox: JComboBox<TranslationMethod>
    private lateinit var directiveInfoLabel: JBLabel
    private lateinit var previewLabel: JBLabel
    private var detectedContext: DirectiveContext = DirectiveContext.NOT_FOUND

    // Parameter handling
    private val detectedParams = mutableListOf<DetectedParam>()
    private val paramNameFields = mutableMapOf<Int, JBTextField>()
    private lateinit var paramsPanel: JPanel
    private var textWithoutParams: String = ""

    // Component scope detection (from TRANSLOCO_SCOPE provider)
    private var detectedComponentScope: TranslocoScopeDetector.ScopeDetectionResult =
        TranslocoScopeDetector.ScopeDetectionResult.NOT_FOUND

    // Existing translation detection
    private var existingTranslations: List<ExistingTranslation> = emptyList()
    private var showingExistingPanel = false
    private lateinit var mainContentPanel: JPanel
    private lateinit var existingPanel: JPanel
    private lateinit var createPanel: JPanel
    private lateinit var cardLayout: CardLayout

    // Key validation feedback
    private lateinit var keyStatusLabel: JBLabel

    init {
        title = "Create Translation"

        // Detect directive context before initializing UI
        detectDirectiveContext()

        // Detect component scope from TRANSLOCO_SCOPE provider
        detectComponentScope()

        // Detect parameters in selected text
        detectParameters()

        init()

        // Find available locations and search for existing translations
        findAvailableLocationsAndExisting()
    }

    /**
     * Detect the TRANSLOCO_SCOPE provider in the associated component file.
     */
    private fun detectComponentScope() {
        val document = editor.document
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
        val virtualFile = psiFile.virtualFile ?: return

        if (virtualFile.extension == "html") {
            detectedComponentScope = TranslocoScopeDetector.detectScopeForHtmlFile(project, virtualFile)
            if (detectedComponentScope.found) {
                LOG.debug("TRANSLOCO-CREATE: Detected component scope: ${detectedComponentScope.scope}")
            }
        }
    }

    /**
     * Detect Angular interpolations in the selected text and extract them as parameters.
     */
    private fun detectParameters() {
        val matches = INTERPOLATION_PATTERN.findAll(selectedText).toList()

        // Track unique expressions to avoid duplicates
        val seenExpressions = mutableSetOf<String>()

        for (match in matches) {
            val fullMatch = match.value  // e.g., "{{req.name.firstName}}"
            val expression = match.groupValues[1].trim()  // e.g., "req.name.firstName"

            if (expression !in seenExpressions) {
                seenExpressions.add(expression)

                // Generate a suggested param name from the expression
                val suggestedName = generateParamName(expression)

                detectedParams.add(DetectedParam(expression, fullMatch, suggestedName))
            }
        }

        // Create the text with placeholders for translation
        textWithoutParams = if (detectedParams.isEmpty()) {
            selectedText
        } else {
            var result = selectedText
            for (param in detectedParams) {
                result = result.replace(param.fullMatch, "{{${param.paramName}}}")
            }
            result
        }

        LOG.debug("TRANSLOCO-CREATE: Detected ${detectedParams.size} parameters in selected text")
    }

    /**
     * Generate a suggested parameter name from an expression.
     * e.g., "req.name.firstName" -> "firstName"
     * e.g., "user.email" -> "email"
     */
    private fun generateParamName(expression: String): String {
        // Get the last part after the last dot
        val lastPart = expression.substringAfterLast(".")

        // Clean up: remove any non-alphanumeric characters, convert to camelCase
        val cleaned = lastPart.replace(Regex("[^a-zA-Z0-9]"), "")

        // Ensure it starts with lowercase
        return if (cleaned.isNotEmpty()) {
            cleaned.replaceFirstChar { it.lowercase() }
        } else {
            "param${detectedParams.size + 1}"
        }
    }

    /**
     * Detect if there's an existing *transloco directive in the ancestor elements.
     */
    private fun detectDirectiveContext() {
        val document = editor.document
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
        val element = psiFile.findElementAt(selectionStart) ?: return

        detectedContext = findTranslocoDirectiveInAncestors(element)
        LOG.debug("TRANSLOCO-CREATE: Detected directive context: $detectedContext")
    }

    /**
     * Search up the PSI tree for a *transloco directive.
     */
    private fun findTranslocoDirectiveInAncestors(element: PsiElement): DirectiveContext {
        var current: PsiElement? = element
        var depth = 0
        val maxDepth = 50 // Prevent infinite loops

        while (current != null && depth < maxDepth) {
            val text = current.text ?: ""

            // Check if this element contains *transloco
            if (text.contains("*transloco")) {
                val directiveMatch = STRUCTURAL_DIRECTIVE_PATTERN.find(text)
                if (directiveMatch != null) {
                    val directiveContent = directiveMatch.groupValues[1]

                    // Extract variable name (e.g., "let t" -> "t")
                    val varMatch = LET_VAR_PATTERN.find(directiveContent)
                    val variableName = varMatch?.groupValues?.get(1) ?: "t"

                    // Extract scope (e.g., "read: 'admin'" -> "admin")
                    val scopeMatch = READ_SCOPE_PATTERN.find(directiveContent)
                    val scope = scopeMatch?.groupValues?.get(1)

                    return DirectiveContext(variableName, scope, true)
                }
            }

            current = current.parent
            depth++
        }

        return DirectiveContext.NOT_FOUND
    }

    override fun createCenterPanel(): JComponent {
        mainContentPanel = JPanel()
        cardLayout = CardLayout()
        mainContentPanel.layout = cardLayout

        // Create the "existing translations found" panel
        existingPanel = createExistingTranslationsPanel()
        mainContentPanel.add(existingPanel, "existing")

        // Create the normal "create new" panel
        createPanel = createCreateNewPanel()
        mainContentPanel.add(createPanel, "create")

        // Start with create panel (will switch to existing if matches found)
        cardLayout.show(mainContentPanel, "create")

        return mainContentPanel
    }

    /**
     * Create the panel shown when existing translations are found.
     */
    private fun createExistingTranslationsPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, JBUI.scale(12)))
        panel.preferredSize = Dimension(JBUI.scale(700), JBUI.scale(450))
        panel.border = JBUI.Borders.empty(8)

        // Header
        val headerPanel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()

        gbc.apply {
            gridx = 0
            gridy = 0
            gridwidth = 2
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(0, 0, 12, 0)
        }
        val infoLabel = JBLabel("<html><b>Existing translation(s) found!</b><br>" +
            "The selected text already exists in your translation files. " +
            "You can use an existing key or create a new one.</html>")
        infoLabel.foreground = JBColor(Color(0, 100, 0), Color(100, 200, 100))
        headerPanel.add(infoLabel, gbc)

        // Selected text display
        gbc.apply {
            gridy = 1
            gridwidth = 1
            weightx = 0.0
            insets = JBUI.insets(8, 0, 8, 8)
        }
        val selectedLabel = JBLabel("Selected text:")
        selectedLabel.font = selectedLabel.font.deriveFont(Font.BOLD)
        headerPanel.add(selectedLabel, gbc)

        gbc.apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(8, 0, 8, 0)
        }
        val selectedTextDisplay = JBTextField(selectedText)
        selectedTextDisplay.isEditable = false
        selectedTextDisplay.background = JBColor(Color(245, 245, 245), Color(60, 63, 65))
        headerPanel.add(selectedTextDisplay, gbc)

        // Method selector for existing
        gbc.apply {
            gridx = 0
            gridy = 2
            weightx = 0.0
            fill = GridBagConstraints.NONE
            insets = JBUI.insets(0, 0, 8, 8)
        }
        val methodLabel = JBLabel("Method:")
        methodLabel.font = methodLabel.font.deriveFont(Font.BOLD)
        headerPanel.add(methodLabel, gbc)

        gbc.apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(0, 0, 8, 0)
        }
        // Reuse the method combo box (it's already initialized)
        headerPanel.add(createMethodSelector(), gbc)

        panel.add(headerPanel, BorderLayout.NORTH)

        // List of existing translations (will be populated later)
        val listPanel = JPanel(BorderLayout())
        listPanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                "Existing Translations"
            ),
            JBUI.Borders.empty(8)
        )

        val placeholder = JBLabel("Searching for existing translations...")
        placeholder.horizontalAlignment = SwingConstants.CENTER
        placeholder.name = "existingListPlaceholder"
        listPanel.add(placeholder, BorderLayout.CENTER)

        panel.add(listPanel, BorderLayout.CENTER)

        // Buttons at bottom
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        val createNewButton = JButton("Create New Instead")
        createNewButton.addActionListener {
            showingExistingPanel = false
            cardLayout.show(mainContentPanel, "create")
            title = "Create Translation"
        }
        buttonPanel.add(createNewButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    /**
     * Create a method selector combo box (used in both panels).
     */
    private fun createMethodSelector(): JPanel {
        val methodPanel = JPanel(BorderLayout(JBUI.scale(12), 0))

        if (!::methodComboBox.isInitialized) {
            methodComboBox = JComboBox(TranslationMethod.values())
            methodComboBox.renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is TranslationMethod) {
                        text = "${value.displayName} - ${value.description}"
                    }
                    return this
                }
            }

            val defaultMethod = if (detectedContext.found) {
                TranslationMethod.DIRECTIVE
            } else {
                when (getLastUsedMethod()) {
                    "directive" -> TranslationMethod.DIRECTIVE
                    else -> TranslationMethod.PIPE
                }
            }
            methodComboBox.selectedItem = defaultMethod

            methodComboBox.addActionListener {
                updateDirectiveInfo()
                updatePreview()
            }
        }

        methodPanel.add(methodComboBox, BorderLayout.WEST)

        if (!::directiveInfoLabel.isInitialized) {
            directiveInfoLabel = JBLabel()
            directiveInfoLabel.font = directiveInfoLabel.font.deriveFont(Font.ITALIC, directiveInfoLabel.font.size - 1f)
        }
        methodPanel.add(directiveInfoLabel, BorderLayout.CENTER)

        return methodPanel
    }

    /**
     * Create the normal "create new translation" panel.
     */
    private fun createCreateNewPanel(): JPanel {
        val mainPanel = JPanel(BorderLayout(0, JBUI.scale(12)))
        val height = if (detectedParams.isNotEmpty()) 620 else 520
        mainPanel.preferredSize = Dimension(JBUI.scale(700), JBUI.scale(height))
        mainPanel.border = JBUI.Borders.empty(8)

        // Header section
        val headerPanel = createHeaderPanel()
        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // Content section with translations
        val contentPanel = createContentPanel()
        mainPanel.add(contentPanel, BorderLayout.CENTER)

        return mainPanel
    }

    private fun createHeaderPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.emptyBottom(12)
        val gbc = GridBagConstraints()
        var row = 0

        // Row 1: Selected Text (readonly display)
        gbc.apply {
            gridx = 0
            gridy = row
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(0, 0, 8, 8)
        }
        val selectedLabel = JBLabel("Selected text:")
        selectedLabel.font = selectedLabel.font.deriveFont(Font.BOLD)
        panel.add(selectedLabel, gbc)

        gbc.apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(0, 0, 8, 0)
        }
        val selectedTextDisplay = JBTextField(selectedText)
        selectedTextDisplay.isEditable = false
        selectedTextDisplay.background = JBColor(Color(245, 245, 245), Color(60, 63, 65))
        panel.add(selectedTextDisplay, gbc)
        row++

        // Row 2: Translation Key input
        gbc.apply {
            gridx = 0
            gridy = row
            weightx = 0.0
            fill = GridBagConstraints.NONE
            insets = JBUI.insets(0, 0, 8, 8)
        }
        val keyLabel = JBLabel("Translation key:")
        keyLabel.font = keyLabel.font.deriveFont(Font.BOLD)
        panel.add(keyLabel, gbc)

        gbc.apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(0, 0, 4, 0)
        }
        keyTextField = JBTextField()
        keyTextField.toolTipText = "Enter the translation key (e.g., 'user.profile.title')"
        keyTextField.emptyText.text = "e.g., user.profile.title"
        keyTextField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onKeyChanged()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onKeyChanged()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onKeyChanged()
        })
        panel.add(keyTextField, gbc)
        row++

        // Row 2.5: Key status label (shows if key already exists)
        gbc.apply {
            gridx = 1
            gridy = row
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(0, 0, 8, 0)
        }
        keyStatusLabel = JBLabel(" ")
        keyStatusLabel.font = keyStatusLabel.font.deriveFont(keyStatusLabel.font.size - 1f)
        panel.add(keyStatusLabel, gbc)
        row++

        // Row 3: Translation Method selector
        gbc.apply {
            gridx = 0
            gridy = row
            weightx = 0.0
            fill = GridBagConstraints.NONE
            insets = JBUI.insets(0, 0, 8, 8)
        }
        val methodLabel = JBLabel("Method:")
        methodLabel.font = methodLabel.font.deriveFont(Font.BOLD)
        panel.add(methodLabel, gbc)

        gbc.apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(0, 0, 8, 0)
        }
        val methodPanel = JPanel(BorderLayout(JBUI.scale(12), 0))

        methodComboBox = JComboBox(TranslationMethod.values())
        methodComboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is TranslationMethod) {
                    text = "${value.displayName} - ${value.description}"
                }
                return this
            }
        }

        // Set default selection based on detected context or last used
        val defaultMethod = if (detectedContext.found) {
            TranslationMethod.DIRECTIVE
        } else {
            when (getLastUsedMethod()) {
                "directive" -> TranslationMethod.DIRECTIVE
                else -> TranslationMethod.PIPE
            }
        }
        methodComboBox.selectedItem = defaultMethod

        methodComboBox.addActionListener {
            updateDirectiveInfo()
            updatePreview()
        }
        methodPanel.add(methodComboBox, BorderLayout.WEST)

        // Directive context info
        directiveInfoLabel = JBLabel()
        directiveInfoLabel.font = directiveInfoLabel.font.deriveFont(Font.ITALIC, directiveInfoLabel.font.size - 1f)
        methodPanel.add(directiveInfoLabel, BorderLayout.CENTER)

        panel.add(methodPanel, gbc)
        row++

        // Row 4: Parameters section (only if params detected)
        if (detectedParams.isNotEmpty()) {
            gbc.apply {
                gridx = 0
                gridy = row
                gridwidth = 2
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(4, 0, 8, 0)
            }
            paramsPanel = createParamsPanel()
            panel.add(paramsPanel, gbc)
            row++
        }

        // Row 5: Preview
        gbc.apply {
            gridx = 0
            gridy = row
            gridwidth = 1
            weightx = 0.0
            fill = GridBagConstraints.NONE
            insets = JBUI.insets(0, 0, 8, 8)
        }
        val previewTitleLabel = JBLabel("Preview:")
        previewTitleLabel.font = previewTitleLabel.font.deriveFont(Font.BOLD)
        panel.add(previewTitleLabel, gbc)

        gbc.apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(0, 0, 8, 0)
        }
        previewLabel = JBLabel()
        previewLabel.font = Font(Font.MONOSPACED, Font.PLAIN, previewLabel.font.size)
        previewLabel.foreground = JBColor(Color(0, 102, 153), Color(102, 178, 255))
        panel.add(previewLabel, gbc)
        row++

        // Row 6: Location selector
        gbc.apply {
            gridx = 0
            gridy = row
            weightx = 0.0
            fill = GridBagConstraints.NONE
            insets = JBUI.insets(0, 0, 0, 8)
        }
        val locLabel = JBLabel("Location:")
        locLabel.font = locLabel.font.deriveFont(Font.BOLD)
        panel.add(locLabel, gbc)

        gbc.apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(0, 0, 0, 0)
        }
        val locationPanel = JPanel(BorderLayout(JBUI.scale(8), 0))
        locationLabel = JBLabel("Loading...")
        locationLabel.foreground = JBColor.GRAY
        locationPanel.add(locationLabel, BorderLayout.CENTER)

        locationButton = JButton("Change...")
        locationButton.addActionListener { showLocationSelector() }
        locationButton.isEnabled = false
        locationPanel.add(locationButton, BorderLayout.EAST)

        panel.add(locationPanel, gbc)

        // Initialize UI state
        updateDirectiveInfo()
        updatePreview()

        return panel
    }

    /**
     * Create the parameters panel showing detected interpolations and allowing renaming.
     */
    private fun createParamsPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(JBColor.border()),
                "Detected Parameters (${detectedParams.size})"
            ),
            JBUI.Borders.empty(8)
        )

        val gbc = GridBagConstraints()

        // Header row
        gbc.apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(0, 0, 8, 16)
        }
        val exprHeader = JBLabel("Expression")
        exprHeader.font = exprHeader.font.deriveFont(Font.BOLD)
        panel.add(exprHeader, gbc)

        gbc.apply {
            gridx = 1
            insets = JBUI.insets(0, 0, 8, 16)
        }
        panel.add(JBLabel("→"), gbc)

        gbc.apply {
            gridx = 2
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(0, 0, 8, 0)
        }
        val nameHeader = JBLabel("Parameter Name")
        nameHeader.font = nameHeader.font.deriveFont(Font.BOLD)
        panel.add(nameHeader, gbc)

        // Parameter rows
        detectedParams.forEachIndexed { index, param ->
            gbc.apply {
                gridx = 0
                gridy = index + 1
                weightx = 0.0
                fill = GridBagConstraints.NONE
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(2, 0, 2, 16)
            }
            val exprLabel = JBLabel(param.originalExpression)
            exprLabel.font = Font(Font.MONOSPACED, Font.PLAIN, exprLabel.font.size)
            exprLabel.foreground = JBColor.GRAY
            panel.add(exprLabel, gbc)

            gbc.apply {
                gridx = 1
                insets = JBUI.insets(2, 0, 2, 16)
            }
            panel.add(JBLabel("→"), gbc)

            gbc.apply {
                gridx = 2
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(2, 0, 2, 0)
            }
            val nameField = JBTextField(param.paramName)
            nameField.toolTipText = "Parameter name to use in translation"
            nameField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onParamNameChanged(index)
                override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onParamNameChanged(index)
                override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onParamNameChanged(index)
            })
            paramNameFields[index] = nameField
            panel.add(nameField, gbc)
        }

        return panel
    }

    /**
     * Called when a parameter name is changed - updates the preview and translation text.
     */
    private fun onParamNameChanged(index: Int) {
        val newName = paramNameFields[index]?.text?.trim() ?: return
        if (newName.isNotBlank()) {
            detectedParams[index].paramName = newName
            updateTextWithParams()
            updatePreview()
            updateEnglishTextField()
        }
    }

    /**
     * Update the textWithoutParams to reflect current parameter names.
     */
    private fun updateTextWithParams() {
        var result = selectedText
        for (param in detectedParams) {
            result = result.replace(param.fullMatch, "{{${param.paramName}}}")
        }
        textWithoutParams = result
    }

    /**
     * Update the English text field with the parameterized text.
     */
    private fun updateEnglishTextField() {
        languageTextFields["en"]?.text = textWithoutParams
    }

    private fun updateDirectiveInfo() {
        val selectedMethod = methodComboBox.selectedItem as? TranslationMethod ?: TranslationMethod.PIPE

        if (selectedMethod == TranslationMethod.DIRECTIVE) {
            if (detectedContext.found) {
                val scopeInfo = if (detectedContext.scope != null) {
                    " (scope: '${detectedContext.scope}')"
                } else ""
                directiveInfoLabel.text = "Using ${detectedContext.variableName}()$scopeInfo"
                directiveInfoLabel.foreground = JBColor(Color(0, 128, 0), Color(100, 200, 100))
            } else {
                directiveInfoLabel.text = "No *transloco directive found in scope"
                directiveInfoLabel.foreground = JBColor(Color(180, 100, 0), Color(255, 180, 100))
            }
        } else {
            directiveInfoLabel.text = ""
        }
    }

    private fun updatePreview() {
        val key = keyTextField.text.trim().ifBlank { "your.key.here" }
        val preview = generateTranslocoReplacement(key)
        previewLabel.text = preview
    }

    /**
     * Called when the key text field changes - updates preview and validates key.
     */
    private fun onKeyChanged() {
        updatePreview()
        validateKeyExists()
    }

    /**
     * Check if the key already exists and update the status label.
     */
    private fun validateKeyExists() {
        val key = keyTextField.text.trim()

        // Clear status if key is empty or invalid format
        if (key.isBlank() || !key.matches(Regex("""^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)*$"""))) {
            keyStatusLabel.text = " "
            return
        }

        val location = selectedLocation
        if (location == null) {
            keyStatusLabel.text = " "
            return
        }

        // Check if key exists in background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            val keyToCheck = getEffectiveKeyForStorage(key)
            val englishFile = location.files["en"]

            val exists = if (englishFile != null) {
                ReadAction.compute<Boolean, Throwable> {
                    val psiFile = PsiManager.getInstance(project).findFile(englishFile) as? JsonFile
                    psiFile != null && JsonKeyNavigator.keyExists(psiFile, keyToCheck)
                }
            } else {
                false
            }

            SwingUtilities.invokeLater {
                if (exists) {
                    keyStatusLabel.text = "Key '$keyToCheck' already exists - choose a different key or use Edit Translation"
                    keyStatusLabel.foreground = JBColor(Color(180, 0, 0), Color(255, 100, 100))
                    keyStatusLabel.icon = UIManager.getIcon("OptionPane.warningIcon")
                } else {
                    keyStatusLabel.text = " "
                    keyStatusLabel.icon = null
                }
            }
        }
    }

    private fun createContentPanel(): JComponent {
        val translationsPanel = JPanel(GridBagLayout())
        translationsPanel.border = JBUI.Borders.empty(12)
        val gbc = GridBagConstraints()
        var row = 0

        // Section header
        gbc.apply {
            gridx = 0
            gridy = row++
            gridwidth = 3
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(0, 0, 12, 0)
        }
        val sectionLabel = JBLabel("Translations")
        sectionLabel.font = sectionLabel.font.deriveFont(Font.BOLD, sectionLabel.font.size + 1f)
        translationsPanel.add(sectionLabel, gbc)

        // English row (pre-filled with parameterized text)
        addLanguageRow(translationsPanel, "en", true, gbc, row++)

        // Other common languages
        for (lang in COMMON_LANGUAGES) {
            if (lang != "en") {
                addLanguageRow(translationsPanel, lang, false, gbc, row++)
            }
        }

        // Translate All button
        gbc.apply {
            gridy = row++
            gridwidth = 3
            insets = JBUI.insets(16, 0, 0, 0)
            anchor = GridBagConstraints.EAST
            fill = GridBagConstraints.NONE
        }
        val translateAllButton = JButton("Translate All from English")
        translateAllButton.toolTipText = "Auto-translate empty fields using Google Translate"
        translateAllButton.addActionListener { translateAllFromEnglish() }
        translationsPanel.add(translateAllButton, gbc)

        // Vertical glue
        gbc.apply {
            gridy = row
            weighty = 1.0
            fill = GridBagConstraints.BOTH
        }
        translationsPanel.add(Box.createVerticalGlue(), gbc)

        val scrollPane = JBScrollPane(translationsPanel)
        scrollPane.border = JBUI.Borders.customLine(JBColor.border(), 1)
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        return scrollPane
    }

    private fun addLanguageRow(
        panel: JPanel,
        lang: String,
        isEnglish: Boolean,
        gbc: GridBagConstraints,
        row: Int
    ) {
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
            insets = JBUI.insets(4, 0, 4, 12)
        }

        val label = JBLabel("$langName ($lang)")
        label.preferredSize = Dimension(JBUI.scale(120), JBUI.scale(26))
        if (isEnglish) {
            label.font = label.font.deriveFont(Font.BOLD)
        }
        panel.add(label, gbc)

        // Column 1: Text field - use parameterized text for English
        gbc.apply {
            gridx = 1
            weightx = 1.0
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4, 0, 4, 8)
        }
        val initialText = if (isEnglish) textWithoutParams else ""
        val textField = JBTextField(initialText)
        textField.preferredSize = Dimension(JBUI.scale(350), JBUI.scale(30))
        if (isEnglish) {
            textField.toolTipText = "Source text (English) - use {{paramName}} for parameters"
        }
        languageTextFields[lang] = textField
        panel.add(textField, gbc)

        // Column 2: Translate button (for non-English)
        gbc.apply {
            gridx = 2
            weightx = 0.0
            fill = GridBagConstraints.NONE
            insets = JBUI.insets(4, 0, 4, 0)
        }

        if (!isEnglish) {
            val translateBtn = JButton("Translate")
            translateBtn.preferredSize = Dimension(JBUI.scale(90), JBUI.scale(28))
            translateBtn.toolTipText = "Translate from English"
            translateBtn.addActionListener { translateSingleLanguage(lang) }
            panel.add(translateBtn, gbc)
        } else {
            panel.add(Box.createHorizontalStrut(JBUI.scale(90)), gbc)
        }
    }

    private fun findAvailableLocationsAndExisting() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val locations = mutableListOf<TranslationLocation>()
            val processedPaths = mutableSetOf<String>()
            val foundExisting = mutableListOf<ExistingTranslation>()

            // Find all translation files and group by directory - must be in read action
            val allFiles = ReadAction.compute<List<VirtualFile>, Throwable> {
                TranslationFileFinder.findAllTranslationFiles(project)
            }
            val byPath = allFiles.groupBy { it.parent?.path ?: "" }

            for ((path, files) in byPath) {
                if (path.isBlank() || processedPaths.contains(path)) continue
                processedPaths.add(path)

                val filesMap = files.associateBy { it.nameWithoutExtension }
                if (filesMap.isNotEmpty()) {
                    val displayPath = getDisplayPath(path)
                    val location = TranslationLocation(displayPath, path, filesMap)
                    locations.add(location)

                    // Search for existing translations in English files
                    val englishFile = filesMap["en"]
                    if (englishFile != null) {
                        val matches = ReadAction.compute<List<Pair<String, String>>, Throwable> {
                            searchForExistingTranslation(englishFile, selectedText)
                        }
                        for ((key, value) in matches) {
                            foundExisting.add(ExistingTranslation(key, value, location, englishFile))
                        }
                    }
                }
            }

            // Filter and sort locations based on detected component scope
            val detectedScope = detectedComponentScope.scope
            val lastUsed = getLastUsedLocation()

            availableLocations = if (detectedScope != null) {
                // Filter to only locations matching the scope
                val matchingLocations = locations.filter { location ->
                    TranslocoScopeDetector.locationMatchesScope(location.fullPath, detectedScope)
                }

                if (matchingLocations.isNotEmpty()) {
                    // Use only matching locations, sorted by last used
                    matchingLocations.sortedWith(compareBy(
                        { it.fullPath != lastUsed },
                        { it.displayPath }
                    ))
                } else {
                    // No matching locations found, fall back to all locations
                    // but prioritize any that contain the scope name
                    locations.sortedWith(compareBy(
                        { !it.fullPath.lowercase().contains(detectedScope.lowercase()) },
                        { it.fullPath != lastUsed },
                        { it.displayPath }
                    ))
                }
            } else {
                // No scope detected, use standard sorting with last used first
                locations.sortedWith(compareBy(
                    { it.fullPath != lastUsed },
                    { it.displayPath }
                ))
            }

            // Filter existing translations by scope if detected
            existingTranslations = if (detectedScope != null) {
                foundExisting.filter { existing ->
                    TranslocoScopeDetector.locationMatchesScope(existing.location.fullPath, detectedScope)
                }
            } else {
                foundExisting
            }

            LOG.debug("TRANSLOCO-CREATE: Found ${existingTranslations.size} existing translations matching '$selectedText'")

            // Update UI on EDT
            SwingUtilities.invokeLater {
                updateLocationUI()
                if (existingTranslations.isNotEmpty()) {
                    showExistingTranslationsUI()
                }
            }
        }
    }

    /**
     * Search a JSON file for translations matching the given text.
     * Returns list of (key, value) pairs.
     */
    private fun searchForExistingTranslation(file: VirtualFile, searchText: String): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: return results
        val rootObject = psiFile.topLevelValue as? JsonObject ?: return results

        // Normalize search text for comparison (trim whitespace)
        val normalizedSearch = searchText.trim()

        // Recursively search the JSON
        searchJsonObject(rootObject, "", normalizedSearch, results)

        return results
    }

    /**
     * Recursively search a JSON object for matching string values.
     */
    private fun searchJsonObject(
        jsonObject: JsonObject,
        prefix: String,
        searchText: String,
        results: MutableList<Pair<String, String>>
    ) {
        for (property in jsonObject.propertyList) {
            val key = if (prefix.isEmpty()) property.name else "$prefix.${property.name}"
            val value = property.value

            when (value) {
                is JsonObject -> searchJsonObject(value, key, searchText, results)
                is com.intellij.json.psi.JsonStringLiteral -> {
                    val stringValue = value.value
                    // Check for exact match (case-sensitive)
                    if (stringValue == searchText) {
                        results.add(key to stringValue)
                    }
                }
            }
        }
    }

    /**
     * Show the existing translations panel and populate it with found matches.
     */
    private fun showExistingTranslationsUI() {
        showingExistingPanel = true
        title = "Existing Translation Found"

        // Find the list panel in existingPanel and update it
        val listPanel = findComponentByBorder(existingPanel, "Existing Translations") as? JPanel
        if (listPanel != null) {
            listPanel.removeAll()

            // Create list of existing translations
            val listModel = DefaultListModel<ExistingTranslation>()
            existingTranslations.forEach { listModel.addElement(it) }

            val existingList = JBList(listModel)
            existingList.cellRenderer = ExistingTranslationListCellRenderer()
            existingList.selectionMode = ListSelectionModel.SINGLE_SELECTION

            if (existingTranslations.isNotEmpty()) {
                existingList.selectedIndex = 0
            }

            // Double-click to use
            existingList.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2) {
                        val selected = existingList.selectedValue
                        if (selected != null) {
                            useExistingTranslation(selected)
                        }
                    }
                }
            })

            val scrollPane = JBScrollPane(existingList)
            listPanel.add(scrollPane, BorderLayout.CENTER)

            // Add "Use Selected" button
            val useButton = JButton("Use Selected Key")
            useButton.addActionListener {
                val selected = existingList.selectedValue
                if (selected != null) {
                    useExistingTranslation(selected)
                }
            }
            val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT))
            buttonPanel.add(useButton)
            listPanel.add(buttonPanel, BorderLayout.SOUTH)

            listPanel.revalidate()
            listPanel.repaint()
        }

        cardLayout.show(mainContentPanel, "existing")
    }

    /**
     * Find a component by its titled border text.
     */
    private fun findComponentByBorder(container: Container, title: String): Component? {
        for (component in container.components) {
            if (component is JPanel) {
                val border = component.border
                if (border is javax.swing.border.TitledBorder && border.title == title) {
                    return component
                }
                if (border is javax.swing.border.CompoundBorder) {
                    val outer = border.outsideBorder
                    if (outer is javax.swing.border.TitledBorder && outer.title == title) {
                        return component
                    }
                }
                // Recurse into nested panels
                val found = findComponentByBorder(component, title)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Use an existing translation - just replace the selected text with transloco syntax.
     */
    private fun useExistingTranslation(existing: ExistingTranslation) {
        val selectedMethod = methodComboBox.selectedItem as? TranslationMethod ?: TranslationMethod.PIPE
        setLastUsedMethod(if (selectedMethod == TranslationMethod.DIRECTIVE) "directive" else "pipe")

        WriteCommandAction.runWriteCommandAction(project, "Use Existing Translation", null, {
            val replacement = generateTranslocoReplacementForKey(existing.key)
            val document = editor.document
            document.replaceString(selectionStart, selectionEnd, replacement)
            PsiDocumentManager.getInstance(project).commitDocument(document)
        })

        LOG.debug("TRANSLOCO-CREATE: Used existing translation key '${existing.key}'")
        close(OK_EXIT_CODE)
    }

    /**
     * Generate transloco replacement for a given key (no params for existing).
     */
    private fun generateTranslocoReplacementForKey(key: String): String {
        val selectedMethod = methodComboBox.selectedItem as? TranslationMethod ?: TranslationMethod.PIPE

        return when (selectedMethod) {
            TranslationMethod.PIPE -> "{{ '$key' | transloco }}"
            TranslationMethod.DIRECTIVE -> {
                val varName = if (detectedContext.found) detectedContext.variableName else "t"
                "{{ $varName('$key') }}"
            }
        }
    }

    /**
     * Cell renderer for existing translation list.
     */
    private inner class ExistingTranslationListCellRenderer : ListCellRenderer<ExistingTranslation> {
        override fun getListCellRendererComponent(
            list: JList<out ExistingTranslation>?,
            value: ExistingTranslation?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(4)))
            panel.border = JBUI.Borders.empty(8, 8)

            if (isSelected) {
                panel.background = UIManager.getColor("List.selectionBackground")
            } else {
                panel.background = if (index % 2 == 0) {
                    UIManager.getColor("List.background")
                } else {
                    JBColor(Color(245, 245, 245), Color(50, 50, 50))
                }
            }

            if (value != null) {
                val topPanel = JPanel(BorderLayout())
                topPanel.isOpaque = false

                // Key name
                val keyLabel = JBLabel(value.key)
                keyLabel.font = Font(Font.MONOSPACED, Font.BOLD, keyLabel.font.size)
                if (isSelected) {
                    keyLabel.foreground = UIManager.getColor("List.selectionForeground")
                } else {
                    keyLabel.foreground = JBColor(Color(0, 102, 153), Color(102, 178, 255))
                }
                topPanel.add(keyLabel, BorderLayout.WEST)

                // Location badge
                val locationLabel = JBLabel(value.location.displayPath)
                locationLabel.font = locationLabel.font.deriveFont(Font.ITALIC, locationLabel.font.size - 2f)
                locationLabel.foreground = if (isSelected) {
                    UIManager.getColor("List.selectionForeground")
                } else {
                    JBColor.GRAY
                }
                topPanel.add(locationLabel, BorderLayout.EAST)

                panel.add(topPanel, BorderLayout.NORTH)

                // Value preview
                val valueLabel = JBLabel("\"${value.value}\"")
                valueLabel.font = valueLabel.font.deriveFont(valueLabel.font.size - 1f)
                valueLabel.foreground = if (isSelected) {
                    UIManager.getColor("List.selectionForeground")
                } else {
                    JBColor.GRAY
                }
                panel.add(valueLabel, BorderLayout.CENTER)

                // Preview of what will be inserted
                val previewText = generateTranslocoReplacementForKey(value.key)
                val previewLabel = JBLabel("→ $previewText")
                previewLabel.font = Font(Font.MONOSPACED, Font.PLAIN, previewLabel.font.size - 1)
                previewLabel.foreground = if (isSelected) {
                    UIManager.getColor("List.selectionForeground")
                } else {
                    JBColor(Color(0, 128, 0), Color(100, 200, 100))
                }
                panel.add(previewLabel, BorderLayout.SOUTH)
            }

            return panel
        }
    }

    private fun updateLocationUI() {
        if (availableLocations.isEmpty()) {
            val detectedScope = detectedComponentScope.scope
            if (detectedScope != null) {
                locationLabel.text = "No translation files found for scope '$detectedScope'"
            } else {
                locationLabel.text = "No translation files found"
            }
            locationLabel.foreground = JBColor.RED
            locationButton.isEnabled = false
            return
        }

        // Auto-select last used or first location
        val lastUsed = getLastUsedLocation()
        selectedLocation = availableLocations.find { it.fullPath == lastUsed }
            ?: availableLocations.firstOrNull()

        updateLocationLabel()
        locationButton.isEnabled = availableLocations.size > 1

        // Validate key now that location is set
        if (::keyStatusLabel.isInitialized) {
            validateKeyExists()
        }
    }

    private fun updateLocationLabel() {
        selectedLocation?.let {
            val detectedScope = detectedComponentScope.scope
            val scopeIndicator = if (detectedScope != null) " (scope: $detectedScope)" else ""
            locationLabel.text = it.displayPath + scopeIndicator
            locationLabel.foreground = JBColor(Color(0, 102, 153), Color(102, 178, 255))

            val tooltipText = buildString {
                append(it.fullPath)
                if (detectedScope != null) {
                    append("\n\nAuto-detected from TRANSLOCO_SCOPE provider")
                    detectedComponentScope.componentFile?.let { componentFile ->
                        append("\nComponent: ${componentFile.name}")
                    }
                }
            }
            locationLabel.toolTipText = tooltipText
        }
    }

    private fun getDisplayPath(fullPath: String): String {
        val basePath = project.basePath ?: return fullPath

        var display = if (fullPath.startsWith(basePath)) {
            fullPath.removePrefix(basePath).removePrefix("/")
        } else {
            fullPath
        }

        display = display
            .replace("libs/", "")
            .replace("apps/", "")
            .replace("/src/assets/i18n", "")
            .replace("/assets/i18n", "")
            .replace("src/", "")

        val parts = display.split("/").filter { it.isNotBlank() }
        if (parts.size > 3) {
            return ".../" + parts.takeLast(2).joinToString("/")
        }

        return display.ifBlank { "main" }
    }

    private fun showLocationSelector() {
        if (availableLocations.isEmpty()) return

        val lastUsed = getLastUsedLocation()

        val dialog = object : DialogWrapper(project, true) {
            private var selected: TranslationLocation? = null
            private lateinit var locationList: JBList<TranslationLocation>

            init {
                title = "Select Translation Location"
                init()
            }

            override fun createCenterPanel(): JComponent {
                val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
                panel.preferredSize = Dimension(JBUI.scale(500), JBUI.scale(300))
                panel.border = JBUI.Borders.empty(8)

                val infoLabel = JBLabel("Select where to create this translation:")
                infoLabel.border = JBUI.Borders.emptyBottom(8)
                panel.add(infoLabel, BorderLayout.NORTH)

                val listModel = DefaultListModel<TranslationLocation>()
                availableLocations.forEach { listModel.addElement(it) }

                locationList = JBList(listModel)
                locationList.cellRenderer = LocationListCellRenderer(lastUsed)
                locationList.selectionMode = ListSelectionModel.SINGLE_SELECTION

                val lastUsedIndex = availableLocations.indexOfFirst { it.fullPath == lastUsed }
                if (lastUsedIndex >= 0) {
                    locationList.selectedIndex = lastUsedIndex
                } else if (availableLocations.isNotEmpty()) {
                    locationList.selectedIndex = 0
                }

                locationList.addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent) {
                        if (e.clickCount == 2) doOKAction()
                    }
                })

                val scrollPane = JBScrollPane(locationList)
                scrollPane.border = JBUI.Borders.customLine(JBColor.border(), 1)
                panel.add(scrollPane, BorderLayout.CENTER)

                return panel
            }

            override fun doOKAction() {
                selected = locationList.selectedValue
                super.doOKAction()
            }

            fun getSelected(): TranslationLocation? = selected
        }

        if (dialog.showAndGet()) {
            dialog.getSelected()?.let {
                selectedLocation = it
                updateLocationLabel()
                validateKeyExists()  // Re-validate key for new location
            }
        }
    }

    private class LocationListCellRenderer(private val lastUsed: String?) : ListCellRenderer<TranslationLocation> {
        override fun getListCellRendererComponent(
            list: JList<out TranslationLocation>?,
            value: TranslationLocation?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
            panel.border = JBUI.Borders.empty(6, 8)

            if (isSelected) {
                panel.background = UIManager.getColor("List.selectionBackground")
            } else {
                panel.background = if (index % 2 == 0) {
                    UIManager.getColor("List.background")
                } else {
                    JBColor(Color(245, 245, 245), Color(50, 50, 50))
                }
            }

            if (value != null) {
                val leftPanel = JPanel(BorderLayout())
                leftPanel.isOpaque = false

                val nameLabel = JBLabel(value.displayPath)
                if (value.fullPath == lastUsed) {
                    nameLabel.font = nameLabel.font.deriveFont(Font.BOLD)
                }
                if (isSelected) {
                    nameLabel.foreground = UIManager.getColor("List.selectionForeground")
                }
                leftPanel.add(nameLabel, BorderLayout.NORTH)

                val pathLabel = JBLabel(value.fullPath)
                pathLabel.font = pathLabel.font.deriveFont(pathLabel.font.size - 2f)
                pathLabel.foreground = if (isSelected) {
                    UIManager.getColor("List.selectionForeground")
                } else {
                    JBColor.GRAY
                }
                leftPanel.add(pathLabel, BorderLayout.SOUTH)

                panel.add(leftPanel, BorderLayout.CENTER)

                if (value.fullPath == lastUsed) {
                    val badge = JBLabel("last used")
                    badge.font = badge.font.deriveFont(Font.ITALIC, badge.font.size - 2f)
                    badge.foreground = JBColor(Color(70, 130, 180), Color(100, 160, 210))
                    badge.border = JBUI.Borders.empty(0, 8)
                    panel.add(badge, BorderLayout.EAST)
                }
            }

            return panel
        }
    }

    private fun translateSingleLanguage(targetLang: String) {
        val englishText = languageTextFields["en"]?.text ?: return
        if (englishText.isBlank()) {
            Messages.showWarningDialog(project, "Please enter English text first.", "No Source Text")
            return
        }

        translateWithGoogle(englishText, targetLang) { translatedText ->
            SwingUtilities.invokeLater {
                languageTextFields[targetLang]?.text = translatedText
            }
        }
    }

    private fun translateAllFromEnglish() {
        val englishText = languageTextFields["en"]?.text ?: return
        if (englishText.isBlank()) {
            Messages.showWarningDialog(project, "Please enter English text first.", "No Source Text")
            return
        }

        for ((lang, textField) in languageTextFields) {
            if (lang != "en" && textField.text.isBlank()) {
                translateWithGoogle(englishText, lang) { translatedText ->
                    SwingUtilities.invokeLater {
                        textField.text = translatedText
                    }
                }
            }
        }
    }

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
                val translated = parseGoogleTranslateResponse(response)
                callback(translated ?: text)
            } catch (e: Exception) {
                LOG.debug("TRANSLOCO-CREATE: Failed to translate to $targetLang: ${e.message}")
                callback(text)
            }
        }
    }

    private fun parseGoogleTranslateResponse(response: String): String? {
        try {
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
            LOG.debug("TRANSLOCO-CREATE: Failed to parse response: ${e.message}")
            return null
        }
    }

    override fun doValidate(): ValidationInfo? {
        val key = keyTextField.text.trim()

        if (key.isBlank()) {
            return ValidationInfo("Please enter a translation key", keyTextField)
        }

        // Validate key format (should be dot-separated alphanumeric, allowing underscores)
        if (!key.matches(Regex("""^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)*$"""))) {
            return ValidationInfo(
                "Key must be dot-separated identifiers (e.g., 'user.profile.title')",
                keyTextField
            )
        }

        if (selectedLocation == null) {
            return ValidationInfo("Please select a location for the translation")
        }

        val englishText = languageTextFields["en"]?.text?.trim() ?: ""
        if (englishText.isBlank()) {
            return ValidationInfo("English translation cannot be empty", languageTextFields["en"])
        }

        // Validate parameter names
        for ((index, field) in paramNameFields) {
            val paramName = field.text.trim()
            if (paramName.isBlank()) {
                return ValidationInfo("Parameter name cannot be empty", field)
            }
            if (!paramName.matches(Regex("""^[a-zA-Z_][a-zA-Z0-9_]*$"""))) {
                return ValidationInfo("Parameter name must be a valid identifier", field)
            }
        }

        // Check for duplicate parameter names
        val paramNames = paramNameFields.values.map { it.text.trim() }
        val duplicates = paramNames.groupingBy { it }.eachCount().filter { it.value > 1 }
        if (duplicates.isNotEmpty()) {
            return ValidationInfo("Duplicate parameter names: ${duplicates.keys.joinToString()}")
        }

        // Check if key already exists (accounting for scope)
        val keyToCheck = getEffectiveKeyForStorage(key)
        selectedLocation?.let { location ->
            val englishFile = location.files["en"]
            if (englishFile != null) {
                val psiFile = PsiManager.getInstance(project).findFile(englishFile) as? JsonFile
                if (psiFile != null && JsonKeyNavigator.keyExists(psiFile, keyToCheck)) {
                    return ValidationInfo(
                        "Key '$keyToCheck' already exists in this location. Use Edit Translation instead.",
                        keyTextField
                    )
                }
            }
        }

        return null
    }

    /**
     * Get the key to use for storage in JSON, accounting for scope.
     */
    private fun getEffectiveKeyForStorage(key: String): String {
        val selectedMethod = methodComboBox.selectedItem as? TranslationMethod
        if (selectedMethod == TranslationMethod.DIRECTIVE && detectedContext.found && detectedContext.scope != null) {
            return key
        }
        return key
    }

    override fun doOKAction() {
        val key = keyTextField.text.trim()
        val location = selectedLocation ?: return
        val selectedMethod = methodComboBox.selectedItem as? TranslationMethod ?: TranslationMethod.PIPE

        // Remember preferences
        setLastUsedLocation(location.fullPath)
        setLastUsedMethod(if (selectedMethod == TranslationMethod.DIRECTIVE) "directive" else "pipe")

        val keyForStorage = getEffectiveKeyForStorage(key)

        WriteCommandAction.runWriteCommandAction(project, "Create Translation", null, {
            // 1. Create translations in all JSON files
            for ((lang, textField) in languageTextFields) {
                val value = textField.text.trim()
                if (value.isNotBlank()) {
                    val file = location.files[lang]
                    if (file != null) {
                        createTranslation(file, keyForStorage, value)
                    }
                }
            }

            // 2. Replace selected text in template with transloco syntax
            replaceSelectedTextWithTransloco(key)
        })

        LOG.debug("TRANSLOCO-CREATE: Created translation key '$keyForStorage' with ${detectedParams.size} params")
        super.doOKAction()
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
                addPropertyToObject(currentObject, part, "{}")
                val added = currentObject.findProperty(part)
                currentObject = added?.value as? JsonObject ?: return
            } else {
                LOG.debug("TRANSLOCO-CREATE: Cannot create nested key, path blocked at '$part'")
                return
            }
        }

        // Add the final property
        val finalKey = keyParts.last()
        val escapedValue = newValue.replace("\\", "\\\\").replace("\"", "\\\"")
        addPropertyToObject(currentObject, finalKey, "\"$escapedValue\"")
        LOG.debug("TRANSLOCO-CREATE: Created '$keyPath' in ${file.name}")
    }

    private fun addPropertyToObject(jsonObject: JsonObject, key: String, value: String) {
        val generator = JsonElementGenerator(project)
        val propertyList = jsonObject.propertyList

        if (propertyList.isEmpty()) {
            val newProp = generator.createProperty(key, value)
            jsonObject.addAfter(newProp, jsonObject.firstChild)
        } else {
            val lastProperty = propertyList.last()

            var nextSibling = lastProperty.nextSibling
            var hasComma = false
            while (nextSibling != null) {
                val text = nextSibling.text.trim()
                if (text == ",") {
                    hasComma = true
                    break
                }
                if (text == "}") break
                nextSibling = nextSibling.nextSibling
            }

            if (!hasComma) {
                val comma = generator.createComma()
                jsonObject.addAfter(comma, lastProperty)
            }

            val newProp = generator.createProperty(key, value)
            jsonObject.addBefore(newProp, jsonObject.lastChild)
        }
    }

    private fun replaceSelectedTextWithTransloco(key: String) {
        val document = editor.document
        PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return

        // Determine the replacement text based on selected method
        val replacement = generateTranslocoReplacement(key)

        // Replace the selected text
        document.replaceString(selectionStart, selectionEnd, replacement)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    /**
     * Generate the appropriate Transloco replacement text based on selected method.
     * Includes params object if parameters were detected.
     */
    private fun generateTranslocoReplacement(key: String): String {
        val selectedMethod = methodComboBox.selectedItem as? TranslationMethod ?: TranslationMethod.PIPE

        // Build params object if we have parameters
        val paramsObject = if (detectedParams.isNotEmpty()) {
            val params = detectedParams.joinToString(", ") { param ->
                "${param.paramName}: ${param.originalExpression}"
            }
            "{ $params }"
        } else {
            null
        }

        return when (selectedMethod) {
            TranslationMethod.PIPE -> {
                if (paramsObject != null) {
                    "{{ '$key' | transloco:$paramsObject }}"
                } else {
                    "{{ '$key' | transloco }}"
                }
            }
            TranslationMethod.DIRECTIVE -> {
                val varName = if (detectedContext.found) detectedContext.variableName else "t"
                if (paramsObject != null) {
                    "{{ $varName('$key', $paramsObject) }}"
                } else {
                    "{{ $varName('$key') }}"
                }
            }
        }
    }
}
