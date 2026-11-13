package dev.commentchecker.core

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.PsiTreeUtil
import dev.commentchecker.model.CommentHit
import dev.commentchecker.model.CommentKind

object CommentScanner {
	private val BLANK_LINE = Regex("\n[ \t\r]*\n")
	private const val MASK_CHAR = '\u0000'
	private const val MASK = "\u0000"

	/** 주석·공백을 뺀 실제 범위. PSI 는 앞에 붙은 주석을 선언 안에 넣어 범위를 늘려 둠 */
	private class Decl(val element: PsiElement, val start: Int) {
		val end: Int = edge(element, last = true)
	}

	/** 넓혀도 되는 사이: 키워드와 공백뿐(export const, type, private readonly). 기호를 지나면 다른 선언·식까지 넓어짐 */
	private val KEYWORDS_ONLY = Regex("[\\p{L}\\p{N}_$ \t]*")

	/** text 는 file 을 만든 원문과 같아야 함. 줄·오프셋 계산을 여기에 맞춤 */
	fun scan(file: PsiFile, text: CharSequence, touched: Set<Int>, cuts: Set<Int> = emptySet()): List<CommentHit> {
		val declarations = Declarations.forFile(file)
		val lines = LineIndex(text)
		// 바뀐 줄에 주석이 있을 때만 계산. 주석 없는 큰 파일(package-lock.json 등)에서 선언을 다 훑지 않도록
		val decls by lazy(LazyThreadSafetyMode.NONE) {
			declarations?.let { d ->
				SyntaxTraverser.psiTraverser(file)
					.filter { ProgressManager.checkCanceled(); d.isMember(it) }
					.map { Decl(it, startOf(it, d, text)) }
					.toList()
			}.orEmpty()
		}
		val comments = SyntaxTraverser.psiTraverser(file)
			.filter(PsiComment::class.java)
			.filter { PsiTreeUtil.getParentOfType(it, PsiComment::class.java, true) == null }
			.toList()

		fun hitOf(element: PsiElement, kind: CommentKind, owner: (TextRange, Int) -> Decl?): CommentHit? {
			val range = element.textRange
			if (range.isEmpty) {
				return null
			}
			val startLine = lines.lineOf(range.startOffset)
			val endLine = lines.lineOf(range.endOffset - 1)
			val hitLines = (startLine..endLine).count { it in touched }
			val cutInside = cuts.any { startLine < it && it <= endLine }
			if (hitLines == 0 && !cutInside) {
				return null
			}
			val target = owner(range, startLine)
			val path = if (target != null) {
				containing(decls, target.start, target.end).let { if (it.lastOrNull() === target) it else it + target }
			} else {
				containing(decls, range.startOffset, range.endOffset)
			}
			return CommentHit(
				startLine = startLine,
				endLine = endLine,
				column = range.startOffset - lines.startOf(startLine),
				kind = kind,
				text = element.text,
				full = hitLines == endLine - startLine + 1 && !cutInside,
				owners = path.map { declarations!!.ownerOf(it.element) },
			)
		}

		val hits = comments.mapNotNull { comment ->
			hitOf(comment, kindOf(comment.text)) { range, startLine ->
				// "/* a */ // b" 의 b 는 앞에 주석만 있으니 꼬리 주석이 아님
				if (!onlyComments(text, lines.startOf(startLine), range.startOffset, comments)) {
					trailingOwner(decls, lines, startLine, range.startOffset)
				} else {
					leadingOwner(decls, text, range.endOffset, comments)
				}
			}
		}
		val docs = declarations?.docComments(file).orEmpty().mapNotNull { doc ->
			hitOf(doc.element, CommentKind.DOC) { _, _ -> doc.owner?.let { owner -> decls.firstOrNull { it.element === owner } } }
		}
		return (hits + docs).sortedWith(compareBy<CommentHit>({ it.startLine }, { it.column }))
	}

	private fun kindOf(text: String) = when {
		text.startsWith("/**") && text != "/**/" -> CommentKind.DOC
		text.startsWith("/*") || text.startsWith("<!--") -> CommentKind.BLOCK
		else -> CommentKind.LINE
	}

