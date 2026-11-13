package dev.commentchecker.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentManager
import com.intellij.pom.Navigatable
import com.intellij.ui.AutoScrollToSourceHandler
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.EditSourceOnEnterKeyHandler
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.update.MergingUpdateQueue
import com.intellij.util.ui.update.Update
import dev.commentchecker.CommentCheckerBundle.message
import dev.commentchecker.core.ChangedCommentsCollector
import dev.commentchecker.model.CommentKind
import dev.commentchecker.model.CommentStatus
import dev.commentchecker.model.ScanResult
import org.jetbrains.annotations.TestOnly
import java.awt.BorderLayout
import java.awt.Component
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.ToolTipManager
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

const val TAB_NAME = "Comments"

private val LOG = logger<CommentsPanel>()

class CommentsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable, UiDataProvider {
	private val settings = ViewSettings(project)
	private val model = DefaultTreeModel(DefaultMutableTreeNode())
	val tree = Tree(model)
	private val summary = JBLabel()
	private val collector = ChangedCommentsCollector(project)

	/** 탭이 보일 때만 돌도록 activationComponent 까지 this. 입력 중에는 멈췄다가 한 번만 돌도록 타이머 재시작 */
	private val queue = MergingUpdateQueue("CommentChecker", 400, true, this, this, this).setRestartTimerOnAdd(true)
	private val generation = AtomicInteger()

	@Volatile
	private var running: ProgressIndicator? = null

	@Volatile
	private var disposed = false
	private var result: ScanResult? = null
	private val collapsed = mutableSetOf(QUIET_GROUP_KEY, SKIPPED_GROUP_KEY)
	private var selectedKey: String? = null
	private var pendingFile: FilePath? = null

	/** 트리를 다시 그리는 중. 이때 생기는 선택·펼침 이벤트는 사용자 조작이 아님 */
	private var rebuilding = false

	private val autoScroll = object : AutoScrollToSourceHandler() {
		override fun isAutoScrollMode() = settings.autoScroll && !rebuilding

		override fun setAutoScrollMode(state: Boolean) {
			settings.autoScroll = state
		}

		override fun scrollToSource(tree: Component) {
			if (selectedData()?.let(::opensDiff) == true) {
				return
			}
			super.scrollToSource(tree)
		}
	}

