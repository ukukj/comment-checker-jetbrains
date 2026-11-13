package dev.commentchecker.model

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import javax.swing.Icon

enum class CommentKind {
	DOC, LINE, BLOCK,
}

enum class CommentStatus(val mark: String) {
	ADDED("+"),
	MODIFIED("~"),
	DELETED("−"),
}

enum class OwnerKind {
	CLASS, INTERFACE, ENUM, ANNOTATION, RECORD, OBJECT,
	FUNCTION, CONSTRUCTOR, ACCESSOR, INITIALIZER,
	PROPERTY, FIELD, ENTRY, TYPEALIAS,

	/** 언어 전용 규칙 없이 브레드크럼으로 찾은 선언. 아이콘은 Owner.icon */
	OTHER,
}

/**
 * 주석이 달린 선언 한 단계. signature 는 이름 뒤에 회색으로 붙는 (파라미터): 반환형.
 * distinct 는 이름이 같은 선언(init 블록 등)을 트리에서 나누는 값
 */
data class Owner(val kind: OwnerKind, val name: String, val signature: String = "", val distinct: Int = 0) {
	/** 언어가 준 아이콘. 생성자 밖에 둬서 equals 에서 빠지도록. 아이콘은 매번 새 객체라 넣으면 결과 비교가 늘 달라짐 */
	var icon: Icon? = null

	val key: String get() = "$kind $name$signature#$distinct"
	val shortName: String get() = if (signature.startsWith("(")) "$name()" else name
}

/** 줄 번호는 0-based */
data class CommentHit(
	val startLine: Int,
	val endLine: Int,
	val column: Int,
	val kind: CommentKind,
	val text: String,
	/** 주석의 모든 줄이 바뀐 줄인지. 일부만 바뀌었거나 사이 줄이 지워졌으면 false */
	val full: Boolean,
	val owners: List<Owner>,
	val status: CommentStatus = CommentStatus.ADDED,
	val oldText: String? = null,
) {
	/** 트리에 회색으로 붙는 주석 종류 표시. 줄 주석은 언어마다 기호가 달라 원문에서 뽑음 */
	val tag: String
		get() = when (kind) {
			CommentKind.DOC -> "Doc"
			CommentKind.BLOCK -> if (text.startsWith("<!--")) "<!-- -->" else "/* */"
			CommentKind.LINE -> LINE_MARKERS.firstOrNull { text.startsWith(it) } ?: "//"
		}

	/** 여러 줄 주석은 첫 줄에 여는 기호만 있어서 내용이 있는 첫 줄로. Kotlin 주석은 중첩되니 여는 기호를 여기 적지 말 것 */
	val headline: String
		get() {
			val lines = text.lines()
			if (lines.size == 1) {
				return lines[0]
			}
			val first = lines[0].trim()
			val opener = first.takeWhile { !it.isLetterOrDigit() && !it.isWhitespace() }.ifEmpty { first.take(3) }
			val body = lines.asSequence()
				.map { line -> CLOSERS.fold(line.trim().removePrefix(opener)) { acc, closer -> acc.removeSuffix(closer) } }
				.map { it.trim().trimStart('*', '#').trim() }
				.firstOrNull { it.isNotEmpty() }
				.orEmpty()
			return "$opener $body"
		}

	private companion object {
		val LINE_MARKERS = listOf("//", "#", "--", ";")
		val CLOSERS = listOf("*/", "\"\"\"", "'''", "-->")
	}
}

data class FileComments(
	val filePath: FilePath,
	/** 버전 관리 밖(Unversioned) 파일이면 null */
	val change: Change?,
	val fileStatus: FileStatus,
	val comments: List<CommentHit>,
)

data class ScanResult(
	val files: List<FileComments>,
	/** 바뀌었지만 바뀐 줄에 주석이 없는 파일 */
	val quietFiles: List<Pair<FilePath, FileStatus>>,
	val errors: List<String>,
	/** 언어 플러그인이 없어 주석을 못 읽은 파일 */
	val skippedFiles: List<Pair<FilePath, FileStatus>> = emptyList(),
) {
	val commentCount: Int get() = files.sumOf { it.comments.size }
	val changedFileCount: Int get() = files.size + quietFiles.size
}
