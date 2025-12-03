package com.baileyble.translocooverlay

import com.baileyble.translocooverlay.util.JsonKeyNavigator
import com.baileyble.translocooverlay.util.TranslationFileFinder
import com.intellij.json.psi.JsonFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

/**
 * Dialog for translation maintenance tasks:
 * - Find unused translations (in JSON but not in code)
 * - Find missing translations (in code but not in JSON)
 * - Find duplicates across locations
 */
class TranslocoMaintenanceDialog(
    private val project: Project
) : DialogWrapper(project, true) {

    companion object {
        private val LOG = Logger.getInstance(TranslocoMaintenanceDialog::class.java)

        // Patterns for finding translation keys in code
        private val CODE_PATTERNS = listOf(
            Regex("""['"]([^'"]+)['"]\s*\|\s*transloco"""),
            Regex("""(?<!\[)transloco\s*=\s*["']([^"']+)["']"""),
            Regex("""\[transloco]\s*=\s*["']['"]?([^"']+)['"]?["']"""),
            Regex("""t\s*\(\s*['"]([^'"]+)['"]"""),
            Regex("""\.translate[(<]\s*['"]([^'"]+)['"]"""),
            Regex("""\.selectTranslate[(<]\s*['"]([^'"]+)['"]""")
        )
    }

    data class TranslationIssue(
        val key: String,
        val location: String,
        val type: IssueType,
        val details: String = ""
    )

    enum class IssueType {
        UNUSED,      // Key in JSON but not found in code
        MISSING,     // Key in code but not in JSON
        INCOMPLETE,  // Key exists in some languages but not all
        DUPLICATE    // Same key in multiple locations
    }

    private val unusedList = DefaultListModel<TranslationIssue>()
    private val missingList = DefaultListModel<TranslationIssue>()
    private val incompleteList = DefaultListModel<TranslationIssue>()
    private val duplicateList = DefaultListModel<TranslationIssue>()

    private var isScanning = false
    private lateinit var statusLabel: JBLabel
    private lateinit var scanButton: JButton

    init {
        title = "Transloco Maintenance"
        setOKButtonText("Close")
        setCancelButtonText("Cancel")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, JBUI.scale(12)))
        mainPanel.preferredSize = Dimension(JBUI.scale(800), JBUI.scale(600))
        mainPanel.border = JBUI.Borders.empty(12)

        // Header with scan button
        val headerPanel = JPanel(BorderLayout(JBUI.scale(12), 0))

        val titleLabel = JBLabel("Translation Maintenance")
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 16f)
        headerPanel.add(titleLabel, BorderLayout.WEST)

        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0))
        scanButton = JButton("Scan Project")
        scanButton.addActionListener { startScan() }
        buttonPanel.add(scanButton)
        headerPanel.add(buttonPanel, BorderLayout.EAST)

        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // Tabbed pane for different issue types
        val tabbedPane = JBTabbedPane()

        tabbedPane.addTab("Unused (0)", createIssuePanel(unusedList, IssueType.UNUSED))
        tabbedPane.addTab("Missing (0)", createIssuePanel(missingList, IssueType.MISSING))
        tabbedPane.addTab("Incomplete (0)", createIssuePanel(incompleteList, IssueType.INCOMPLETE))
        tabbedPane.addTab("Duplicates (0)", createIssuePanel(duplicateList, IssueType.DUPLICATE))

        mainPanel.add(tabbedPane, BorderLayout.CENTER)

        // Status bar
        statusLabel = JBLabel("Click 'Scan Project' to analyze translations")
        statusLabel.foreground = JBColor.GRAY
        statusLabel.border = JBUI.Borders.emptyTop(8)
        mainPanel.add(statusLabel, BorderLayout.SOUTH)

        return mainPanel
    }

    private fun createIssuePanel(listModel: DefaultListModel<TranslationIssue>, type: IssueType): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.border = JBUI.Borders.empty(8)

        // Description
        val description = when (type) {
            IssueType.UNUSED -> "Keys that exist in translation files but are not used in code"
            IssueType.MISSING -> "Keys used in code but not found in translation files"
            IssueType.INCOMPLETE -> "Keys that exist in some languages but not all"
            IssueType.DUPLICATE -> "Keys that exist in multiple translation file locations"
        }
        val descLabel = JBLabel(description)
        descLabel.foreground = JBColor.GRAY
        descLabel.border = JBUI.Borders.emptyBottom(8)
        panel.add(descLabel, BorderLayout.NORTH)

        // List
        val list = JBList(listModel)
        list.cellRenderer = IssueListCellRenderer()
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

        val scrollPane = JBScrollPane(list)
        scrollPane.border = JBUI.Borders.customLine(JBColor.border(), 1)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Action buttons
        val actionPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        actionPanel.border = JBUI.Borders.emptyTop(8)

        when (type) {
            IssueType.UNUSED -> {
                val deleteButton = JButton("Delete Selected")
                deleteButton.addActionListener { deleteSelectedUnused(list) }
                actionPanel.add(deleteButton)

                val deleteAllButton = JButton("Delete All Unused")
                deleteAllButton.addActionListener { deleteAllUnused() }
                actionPanel.add(deleteAllButton)
            }
            IssueType.MISSING -> {
                val createButton = JButton("Create Selected")
                createButton.addActionListener { createSelectedMissing(list) }
                actionPanel.add(createButton)
            }
            IssueType.INCOMPLETE -> {
                val viewButton = JButton("Edit Selected")
                viewButton.addActionListener { editSelectedIncomplete(list) }
                actionPanel.add(viewButton)
            }
            IssueType.DUPLICATE -> {
                val viewButton = JButton("View Details")
                viewButton.addActionListener { viewDuplicateDetails(list) }
                actionPanel.add(viewButton)
            }
        }

        panel.add(actionPanel, BorderLayout.SOUTH)
        return panel
    }

    private fun startScan() {
        if (isScanning) return

        isScanning = true
        scanButton.isEnabled = false
        statusLabel.text = "Scanning..."

        unusedList.clear()
        missingList.clear()
        incompleteList.clear()
        duplicateList.clear()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning Translations", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false

                // Step 1: Collect all keys from JSON files
                indicator.text = "Collecting translation keys..."
                indicator.fraction = 0.1
                val jsonKeys = collectJsonKeys()

                // Step 2: Collect all keys from code
                indicator.text = "Scanning code for translation usage..."
                indicator.fraction = 0.4
                val codeKeys = collectCodeKeys(indicator)

                // Step 3: Find unused keys
                indicator.text = "Finding unused translations..."
                indicator.fraction = 0.6
                findUnusedKeys(jsonKeys, codeKeys)

                // Step 4: Find missing keys
                indicator.text = "Finding missing translations..."
                indicator.fraction = 0.7
                findMissingKeys(jsonKeys, codeKeys)

                // Step 5: Find incomplete translations
                indicator.text = "Finding incomplete translations..."
                indicator.fraction = 0.8
                findIncompleteKeys(jsonKeys)

                // Step 6: Find duplicates
                indicator.text = "Finding duplicates..."
                indicator.fraction = 0.9
                findDuplicateKeys(jsonKeys)

                indicator.fraction = 1.0
            }

            override fun onSuccess() {
                isScanning = false
                scanButton.isEnabled = true
                updateTabTitles()
                statusLabel.text = "Scan complete. Found ${unusedList.size()} unused, ${missingList.size()} missing, ${incompleteList.size()} incomplete, ${duplicateList.size()} duplicates."
            }

            override fun onCancel() {
                isScanning = false
                scanButton.isEnabled = true
                statusLabel.text = "Scan cancelled"
            }
        })
    }

    /**
     * Collect all translation keys from JSON files.
     * Returns map of (locationPath -> (language -> Set<keys>))
     */
    private fun collectJsonKeys(): Map<String, Map<String, Set<String>>> {
        val result = mutableMapOf<String, MutableMap<String, Set<String>>>()

        val allFiles = TranslationFileFinder.findAllTranslationFiles(project)
        val byLocation = allFiles.groupBy { it.parent?.path ?: "" }

        for ((location, files) in byLocation) {
            if (location.isBlank()) continue

            val locationKeys = mutableMapOf<String, Set<String>>()

            for (file in files) {
                val lang = file.nameWithoutExtension
                val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                val keys = JsonKeyNavigator.getAllKeys(psiFile).toSet()
                locationKeys[lang] = keys
            }

            if (locationKeys.isNotEmpty()) {
                result[location] = locationKeys
            }
        }

        return result
    }

    /**
     * Collect all translation keys used in code.
     */
    private fun collectCodeKeys(indicator: ProgressIndicator): Set<String> {
        val keys = mutableSetOf<String>()

        // Search in HTML files
        val htmlFiles = FilenameIndex.getAllFilesByExt(project, "html", GlobalSearchScope.projectScope(project))
        val tsFiles = FilenameIndex.getAllFilesByExt(project, "ts", GlobalSearchScope.projectScope(project))

        val allFiles = htmlFiles + tsFiles
        var processed = 0

        for (file in allFiles) {
            if (indicator.isCanceled) break

            indicator.text2 = file.name
            indicator.fraction = 0.4 + (0.2 * processed / allFiles.size.coerceAtLeast(1))

            try {
                val content = VfsUtil.loadText(file)
                for (pattern in CODE_PATTERNS) {
                    pattern.findAll(content).forEach { match ->
                        keys.add(match.groupValues[1])
                    }
                }
            } catch (e: Exception) {
                // Skip files that can't be read
            }

            processed++
        }

        return keys
    }

    private fun findUnusedKeys(jsonKeys: Map<String, Map<String, Set<String>>>, codeKeys: Set<String>) {
        for ((location, langKeys) in jsonKeys) {
            val allKeysInLocation = langKeys.values.flatten().toSet()

            for (key in allKeysInLocation) {
                // Check if key is used in code (with or without scope prefix)
                val isUsed = codeKeys.any { codeKey ->
                    codeKey == key || codeKey.endsWith(".$key") || key.endsWith(".$codeKey")
                }

                if (!isUsed) {
                    SwingUtilities.invokeLater {
                        unusedList.addElement(TranslationIssue(
                            key = key,
                            location = location,
                            type = IssueType.UNUSED
                        ))
                    }
                }
            }
        }
    }

    private fun findMissingKeys(jsonKeys: Map<String, Map<String, Set<String>>>, codeKeys: Set<String>) {
        val allJsonKeys = jsonKeys.values.flatMap { it.values.flatten() }.toSet()

        for (codeKey in codeKeys) {
            // Check if key exists in any JSON file
            val exists = allJsonKeys.any { jsonKey ->
                jsonKey == codeKey || jsonKey.endsWith(".$codeKey") || codeKey.endsWith(".$jsonKey")
            }

            if (!exists) {
                SwingUtilities.invokeLater {
                    missingList.addElement(TranslationIssue(
                        key = codeKey,
                        location = "Not found in any translation file",
                        type = IssueType.MISSING
                    ))
                }
            }
        }
    }

    private fun findIncompleteKeys(jsonKeys: Map<String, Map<String, Set<String>>>) {
        for ((location, langKeys) in jsonKeys) {
            if (langKeys.size <= 1) continue // Need at least 2 languages to compare

            val allLanguages = langKeys.keys
            val allKeysInLocation = langKeys.values.flatten().toSet()

            for (key in allKeysInLocation) {
                val missingLanguages = allLanguages.filter { lang ->
                    langKeys[lang]?.contains(key) != true
                }

                if (missingLanguages.isNotEmpty()) {
                    SwingUtilities.invokeLater {
                        incompleteList.addElement(TranslationIssue(
                            key = key,
                            location = location,
                            type = IssueType.INCOMPLETE,
                            details = "Missing in: ${missingLanguages.joinToString(", ")}"
                        ))
                    }
                }
            }
        }
    }

    private fun findDuplicateKeys(jsonKeys: Map<String, Map<String, Set<String>>>) {
        // Track which keys appear in which locations
        val keyLocations = mutableMapOf<String, MutableList<String>>()

        for ((location, langKeys) in jsonKeys) {
            val allKeysInLocation = langKeys.values.flatten().toSet()
            for (key in allKeysInLocation) {
                keyLocations.getOrPut(key) { mutableListOf() }.add(location)
            }
        }

        // Find keys that appear in multiple locations
        for ((key, locations) in keyLocations) {
            if (locations.size > 1) {
                SwingUtilities.invokeLater {
                    duplicateList.addElement(TranslationIssue(
                        key = key,
                        location = locations.first(),
                        type = IssueType.DUPLICATE,
                        details = "Also in: ${locations.drop(1).joinToString(", ") { getShortPath(it) }}"
                    ))
                }
            }
        }
    }

    private fun getShortPath(path: String): String {
        val basePath = project.basePath ?: return path
        return path.removePrefix(basePath).removePrefix("/")
            .replace("libs/", "")
            .replace("apps/", "")
            .replace("/src/assets/i18n", "")
    }

    private fun updateTabTitles() {
        val tabbedPane = (contentPanel as? JPanel)?.components?.filterIsInstance<JBTabbedPane>()?.firstOrNull()
        tabbedPane?.let {
            it.setTitleAt(0, "Unused (${unusedList.size()})")
            it.setTitleAt(1, "Missing (${missingList.size()})")
            it.setTitleAt(2, "Incomplete (${incompleteList.size()})")
            it.setTitleAt(3, "Duplicates (${duplicateList.size()})")
        }
    }

    private fun deleteSelectedUnused(list: JBList<TranslationIssue>) {
        val selected = list.selectedValuesList
        if (selected.isEmpty()) {
            Messages.showInfoMessage("Please select items to delete", "No Selection")
            return
        }

        val result = Messages.showYesNoDialog(
            project,
            "Delete ${selected.size} unused translation(s)?\n\nThis will remove the keys from all language files.",
            "Confirm Delete",
            Messages.getWarningIcon()
        )

        if (result == Messages.YES) {
            deleteTranslations(selected)
        }
    }

    private fun deleteAllUnused() {
        if (unusedList.isEmpty) {
            Messages.showInfoMessage("No unused translations to delete", "Nothing to Delete")
            return
        }

        val allItems = (0 until unusedList.size()).map { unusedList.getElementAt(it) }

        val result = Messages.showYesNoDialog(
            project,
            "Delete ALL ${allItems.size} unused translation(s)?\n\nThis will remove the keys from all language files.\nThis action cannot be undone.",
            "Confirm Delete All",
            Messages.getWarningIcon()
        )

        if (result == Messages.YES) {
            deleteTranslations(allItems)
        }
    }

    private fun deleteTranslations(issues: List<TranslationIssue>) {
        WriteCommandAction.runWriteCommandAction(project, "Delete Unused Translations", null, {
            for (issue in issues) {
                val files = TranslationFileFinder.findAllTranslationFiles(project)
                    .filter { it.parent?.path == issue.location }

                for (file in files) {
                    val psiFile = PsiManager.getInstance(project).findFile(file) as? JsonFile ?: continue
                    val navResult = JsonKeyNavigator.navigateToKey(psiFile, issue.key)
                    if (navResult.found && navResult.property != null) {
                        navResult.property.delete()
                    }
                }

                SwingUtilities.invokeLater {
                    unusedList.removeElement(issue)
                }
            }
        })

        updateTabTitles()
        Messages.showInfoMessage("Deleted ${issues.size} translation(s)", "Delete Complete")
    }

    private fun createSelectedMissing(list: JBList<TranslationIssue>) {
        val selected = list.selectedValue
        if (selected == null) {
            Messages.showInfoMessage("Please select an item to create", "No Selection")
            return
        }

        // Open the edit dialog to create the missing key
        // This reuses the existing TranslocoEditDialog infrastructure
        Messages.showInfoMessage(
            "To create this key, use Ctrl+Shift+Click on the key in your code,\nor add it manually to your translation files.\n\nKey: ${selected.key}",
            "Create Translation"
        )
    }

    private fun editSelectedIncomplete(list: JBList<TranslationIssue>) {
        val selected = list.selectedValue
        if (selected == null) {
            Messages.showInfoMessage("Please select an item to edit", "No Selection")
            return
        }

        Messages.showInfoMessage(
            "Key: ${selected.key}\nLocation: ${selected.location}\n\n${selected.details}\n\nUse the translation editor (Ctrl+Shift+Click) on this key in your code to add missing translations.",
            "Incomplete Translation"
        )
    }

    private fun viewDuplicateDetails(list: JBList<TranslationIssue>) {
        val selected = list.selectedValue
        if (selected == null) {
            Messages.showInfoMessage("Please select an item to view", "No Selection")
            return
        }

        Messages.showInfoMessage(
            "Key: ${selected.key}\n\nLocations:\n- ${selected.location}\n- ${selected.details.removePrefix("Also in: ").replace(", ", "\n- ")}",
            "Duplicate Key Details"
        )
    }

    /**
     * Custom cell renderer for issue list items.
     */
    private class IssueListCellRenderer : ListCellRenderer<TranslationIssue> {
        override fun getListCellRendererComponent(
            list: JList<out TranslationIssue>?,
            value: TranslationIssue?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
            panel.border = JBUI.Borders.empty(4, 8)

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

                // Key name
                val keyLabel = JBLabel(value.key)
                keyLabel.font = Font(Font.MONOSPACED, Font.PLAIN, keyLabel.font.size)
                if (isSelected) {
                    keyLabel.foreground = UIManager.getColor("List.selectionForeground")
                }
                leftPanel.add(keyLabel, BorderLayout.NORTH)

                // Location/details
                val detailText = if (value.details.isNotEmpty()) {
                    "${getShortPath(value.location)} - ${value.details}"
                } else {
                    getShortPath(value.location)
                }
                val detailLabel = JBLabel(detailText)
                detailLabel.font = detailLabel.font.deriveFont(detailLabel.font.size - 2f)
                detailLabel.foreground = if (isSelected) {
                    UIManager.getColor("List.selectionForeground")
                } else {
                    JBColor.GRAY
                }
                leftPanel.add(detailLabel, BorderLayout.SOUTH)

                panel.add(leftPanel, BorderLayout.CENTER)
            }

            return panel
        }

        private fun getShortPath(path: String): String {
            return path.replace(Regex(".*/libs/"), "libs/")
                .replace(Regex(".*/apps/"), "apps/")
                .replace("/src/assets/i18n", "")
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }
}