	/** 코드 뒤 꼬리 주석은 그 줄에서 끝나는 선언 */
	private fun trailingOwner(decls: List<Decl>, lines: LineIndex, line: Int, commentStart: Int): Decl? {
		return decls
			.filter { it.end <= commentStart && lines.lineOf(maxOf(it.end - 1, it.start)) == line }
			.maxWithOrNull(compareBy<Decl> { it.end }.thenBy { it.start })
	}

	/** 바로 뒤 선언. KDoc/Javadoc 은 선언 범위 밖이라 감싸는 선언으로 찾으면 클래스에 붙음 */
	private fun leadingOwner(decls: List<Decl>, text: CharSequence, commentEnd: Int, comments: List<PsiComment>): Decl? {
		val next = decls
			.filter { it.start >= commentEnd }
			.minWithOrNull(compareBy<Decl> { it.start }.thenByDescending { it.end - it.start })
			?: return null
		return next.takeIf { onlyGap(text, commentEnd, it.start, comments) }
	}

	/** 사이가 공백·주석뿐이고 빈 줄이 없는지 */
	private fun onlyGap(text: CharSequence, from: Int, to: Int, comments: List<PsiComment>): Boolean {
		if (from > to) {
			return false
		}
		val masked = masked(text, from, to, comments)
		return masked.replace(MASK, "").isBlank() && !BLANK_LINE.containsMatchIn(masked)
	}

	private fun onlyComments(text: CharSequence, from: Int, to: Int, comments: List<PsiComment>): Boolean {
		return from >= to || masked(text, from, to, comments).replace(MASK, "").isBlank()
	}

	/**
	 * 주석을 줄바꿈까지 MASK 로 덮음. 공백으로 덮으면 주석 줄이 빈 줄로 보이고,
	 * 원문 그대로 두면 블록 주석 안의 빈 줄이 빈 줄로 보임
	 */
	private fun masked(text: CharSequence, from: Int, to: Int, comments: List<PsiComment>): String {
		val gap = StringBuilder(text.subSequence(from, to))
		for (comment in comments) {
			val lo = maxOf(comment.textRange.startOffset, from)
			val hi = minOf(comment.textRange.endOffset, to)
			for (i in lo until hi) {
				gap.setCharAt(i - from, MASK_CHAR)
			}
		}
		return gap.toString()
	}

	/** 바깥 → 안 */
	private fun containing(decls: List<Decl>, start: Int, end: Int): List<Decl> {
		return decls.filter { it.start <= start && end <= it.end }.sortedByDescending { it.end - it.start }
	}

	/**
	 * 선언이 눈에 보이는 시작 위치. export const x, type X struct, private readonly x 는
	 * 키워드가 바깥 문장에 있어 이름부터 재면 바로 위 주석이 안 붙음. 키워드·공백만 사이에 둔 바깥 문장까지 넓힘
	 */
	private fun startOf(element: PsiElement, declarations: Declarations, text: CharSequence): Int {
		val own = edge(element, last = false)
		if (!declarations.keywordsOutside) {
			return own
		}
		var start = own
		var node = element.parent
		while (node != null && node !is PsiFile && !declarations.isMember(node)) {
			val outer = edge(node, last = false)
			val between = text.subSequence(minOf(outer, start), start)
			if (outer > start || !KEYWORDS_ONLY.matches(between)) {
				return start
			}
			start = outer
			node = node.parent
		}
		return start
	}

	private fun edge(element: PsiElement, last: Boolean): Int {
		var node = element
		while (true) {
			var child = if (last) node.lastChild else node.firstChild
			while (child != null && (child is PsiComment || child is PsiWhiteSpace || child.textLength == 0)) {
				child = if (last) child.prevSibling else child.nextSibling
			}
			if (child == null) {
				return if (last) node.textRange.endOffset else node.textRange.startOffset
			}
			node = child
		}
	}
}

class LineIndex(text: CharSequence) {
	private val starts: IntArray = buildList {
		add(0)
		text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) }
	}.toIntArray()

	fun lineOf(offset: Int): Int {
		val found = starts.binarySearch(offset)
		return if (found >= 0) found else -found - 2
	}

	fun startOf(line: Int): Int = starts[line]
}
