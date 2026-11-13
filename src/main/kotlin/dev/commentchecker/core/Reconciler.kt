package dev.commentchecker.core

import dev.commentchecker.model.CommentHit
import dev.commentchecker.model.CommentStatus

object Reconciler {
	/**
	 * 같은 변경 구간 안에서만 짝지음. 파일 전체로 짝지으면 지운 테스트와 새 테스트의 "// given" 이 서로 지워짐.
	 * 같은 문구는 양쪽 다 뺌(코드만 바뀐 줄의 꼬리 주석, 들여쓰기 변경). 남은 추가·삭제는 줄 순서대로 수정 하나로 합침
	 */
	fun reconcile(newHits: List<CommentHit>, oldHits: List<CommentHit>, changes: LineChanges): List<CommentHit> {
		val consumed = HashSet<Int>()
		val oldIndexesByKey = oldHits.indices.groupByTo(HashMap()) { i ->
			changes.fragmentIndex(oldHits[i].startLine, old = true) to normalized(oldHits[i].text)
		}
		val keptNew = newHits.filter { hit ->
			val key = changes.fragmentIndex(hit.startLine, old = false) to normalized(hit.text)
			val match = oldIndexesByKey[key]?.firstOrNull { it !in consumed }
			match?.let(consumed::add)
			match == null
		}

		val remainingOld = oldHits.indices.filter { it !in consumed }
			.groupByTo(HashMap()) { changes.fragmentIndex(oldHits[it].startLine, old = true) }
		val merged = keptNew.map { hit ->
			val bucket = remainingOld[changes.fragmentIndex(hit.startLine, old = false)]
			if (hit.status != CommentStatus.ADDED || !hit.full || bucket.isNullOrEmpty()) {
				return@map hit
			}
			val previous = oldHits[bucket.removeAt(0)]
			hit.copy(status = CommentStatus.MODIFIED, oldText = previous.text)
		}
		val leftover = remainingOld.values.flatten().sorted().map { oldHits[it] }
		return merged + leftover
	}

	private fun normalized(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
}
