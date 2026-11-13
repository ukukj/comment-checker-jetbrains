package dev.commentchecker.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.vcsUtil.VcsUtil

class ShowChangedCommentsAction : DumbAwareAction() {
	override fun getActionUpdateThread() = ActionUpdateThread.BGT

	override fun update(e: AnActionEvent) {
		e.presentation.isEnabledAndVisible = e.project != null
	}

	override fun actionPerformed(e: AnActionEvent) {
		val project = e.project ?: return
		// Unversioned 파일은 Change 가 아니라 CHANGES 에 안 들어 있음
		val target = e.getData(VcsDataKeys.CHANGES)?.firstOrNull()?.let(ChangesUtil::getFilePath)
			?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let(VcsUtil::getFilePath)
		CommentsPanel.show(project, target)
	}
}
