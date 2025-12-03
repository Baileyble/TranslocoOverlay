package com.baileyble.translocooverlay

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import javax.swing.JList

/**
 * Finds usages of JSON translation keys in HTML/TypeScript templates.
 * Triggered by Ctrl+Shift+Click on JSON keys in translation files.
 */
object TranslocoJsonUsagesFinder {

    private val LOG = Logger.getInstance(TranslocoJsonUsagesFinder::class.java)

    data class UsageInfo(
        val element: PsiElement,
        val fileName: String,
        val lineNumber: Int,
        val contextSnippet: String,
        val virtualFile: VirtualFile?
    )

    /**
     * Find usages of the JSON key at the given element in templates.
     */
    fun findUsages(project: Project, element: PsiElement) {
        // Run in background to avoid EDT slow operation errors
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Finding usages...", false) {
            private var usages: List<UsageInfo> = emptyList()
            private var keyPath: String = ""

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                ReadAction.run<Throwable> {
                    val jsonProperty = getJsonProperty(element)
                    if (jsonProperty == null) {
                        LOG.debug("TRANSLOCO-JSON: Not a JSON property")
                        return@run
                    }

                    keyPath = buildKeyPath(jsonProperty)
                    if (keyPath.isBlank()) {
                        LOG.debug("TRANSLOCO-JSON: Empty key path")
                        return@run
                    }

                    val sourceFile = element.containingFile?.virtualFile
                    if (sourceFile == null) {
                        LOG.debug("TRANSLOCO-JSON: No source file")
                        return@run
                    }

                    val scopePrefix = getScopeFromFilePath(sourceFile.path)
                    LOG.debug("TRANSLOCO-JSON: Finding usages for '$keyPath' (scope: $scopePrefix)")

                    usages = searchForUsages(project, keyPath, scopePrefix, sourceFile)
                }
            }

