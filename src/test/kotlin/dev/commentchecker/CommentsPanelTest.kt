package dev.commentchecker

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataMap
import com.intellij.openapi.actionSystem.DataProvider
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DataSnapshotProvider
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.commentchecker.core.ChangedCommentsCollector
import dev.commentchecker.model.FileComments
import dev.commentchecker.model.ScanResult
import dev.commentchecker.ui.CommentNode
import dev.commentchecker.ui.CommentsPanel
import dev.commentchecker.ui.FileNode
import dev.commentchecker.ui.GroupNode
import dev.commentchecker.ui.NodeData
import dev.commentchecker.ui.QUIET_GROUP_KEY
import org.jetbrains.kotlin.idea.KotlinLanguage
import java.io.File
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class CommentsPanelTest : BasePlatformTestCase() {
	private fun result(vararg files: Pair<String, String>): ScanResult {
		val comments = files.map { (name, text) ->
			val hits = ChangedCommentsCollector.commentChanges(project, name, KotlinLanguage.INSTANCE, null, text, EmptyProgressIndicator())
			FileComments(LocalFilePath("/repo/src/main/kotlin/a/$name", false), null, FileStatus.ADDED, hits)
		}
		return ScanResult(comments, emptyList(), emptyList())
	}

	private fun panel(): CommentsPanel = CommentsPanel(project).also { Disposer.register(testRootDisposable, it) }

	private fun nodes(panel: CommentsPanel) = (panel.tree.model.root as DefaultMutableTreeNode).depthFirstEnumeration().asSequence()
		.map { it as DefaultMutableTreeNode }
		.toList()

	private fun selected(panel: CommentsPanel) =
		(panel.tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? NodeData

	fun testSelectionSurvivesRefreshEvenWhenLinesShift() {
		val panel = panel()
		panel.showForTest(result("A.kt" to "class A {\n\t// target\n\tfun f() {}\n}\n"))
		val target = nodes(panel).first { (it.userObject as? CommentNode)?.hit?.text == "// target" }
		panel.tree.selectionPath = TreePath(target.path)

		panel.showForTest(result("A.kt" to "class A {\n\t// 위에 새로 쓴 주석\n\tval x = 1\n\n\t// target\n\tfun f() {}\n}\n"))

		val now = selected(panel) as? CommentNode
		assertEquals("// target", now?.hit?.text)
		assertEquals(4, now?.hit?.startLine)
	}

	fun testFocusOnMissingFileDoesNotJumpLater() {
		val panel = panel()
		panel.showForTest(result("A.kt" to "// a\nclass A\n"))
		panel.focusFile(LocalFilePath("/repo/src/main/kotlin/a/B.kt", false))

		panel.showForTest(result("A.kt" to "// a\nclass A\n", "B.kt" to "// b\nclass B\n"))

		assertNull(selected(panel))
	}

	fun testFocusSelectsFileNode() {
		val panel = panel()
		panel.showForTest(result("A.kt" to "// a\nclass A\n", "B.kt" to "// b\nclass B\n"))
		panel.focusFile(LocalFilePath("/repo/src/main/kotlin/a/B.kt", false))
		assertEquals("B.kt", (selected(panel) as? FileNode)?.file?.filePath?.name)
	}

	fun testCollapsedFileStaysCollapsedAfterRefresh() {
		val panel = panel()
		panel.showForTest(result("A.kt" to "// a\nclass A\n"))
		val file = nodes(panel).first { it.userObject is FileNode }
		panel.tree.collapsePath(TreePath(file.path))

		panel.showForTest(result("A.kt" to "// a\n// a2\nclass A\n"))

		val again = nodes(panel).first { it.userObject is FileNode }
		assertFalse(panel.tree.isExpanded(TreePath(again.path)))
	}

	/** 접힌 묶음 안 파일로 이동해 펼친 뒤 다른 곳을 골라도, 새로고침 때 묶음이 도로 접히지 않도록 */
	fun testGroupOpenedByFocusStaysOpenAfterRefresh() {
		val quiet = LocalFilePath("/repo/src/main/kotlin/a/Q.kt", false)
		fun scan(text: String) = result("A.kt" to text).copy(quietFiles = listOf(quiet to FileStatus.MODIFIED))
		val panel = panel()
		panel.showForTest(scan("// a\nclass A\n"))
		panel.focusFile(quiet)
		val comment = nodes(panel).first { it.userObject is CommentNode }
		panel.tree.selectionPath = TreePath(comment.path)

		panel.showForTest(scan("// a\n// a2\nclass A\n"))

		val group = nodes(panel).first { (it.userObject as? GroupNode)?.key == QUIET_GROUP_KEY }
		assertTrue(panel.tree.isExpanded(TreePath(group.path)))
	}

	/** 자체 버튼이 EDT 로 남으면 화면 스레드가 바쁠 때 툴바가 같이 기다림. IDE 제공 버튼(펼치기 등)은 원래 EDT 라 뺌 */
	fun testOwnActionsUpdateInBackground() {
		val panel = panel()
		val own = (actionsIn(panel.toolbarActions()) + actionsIn(panel.popupActions()))
			.filter { it.javaClass.name.startsWith("dev.commentchecker") }
		assertTrue(own.size >= 9)
		for (action in own) {
			assertEquals(action.javaClass.name, ActionUpdateThread.BGT, action.actionUpdateThread)
		}
	}

	private fun actionsIn(group: DefaultActionGroup): List<AnAction> =
		listOf<AnAction>(group) + group.childActionsOrStubs.flatMap { if (it is DefaultActionGroup) actionsIn(it) else listOf(it) }

	/** 2026.1 은 트리 클릭 때 읽기 권한 없이 uiDataSnapshot 을 부름. 테스트의 EDT 는 권한이 있어 백그라운드 스레드로 재현 */
	fun testDataSnapshotDoesNotReadDocumentWithoutReadAccess() {
		val dir = FileUtil.createTempDirectory("comment-checker", null, true)
		VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
		val text = "class A {\n\t// target\n\tfun f() {}\n}\n"
		val io = File(dir, "A.kt").apply { writeText(text) }
		val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)!!
		val hits = ChangedCommentsCollector.commentChanges(project, "A.kt", KotlinLanguage.INSTANCE, null, text, EmptyProgressIndicator())
		val panel = panel()
		panel.showForTest(ScanResult(listOf(FileComments(LocalFilePath(io.path, false), null, FileStatus.ADDED, hits)), emptyList(), emptyList()))
		val target = nodes(panel).first { (it.userObject as? CommentNode)?.hit?.text == "// target" }
		panel.tree.selectionPath = TreePath(target.path)

		val sink = RecordingSink()
		ApplicationManager.getApplication().executeOnPooledThread { panel.uiDataSnapshot(sink) }.get()

		val navigatable = ApplicationManager.getApplication().runReadAction<Any?> { sink.evaluate(CommonDataKeys.NAVIGATABLE) }
		assertEquals(file, (navigatable as OpenFileDescriptor).file)
		assertEquals(text.indexOf("// target"), navigatable.offset)
	}

	private class RecordingSink : DataSink {
		private val values = mutableMapOf<DataKey<*>, Any?>()
		private val lazies = mutableMapOf<DataKey<*>, (DataMap) -> Any?>()

		fun evaluate(key: DataKey<*>): Any? = if (key in values) values[key] else lazies[key]?.invoke(EMPTY)

		override fun <T : Any> set(key: DataKey<T>, data: T?) {
			values[key] = data
		}

		override fun <T : Any> setNull(key: DataKey<T>) {
			values[key] = null
		}

		override fun <T : Any> lazyValue(key: DataKey<T>, data: (DataMap) -> T?) {
			lazies[key] = data
		}

		override fun <T : Any> lazyNull(key: DataKey<T>) {
			lazies[key] = { null }
		}

		override fun uiDataSnapshot(provider: UiDataProvider) = provider.uiDataSnapshot(this)

		override fun dataSnapshot(provider: DataSnapshotProvider) = provider.dataSnapshot(this)

		override fun uiDataSnapshot(provider: DataProvider) = Unit

		companion object {
			private val EMPTY = object : DataMap {
				override fun <T : Any> get(key: DataKey<T>): T? = null
			}
		}
	}
}
