package dev.commentchecker.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider
import com.intellij.ui.content.Content

class CommentsTabProvider(private val project: Project) : ChangesViewContentProvider {
	override fun initTabContent(content: Content) {
		val panel = CommentsPanel(project)
		content.component = panel
		content.preferredFocusableComponent = panel.tree
		content.setDisposer(panel)
	}
}