            override fun onSuccess() {
                if (keyPath.isBlank()) return

                when {
                    usages.isEmpty() -> showNoUsagesMessage(project, keyPath)
                    usages.size == 1 -> navigateToUsage(project, usages[0])
                    else -> showUsagesPopup(project, keyPath, usages)
                }
            }
        })
    }

    private fun searchForUsages(
        project: Project,
        keyPath: String,
        scopePrefix: String?,
        sourceFile: VirtualFile
    ): List<UsageInfo> {
        val usageInfos = mutableListOf<UsageInfo>()

        // Build search keys
        val searchKeys = mutableSetOf(keyPath)
        if (scopePrefix != null) {
            searchKeys.add("$scopePrefix.$keyPath")
        }

        // Search HTML files
        val htmlFiles = FilenameIndex.getAllFilesByExt(project, "html", GlobalSearchScope.projectScope(project))
        for (htmlFile in htmlFiles) {
            if (htmlFile.path == sourceFile.path) continue
            val psiFile = PsiManager.getInstance(project).findFile(htmlFile) ?: continue
            collectUsages(psiFile, searchKeys, htmlFile, usageInfos)
        }

        // Search TypeScript files
        val tsFiles = FilenameIndex.getAllFilesByExt(project, "ts", GlobalSearchScope.projectScope(project))
        for (tsFile in tsFiles) {
            if (tsFile.path == sourceFile.path) continue
            val psiFile = PsiManager.getInstance(project).findFile(tsFile) ?: continue
            collectUsages(psiFile, searchKeys, tsFile, usageInfos)
        }

        LOG.debug("TRANSLOCO-JSON: Found ${usageInfos.size} usages")
        return usageInfos
    }

    private fun collectUsages(
        psiFile: PsiFile,
        searchKeys: Set<String>,
        virtualFile: VirtualFile,
        usageInfos: MutableList<UsageInfo>
    ) {
        val text = psiFile.text
        val processedOffsets = mutableSetOf<Int>()

        for (searchKey in searchKeys) {
            if (!text.contains(searchKey)) continue

            var index = 0
            while (true) {
                val foundIndex = text.indexOf(searchKey, index)
                if (foundIndex < 0) break

                if (foundIndex !in processedOffsets) {
                    processedOffsets.add(foundIndex)

                    val element = psiFile.findElementAt(foundIndex)
                    if (element != null) {
                        val lineNumber = text.substring(0, foundIndex).count { it == '\n' } + 1
                        val lineStart = text.lastIndexOf('\n', foundIndex).let { if (it < 0) 0 else it + 1 }
                        val lineEnd = text.indexOf('\n', foundIndex).let { if (it < 0) text.length else it }
                        val contextSnippet = text.substring(lineStart, lineEnd).trim()

                        usageInfos.add(UsageInfo(
                            element = element,
                            fileName = virtualFile.name,
                            lineNumber = lineNumber,
                            contextSnippet = contextSnippet,
                            virtualFile = virtualFile
                        ))
                    }
                }
                index = foundIndex + 1
            }
        }
    }

    private fun getJsonProperty(element: PsiElement): JsonProperty? {
        var current: PsiElement? = element
        var depth = 0
        while (current != null && depth < 10) {
            if (current is JsonProperty) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun buildKeyPath(property: JsonProperty): String {
        val parts = mutableListOf<String>()
        var current: PsiElement? = property

        while (current != null) {
            if (current is JsonProperty) {
                parts.add(0, current.name)
            }
            current = current.parent
            if (current is JsonFile || current == null) break
        }

        return parts.joinToString(".")
    }

    private fun getScopeFromFilePath(path: String): String? {
        val commonFolders = listOf("assets", "src", "app", "libs", "apps", "i18n", "locale", "translations", "external")
        val parts = path.replace("\\", "/").split("/").filter { it.isNotBlank() }

        val i18nIndex = parts.indexOfFirst { it.equals("i18n", ignoreCase = true) }
        if (i18nIndex > 0) {
            for (i in i18nIndex - 1 downTo 0) {
                val folder = parts[i]
                if (folder.lowercase() !in commonFolders && !folder.contains(".")) {
                    return folder
                }
            }
        }
        return null
    }

    private fun navigateToUsage(project: Project, usage: UsageInfo) {
        val virtualFile = usage.virtualFile ?: return
        val descriptor = OpenFileDescriptor(project, virtualFile, usage.element.textOffset)
        FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
    }

    private fun showNoUsagesMessage(project: Project, keyPath: String) {
        ApplicationManager.getApplication().invokeLater {
            val statusBar = WindowManager.getInstance().getStatusBar(project)
            val component = statusBar?.component ?: return@invokeLater
            JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder("No usages found for '$keyPath'", MessageType.INFO, null)
                .setFadeoutTime(3000)
                .createBalloon()
                .show(RelativePoint.getCenterOf(component), Balloon.Position.atRight)
        }
    }

    private fun showUsagesPopup(project: Project, keyPath: String, usages: List<UsageInfo>) {
        ApplicationManager.getApplication().invokeLater {
            val popup = JBPopupFactory.getInstance()
                .createPopupChooserBuilder(usages)
                .setTitle("Usages of '$keyPath'")
                .setRenderer(object : ColoredListCellRenderer<UsageInfo>() {
                    override fun customizeCellRenderer(
                        list: JList<out UsageInfo>,
                        value: UsageInfo?,
                        index: Int,
                        selected: Boolean,
                        hasFocus: Boolean
                    ) {
                        if (value == null) return
                        append(value.fileName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append(":${value.lineNumber}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        val snippet = value.contextSnippet.take(60)
                        append(snippet, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        if (value.contextSnippet.length > 60) {
                            append("...", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }
                })
                .setItemChosenCallback { selected ->
                    navigateToUsage(project, selected)
                }
                .setNamerForFiltering { it.fileName + " " + it.contextSnippet }
                .createPopup()

            popup.showInFocusCenter()
        }
    }
}
