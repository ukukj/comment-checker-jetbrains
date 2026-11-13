package dev.commentchecker.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import dev.commentchecker.CommentCheckerBundle.message
import dev.commentchecker.model.CommentHit
import dev.commentchecker.model.CommentKind
import dev.commentchecker.model.CommentStatus
import dev.commentchecker.model.FileComments
import dev.commentchecker.model.Owner
import dev.commentchecker.model.OwnerKind
import dev.commentchecker.model.ScanResult
import java.awt.Font
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/** 트리 노드 데이터. key 는 새로고침 뒤 펼침·선택 상태를 되살리는 데 씀 */
sealed interface NodeData {
	val key: String
}

data class FileNode(val file: FileComments, val count: Int, val where: String) : NodeData {
	override val key: String get() = "f:${file.filePath.path}"
}

data class OwnerNode(val owner: Owner, val count: Int, val first: CommentNode, override val key: String) : NodeData

data class GroupNode(val label: String, val count: Int, override val key: String) : NodeData

data class QuietFileNode(val path: FilePath, val status: FileStatus, val where: String) : NodeData {
	override val key: String get() = "q:${path.path}"
}

data class CommentNode(val file: FileComments, val hit: CommentHit, val flat: Boolean, override val key: String) : NodeData

const val QUIET_GROUP_KEY = "quiet"
const val SKIPPED_GROUP_KEY = "skipped"

class ViewSettings(project: Project) {
	private val props = PropertiesComponent.getInstance(project)

	var groupByStructure: Boolean
		get() = props.getBoolean("commentChecker.group", true)
		set(value) = props.setValue("commentChecker.group", value, true)

	var autoScroll: Boolean
		get() = props.getBoolean("commentChecker.autoScroll", false)
		set(value) = props.setValue("commentChecker.autoScroll", value, false)

	fun shows(kind: CommentKind): Boolean = props.getBoolean("commentChecker.kind.$kind", true)

	fun show(kind: CommentKind, value: Boolean) = props.setValue("commentChecker.kind.$kind", value, true)

	var showDeleted: Boolean
		get() = props.getBoolean("commentChecker.deleted", true)
		set(value) = props.setValue("commentChecker.deleted", value, true)

	val filtersAll: Boolean get() = CommentKind.entries.all(::shows) && showDeleted

	fun accepts(hit: CommentHit) = shows(hit.kind) && (showDeleted || hit.status != CommentStatus.DELETED)
}

/** 파일 옆 회색 경로. 소스 루트 아래 패키지 경로만, 모든 파일에 공통인 앞부분은 뺌 */
class Locations(basePath: String?, paths: List<FilePath>) {
	private class Where(val module: String, val test: Boolean, val dir: List<String>)

	private val wheres: Map<String, Where> = paths.associate { it.path to whereOf(basePath, it) }
	private val common: Int = wheres.values.map { it.dir }.filter { it.isNotEmpty() }.let { dirs ->
		if (dirs.size < 2) 0 else (0 until dirs.minOf { it.size }).takeWhile { i -> dirs.all { it[i] == dirs[0][i] } }.count()
	}

	fun of(path: FilePath): String {
		val where = wheres[path.path] ?: return ""
		val dir = where.dir.drop(common).joinToString("/")
		return listOf(where.module, if (where.test) "test" else "", dir).filter { it.isNotEmpty() }.joinToString(" · ")
	}

	private companion object {
		val SOURCE_ROOT = Regex("^(?:(.*)/)?src/(main|test)/(?:java|kotlin)(?:/(.*))?$")

		fun whereOf(basePath: String?, path: FilePath): Where {
			val parent = path.parentPath?.path.orEmpty()
			val relative = basePath?.let { FileUtil.getRelativePath(it, parent, '/') }?.takeUnless { it.startsWith("..") } ?: parent
			val m = SOURCE_ROOT.find(if (relative == ".") "" else relative)
				?: return Where("", false, relative.split('/').filter { it.isNotEmpty() && it != "." })
			val module = m.groupValues[1].takeUnless { it.startsWith("/") }.orEmpty()
			return Where(module, m.groupValues[2] == "test", m.groupValues[3].split('/').filter { it.isNotEmpty() })
		}
	}
}

