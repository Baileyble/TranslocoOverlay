package com.baileyble.translocooverlay

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import java.util.WeakHashMap

/**
 * Listener that registers mouse handlers on new editors.
 * Uses WeakHashMap to track listeners and properly clean up when editors are released.
 */
class TranslocoEditorFactoryListener : EditorFactoryListener {

    companion object {
        // Use WeakHashMap so entries are automatically removed when editors are garbage collected
        private val editorListeners = WeakHashMap<Editor, TranslocoEditMouseListener>()
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val listener = TranslocoEditMouseListener()
        editorListeners[editor] = listener
        editor.addEditorMouseListener(listener)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        val listener = editorListeners.remove(editor)
        if (listener != null) {
            editor.removeEditorMouseListener(listener)
        }
    }
}
