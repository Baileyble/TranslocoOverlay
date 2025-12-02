package com.baileyble.translocooverlay

import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener

/**
 * Listener that registers mouse handlers on new editors.
 */
class TranslocoEditorFactoryListener : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        editor.addEditorMouseListener(TranslocoEditMouseListener())
    }
}