class TreeBuilder(private val settings: ViewSettings, private val basePath: String?) {
	private class Bucket(val owner: Owner?) {
		val children = LinkedHashMap<String, Bucket>()
		val hits = mutableListOf<CommentNode>()
		var firstLine = Int.MAX_VALUE
		val count: Int get() = hits.size + children.values.sumOf { it.count }
		val firstHit: CommentNode get() = (hits + children.values.map { it.firstHit }).minBy { it.hit.startLine }
	}

	fun build(result: ScanResult): DefaultMutableTreeNode {
		val root = DefaultMutableTreeNode()
		val locations = Locations(basePath, result.files.map { it.filePath } + (result.quietFiles + result.skippedFiles).map { it.first })
		for (file in result.files) {
			val nodes = withKeys(file).filter { settings.accepts(it.hit) }
			if (nodes.isEmpty()) {
				continue
			}
			val fileData = FileNode(file, nodes.size, locations.of(file.filePath))
			val fileNode = DefaultMutableTreeNode(fileData)
			if (settings.groupByStructure) {
				val (gone, live) = nodes.partition { it.hit.status == CommentStatus.DELETED }
				addNested(fileNode, live, fileData.key)
				if (gone.isNotEmpty()) {
					val group = GroupNode(message("tree.group.deleted"), gone.size, "${fileData.key}/deleted")
					val groupNode = DefaultMutableTreeNode(group)
					addNested(groupNode, gone, group.key)
					fileNode.add(groupNode)
				}
			} else {
				nodes.forEach { fileNode.add(DefaultMutableTreeNode(it.copy(flat = true))) }
			}
			root.add(fileNode)
		}
		if (settings.filtersAll && result.quietFiles.isNotEmpty()) {
			root.add(fileGroup(message("tree.group.quiet"), QUIET_GROUP_KEY, result.quietFiles, locations))
		}
		// 필터와 상관없이 늘 보임. 없으면 "설치했는데 주석이 안 나온다"와 구분이 안 됨
		if (result.skippedFiles.isNotEmpty()) {
			root.add(fileGroup(message("tree.group.skipped"), SKIPPED_GROUP_KEY, result.skippedFiles, locations))
		}
		return root
	}

	private fun fileGroup(label: String, key: String, files: List<Pair<FilePath, FileStatus>>, locations: Locations): DefaultMutableTreeNode {
		val group = DefaultMutableTreeNode(GroupNode(label, files.size, key))
		files.forEach { (path, status) -> group.add(DefaultMutableTreeNode(QuietFileNode(path, status, locations.of(path)))) }
		return group
	}

	/** 줄 번호로 키를 만들면 위에 줄을 넣는 순간 선택이 풀림. 같은 문구는 등장 순번으로 구분 */
	private fun withKeys(file: FileComments): List<CommentNode> {
		val seen = HashMap<String, Int>()
		return file.comments.map { hit ->
			val base = "c:${file.filePath.path}#${hit.status}#${hit.text}"
			val n = seen.merge(base, 1, Int::plus)
			CommentNode(file, hit, flat = false, key = "$base#$n")
		}
	}

	private fun addNested(parent: DefaultMutableTreeNode, nodes: List<CommentNode>, prefix: String) {
		val root = Bucket(null)
		for (node in nodes) {
			var bucket = root
			for (owner in node.hit.owners) {
				bucket = bucket.children.getOrPut(owner.key) { Bucket(owner) }
				bucket.firstLine = minOf(bucket.firstLine, node.hit.startLine)
			}
			bucket.hits += node
		}
		emit(parent, root, prefix)
	}

	private fun emit(parent: DefaultMutableTreeNode, bucket: Bucket, prefix: String) {
		val comments = bucket.hits.map { hit -> hit.hit.startLine to { parent.add(DefaultMutableTreeNode(hit)) } }
		val owners = bucket.children.map { (k, child) ->
			child.firstLine to {
				val key = "$prefix/$k"
				val node = DefaultMutableTreeNode(OwnerNode(child.owner!!, child.count, child.firstHit, key))
				emit(node, child, key)
				parent.add(node)
			}
		}
		(comments + owners).sortedBy { it.first }.forEach { it.second() }
	}
}