	init {
		tree.isRootVisible = false
		tree.showsRootHandles = true
		tree.cellRenderer = CommentsRenderer()
		tree.emptyText.text = message("panel.loading")
		TreeSpeedSearch.installOn(tree, false) { path -> searchTextOf(path) }
		ToolTipManager.sharedInstance().registerComponent(tree)
		EditSourceOnDoubleClickHandler.install(tree)
		EditSourceOnEnterKeyHandler.install(tree)
		autoScroll.install(tree)
		tree.addTreeSelectionListener {
			if (!rebuilding) {
				selectedKey = selectedData()?.key
			}
		}
		tree.addTreeExpansionListener(object : TreeExpansionListener {
			override fun treeExpanded(event: TreeExpansionEvent) {
				if (rebuilding) {
					return
				}
				keyOf(event.path)?.let(collapsed::remove)
				// 접혀 있던 노드를 펼치면 그 아래는 기억한 상태대로. 안 하면 다음 새로고침 때 갑자기 펼쳐짐
				(event.path.lastPathComponent as? DefaultMutableTreeNode)?.let { node ->
					withRebuilding { node.children().asSequence().forEach { expandRemembered(it as DefaultMutableTreeNode) } }
				}
			}

			override fun treeCollapsed(event: TreeExpansionEvent) {
				if (!rebuilding) {
					keyOf(event.path)?.let(collapsed::add)
				}
			}
		})
		PopupHandler.installPopupMenu(tree, popupActions(), "CommentCheckerPopup")

		summary.foreground = UIUtil.getContextHelpForeground()
		summary.border = JBUI.Borders.emptyLeft(8)
		val toolbar = ActionManager.getInstance().createActionToolbar("CommentChecker", toolbarActions(), true)
		toolbar.targetComponent = this
		setToolbar(JPanel(BorderLayout()).apply {
			add(toolbar.component, BorderLayout.WEST)
			add(summary, BorderLayout.CENTER)
		})
		setContent(ScrollPaneFactory.createScrollPane(tree, true))

		ChangeListManager.getInstance(project).addChangeListListener(object : ChangeListListener {
			override fun changeListUpdateDone() = scheduleRefresh()
		}, this)
		// 인덱싱 중에는 언어 플러그인이 선언 범위를 못 알려 줘 묶음이 틀릴 수 있음. 끝나면 다시 읽음
		project.messageBus.connect(this).subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
			override fun exitDumbMode() = scheduleRefresh()
		})
		// 이미 Commit 창에 오른 파일을 계속 고칠 때는 목록이 안 바뀌어 위 이벤트가 안 올 수 있음
		EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
			override fun documentChanged(event: DocumentEvent) {
				val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
				if (ChangedCommentsCollector.languageOf(file.fileType) == null) {
					return
				}
				val status = ChangeListManager.getInstance(project).getStatus(file)
				if (status != FileStatus.NOT_CHANGED && status != FileStatus.IGNORED) {
					scheduleRefresh()
				}
			}
		}, this)
		scheduleRefresh()
	}

	fun scheduleRefresh() {
		queue.queue(Update.create("refresh") { startRefresh() })
	}

	private fun startRefresh() {
		val gen = generation.incrementAndGet()
		running?.cancel()
		val indicator = EmptyProgressIndicator()
		running = indicator
		ApplicationManager.getApplication().executeOnPooledThread {
			val scanned = try {
				ProgressManager.getInstance().runProcess<ScanResult>({ collector.collect(indicator) }, indicator)
			} catch (e: ProcessCanceledException) {
				return@executeOnPooledThread
			} catch (e: Throwable) {
				// Error 까지 받아야 패널이 "불러오는 중"에 멈추지 않음
				LOG.warn("Failed to collect changed comments", e)
				ScanResult(emptyList(), emptyList(), listOf(message("error.collect", e.message ?: e.javaClass.simpleName)))
			}
			ApplicationManager.getApplication().invokeLater(
				{ if (!disposed && gen == generation.get()) apply(scanned) },
				ModalityState.any(),
			)
		}
	}

	private fun apply(scanned: ScanResult) {
		if (scanned == result) {
			return
		}
		result = scanned
		rebuild()
	}

	@TestOnly
	fun showForTest(scanned: ScanResult) = apply(scanned)

	private fun rebuild() {
		val scanned = result ?: return
		val root = TreeBuilder(settings, project.basePath).build(scanned)
		val viewport = UIUtil.getParentOfType(JViewport::class.java, tree)
		val position = viewport?.viewPosition
		// setRoot 이 선택을 지우고, 그 이벤트로 기억한 선택까지 지워지지 않도록 rebuilding 안에서
		val jumped = withRebuilding {
			model.setRoot(root)
			expandRemembered(root)
			restoreSelection(root)
		}
		if (!jumped && position != null) {
			viewport.viewPosition = position
		}

		val counts = arrayOf<Any>(scanned.commentCount, scanned.files.size, scanned.changedFileCount)
		summary.text = if (scanned.errors.isEmpty()) {
			message("panel.summary", *counts)
		} else {
			message("panel.summary.withErrors", *counts, scanned.errors.size)
		}
		summary.toolTipText = scanned.errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
		tree.emptyText.text = when {
			scanned.changedFileCount == 0 -> message("panel.empty.noFiles")
			scanned.commentCount == 0 -> message("panel.empty.noComments")
			else -> message("panel.empty.filtered")
		}
	}

	/** 접어 둔 노드만 빼고 펼침. 기본값이면 펼칠 때마다 그 노드로 스크롤해서 목록이 튀니 스크롤은 끔 */
	private fun expandRemembered(node: DefaultMutableTreeNode) {
		val key = (node.userObject as? NodeData)?.key
		if (node.childCount == 0 || (key != null && key in collapsed)) {
			return
		}
		val scrolls = tree.scrollsOnExpand
		tree.scrollsOnExpand = false
		try {
			tree.expandPath(TreePath(node.path))
		} finally {
			tree.scrollsOnExpand = scrolls
		}
		node.children().asSequence().forEach { expandRemembered(it as DefaultMutableTreeNode) }
	}

	private fun <T> withRebuilding(action: () -> T): T {
		val outer = rebuilding
		rebuilding = true
		try {
			return action()
		} finally {
			rebuilding = outer
		}
	}

	/** 새로고침 때는 선택만 되살리고 스크롤은 그대로. Commit 창에서 넘어온 파일일 때만 그리로 스크롤 */
	private fun restoreSelection(root: DefaultMutableTreeNode): Boolean {
		val wantFile = pendingFile
		// 트리에 없는 파일(지원 안 하는 언어, 필터로 다 가려짐)이면 계속 기다리다 나중에 엉뚱하게 튀므로 한 번만 시도
		pendingFile = null
		val targets = wantFile?.let { listOf("f:${it.path}", "q:${it.path}") } ?: listOfNotNull(selectedKey)
		val node = root.depthFirstEnumeration().asSequence()
			.map { it as DefaultMutableTreeNode }
			.firstOrNull { (it.userObject as? NodeData)?.key in targets }
			?: return false
		val path = TreePath(node.path)
		tree.selectionPath = path
		if (wantFile == null) {
			return false
		}
		selectedKey = (node.userObject as NodeData).key
		// rebuilding 중이라 펼침이 기억되지 않음. 안 지우면 다른 노드를 고른 뒤 새로고침 때 도로 접힘
		path.path.mapNotNull { ((it as? DefaultMutableTreeNode)?.userObject as? NodeData)?.key }.forEach(collapsed::remove)
		tree.expandPath(path.parentPath)
		tree.scrollPathToVisible(path)
		return true
	}

	fun focusFile(path: FilePath?) {
		pendingFile = path
		val root = model.root as? DefaultMutableTreeNode ?: return
		if (result != null) {
			withRebuilding { restoreSelection(root) }
		}
	}

	private fun searchTextOf(path: TreePath): String = when (val data = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
		is FileNode -> data.file.filePath.name
		is QuietFileNode -> data.path.name
		is OwnerNode -> data.owner.name
		is GroupNode -> data.label
		is CommentNode -> data.hit.text
		else -> ""
	}

	private fun keyOf(path: TreePath?) = ((path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? NodeData)?.key

	private fun selectedData(): NodeData? = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? NodeData

	override fun uiDataSnapshot(sink: DataSink) {
		super.uiDataSnapshot(sink)
		val data = selectedData() ?: return
		changeOf(data)?.let { sink.set(VcsDataKeys.CHANGES, arrayOf(it)) }
		// 2026.1 은 클릭 때 읽기 권한 없이 부름. 파일·문서를 읽는 건 lazy 로 넘겨야 플랫폼이 읽기 권한 안에서 계산
		sink.lazy(CommonDataKeys.NAVIGATABLE) { navigatableOf(data) }
	}

	private fun changeOf(data: NodeData): Change? = when (data) {
		is FileNode -> data.file.change
		is CommentNode -> data.file.change
		is OwnerNode -> data.first.file.change
		else -> null
	}

	/** 삭제된 주석·파일은 현재 파일에 없어 이동하면 Diff 가 열림. 선택만으로 Diff 창이 뜨지 않도록 자동 이동에서 뺌 */
	private fun opensDiff(data: NodeData): Boolean = when (data) {
		is CommentNode -> data.hit.status == CommentStatus.DELETED || data.file.fileStatus == FileStatus.DELETED
		is OwnerNode -> opensDiff(data.first)
		is FileNode -> data.file.fileStatus == FileStatus.DELETED
		else -> false
	}

	private fun navigatableOf(data: NodeData): Navigatable? {
		val comment = when (data) {
			is CommentNode -> data
			is OwnerNode -> data.first
			is FileNode -> return data.file.filePath.virtualFile?.let { OpenFileDescriptor(project, it) } ?: diffNavigatable(data.file.change)
			is QuietFileNode -> return data.path.virtualFile?.let { OpenFileDescriptor(project, it) }
			is GroupNode -> return null
		}
		val file = comment.file.filePath.virtualFile
		if (comment.hit.status == CommentStatus.DELETED || file == null) {
			return diffNavigatable(comment.file.change)
		}
		// OpenFileDescriptor 의 column 은 탭을 펼친 논리 열이라 글자 수로 주면 어긋남. 오프셋으로
		val document = FileDocumentManager.getInstance().getDocument(file)
		if (document == null || comment.hit.startLine >= document.lineCount) {
			return OpenFileDescriptor(project, file, comment.hit.startLine, 0)
		}
		val lineStart = document.getLineStartOffset(comment.hit.startLine)
		val offset = minOf(lineStart + comment.hit.column, document.getLineEndOffset(comment.hit.startLine))
		return OpenFileDescriptor(project, file, offset)
	}

	private fun diffNavigatable(change: Change?): Navigatable? {
		change ?: return null
		return object : Navigatable {
			override fun navigate(requestFocus: Boolean) = ShowDiffAction.showDiffForChange(project, listOf(change))

			override fun canNavigate() = true

			override fun canNavigateToSource() = true
		}
	}

	/** 자체 버튼은 BGT. EDT 로 두면 화면 스레드가 바쁠 때 IDE 툴바와 같이 기다림 */
	internal fun toolbarActions(): DefaultActionGroup {
		val expander = DefaultTreeExpander(tree)
		val common = CommonActionsManager.getInstance()
		return group().apply {
			add(object : DumbAwareAction(message("action.refresh.text"), message("action.refresh.description"), AllIcons.Actions.Refresh) {
				override fun getActionUpdateThread() = ActionUpdateThread.BGT

				override fun actionPerformed(e: AnActionEvent) = startRefresh()
			})
			addSeparator()
			add(common.createExpandAllAction(expander, tree))
			add(common.createCollapseAllAction(expander, tree))
			addSeparator()
			add(toggle(message("action.groupByStructure.text"), AllIcons.Actions.GroupByClass, { settings.groupByStructure }) { settings.groupByStructure = it })
			add(group(message("action.commentTypes.text"), popup = true).apply {
				templatePresentation.icon = AllIcons.General.Filter
				add(toggle(message("action.commentType.doc.text"), null, { settings.shows(CommentKind.DOC) }) { settings.show(CommentKind.DOC, it) })
				add(toggle(message("action.commentType.line.text"), null, { settings.shows(CommentKind.LINE) }) { settings.show(CommentKind.LINE, it) })
				add(toggle(message("action.commentType.block.text"), null, { settings.shows(CommentKind.BLOCK) }) { settings.show(CommentKind.BLOCK, it) })
				addSeparator()
				add(toggle(message("action.deletedComments.text"), null, { settings.showDeleted }) { settings.showDeleted = it })
			})
			add(autoScroll.createToggleAction())
		}
	}

	internal fun popupActions() = group().apply {
		ActionManager.getInstance().getAction(IdeActions.ACTION_EDIT_SOURCE)?.let(::add)
		add(object : DumbAwareAction(message("action.showDiff.text"), null, AllIcons.Actions.Diff) {
			override fun getActionUpdateThread() = ActionUpdateThread.BGT

			// 트리 선택(Swing)을 직접 읽지 않고 uiDataSnapshot 이 넘긴 변경을 씀. 그래야 BGT 에서 읽을 수 있음
			override fun update(e: AnActionEvent) {
				e.presentation.isEnabled = !e.getData(VcsDataKeys.CHANGES).isNullOrEmpty()
			}

			override fun actionPerformed(e: AnActionEvent) {
				e.getData(VcsDataKeys.CHANGES)?.takeIf { it.isNotEmpty() }?.let { ShowDiffAction.showDiffForChange(project, it.toList()) }
			}
		})
	}

	private fun group(name: String? = null, popup: Boolean = false) = object : DefaultActionGroup(name, popup) {
		override fun getActionUpdateThread() = ActionUpdateThread.BGT
	}

	private fun toggle(text: String, icon: Icon?, get: () -> Boolean, set: (Boolean) -> Unit) =
		object : DumbAwareToggleAction(text, null, icon) {
			// 설정 저장소(PropertiesComponent)는 아무 스레드에서나 읽어도 됨
			override fun getActionUpdateThread() = ActionUpdateThread.BGT

			override fun isSelected(e: AnActionEvent) = get()

			override fun setSelected(e: AnActionEvent, state: Boolean) {
				set(state)
				rebuild()
			}
		}

	override fun dispose() {
		disposed = true
		running?.cancel()
	}

	companion object {
		fun show(project: Project, target: FilePath?) {
			val toolWindow = ChangesViewContentManager.getToolWindowFor(project, TAB_NAME) ?: return
			toolWindow.activate {
				val manager = ChangesViewContentManager.getInstance(project)
				manager.selectContent(TAB_NAME)
				(manager.findContent(TAB_NAME)?.component as? CommentsPanel)?.focusFile(target)
			}
		}
	}
}
