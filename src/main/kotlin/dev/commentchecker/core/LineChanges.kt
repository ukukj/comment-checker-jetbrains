package dev.commentchecker.core

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.progress.ProgressIndicator

/** 기준 ↔ 현재 사이 줄 단위 변경. 줄 번호는 0-based, 구간 끝은 미포함 */
class LineChanges private constructor(private val fragments: List<Fragment>) {
	data class Fragment(val start1: Int, val end1: Int, val start2: Int, val end2: Int)

	/** 기준 쪽에서 지워지거나 바뀐 줄 */
	val removed: Set<Int> = fragments.flatMapTo(HashSet()) { it.start1 until it.end1 }

	/** 현재 쪽에서 추가되거나 바뀐 줄 */
	val added: Set<Int> = fragments.flatMapTo(HashSet()) { it.start2 until it.end2 }

	/** added 중 기존 줄을 고친 줄 */
	val modified: Set<Int> = fragments.filter { it.end1 > it.start1 }.flatMapTo(HashSet()) { it.start2 until it.end2 }

	/** 순수 삭제 위치. q 면 현재 쪽 q-1 줄과 q 줄 사이에서 지워짐 */
	val cuts: Set<Int> = fragments.filter { it.start2 == it.end2 }.mapTo(HashSet()) { it.start2 }

	fun fragmentIndex(line: Int, old: Boolean): Int {
		return fragments.indexOfFirst {
			if (old) line in it.start1 until it.end1 else line in it.start2 until it.end2
		}
	}

	companion object {
		fun allAdded(text: CharSequence) = LineChanges(listOf(Fragment(0, 0, 0, lineCount(text))))

		fun allRemoved(text: CharSequence) = LineChanges(listOf(Fragment(0, lineCount(text), 0, 0)))

		fun compare(before: CharSequence, after: CharSequence, indicator: ProgressIndicator): LineChanges {
			val lines = ComparisonManager.getInstance().compareLines(before, after, ComparisonPolicy.DEFAULT, indicator)
			return LineChanges(lines.map { Fragment(it.startLine1, it.endLine1, it.startLine2, it.endLine2) })
		}

		private fun lineCount(text: CharSequence) = text.count { it == '\n' } + 1
	}
}