class CommentsRenderer : ColoredTreeCellRenderer() {
	override fun customizeCellRenderer(
		tree: JTree,
		value: Any?,
		selected: Boolean,
		expanded: Boolean,
		leaf: Boolean,
		row: Int,
		hasFocus: Boolean,
	) {
		toolTipText = null
		when (val data = (value as? DefaultMutableTreeNode)?.userObject) {
			is FileNode -> renderFile(data.file.filePath, data.file.fileStatus, data.where, data.count)
			is QuietFileNode -> renderFile(data.path, data.status, data.where, null)
			is GroupNode -> {
				append(data.label, SimpleTextAttributes.GRAYED_ATTRIBUTES)
				append("  ${data.count}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
			}
			is OwnerNode -> {
				icon = data.owner.icon ?: iconOf(data.owner.kind)
				append(data.owner.name)
				append(data.owner.signature, SimpleTextAttributes.GRAYED_ATTRIBUTES)
				append("  ${data.count}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
				toolTipText = data.owner.name + data.owner.signature
			}
			is CommentNode -> renderComment(data)
		}
	}

	private fun renderFile(path: FilePath, status: FileStatus, where: String, count: Int?) {
		icon = FileTypeManager.getInstance().getFileTypeByFileName(path.name).icon
		append(path.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, status.color))
		if (where.isNotEmpty()) {
			append("  $where", SimpleTextAttributes.GRAYED_ATTRIBUTES)
		}
		count?.let { append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
		toolTipText = path.presentableUrl
	}

	private fun renderComment(node: CommentNode) {
		val hit = node.hit
		append("${hit.startLine + 1}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
		append("${hit.status.mark}  ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, statusColor(hit.status)))
		append("${hit.tag}  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
		append(hit.headline, commentAttributes(hit))
		if (hit.endLine > hit.startLine) {
			append("  " + message("tree.comment.lines", hit.endLine - hit.startLine + 1), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
		}
		if (node.flat && hit.owners.isNotEmpty()) {
			append("   ${hit.owners.takeLast(2).joinToString(" › ") { it.shortName }}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
		}
		toolTipText = buildString {
			append(hit.text)
			hit.oldText?.let { append("\n\n").append(message("tree.comment.before")).append("\n").append(it) }
		}.takeIf { hit.oldText != null || hit.endLine > hit.startLine }
	}

	private fun statusColor(status: CommentStatus) = when (status) {
		CommentStatus.ADDED -> FileStatus.ADDED.color
		CommentStatus.MODIFIED -> FileStatus.MODIFIED.color
		CommentStatus.DELETED -> FileStatus.DELETED.color
	}

	/** 편집기의 주석 색을 씀. 배경은 빼야 선택 강조가 안 깨짐 */
	private fun commentAttributes(hit: CommentHit): SimpleTextAttributes {
		val key = when (hit.kind) {
			CommentKind.DOC -> DefaultLanguageHighlighterColors.DOC_COMMENT
			CommentKind.BLOCK -> DefaultLanguageHighlighterColors.BLOCK_COMMENT
			CommentKind.LINE -> DefaultLanguageHighlighterColors.LINE_COMMENT
		}
		val attrs = EditorColorsManager.getInstance().schemeForCurrentUITheme.getAttributes(key)
		var style = SimpleTextAttributes.STYLE_PLAIN
		if (attrs != null && attrs.fontType and Font.ITALIC != 0) {
			style = style or SimpleTextAttributes.STYLE_ITALIC
		}
		if (hit.status == CommentStatus.DELETED) {
			style = style or SimpleTextAttributes.STYLE_STRIKEOUT
		}
		return SimpleTextAttributes(style, attrs?.foregroundColor)
	}

	companion object {
		fun iconOf(kind: OwnerKind): Icon = when (kind) {
			OwnerKind.CLASS -> AllIcons.Nodes.Class
			OwnerKind.INTERFACE -> AllIcons.Nodes.Interface
			OwnerKind.ENUM -> AllIcons.Nodes.Enum
			OwnerKind.ANNOTATION -> AllIcons.Nodes.Annotationtype
			OwnerKind.RECORD -> AllIcons.Nodes.Record
			OwnerKind.OBJECT -> AllIcons.Nodes.Static
			OwnerKind.FUNCTION, OwnerKind.CONSTRUCTOR, OwnerKind.ACCESSOR -> AllIcons.Nodes.Method
			OwnerKind.INITIALIZER -> AllIcons.Nodes.ClassInitializer
			OwnerKind.PROPERTY -> AllIcons.Nodes.Property
			OwnerKind.FIELD -> AllIcons.Nodes.Field
			OwnerKind.ENTRY -> AllIcons.Nodes.Constant
			OwnerKind.TYPEALIAS -> AllIcons.Nodes.Class
			OwnerKind.OTHER -> AllIcons.Nodes.Tag
		}
	}
}
