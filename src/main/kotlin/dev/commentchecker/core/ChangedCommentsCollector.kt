package dev.commentchecker.core

import com.intellij.diff.comparison.DiffTooBigException
import com.intellij.lang.Language
import com.intellij.lang.LanguageCommenters
import com.intellij.lang.LanguageParserDefinitions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangesUtil
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.LightVirtualFile
import dev.commentchecker.CommentCheckerBundle.message
import dev.commentchecker.model.CommentHit
import dev.commentchecker.model.CommentStatus
import dev.commentchecker.model.FileComments
import dev.commentchecker.model.ScanResult
import java.util.concurrent.ConcurrentHashMap

/** Commit 창에 오른 파일(변경 + Unversioned)에서 바뀐 줄의 주석을 모음. Unversioned 는 전체가 새 줄 */
class ChangedCommentsCollector(private val project: Project) {
	/** 기준 리비전 내용. 같은 리비전은 바뀌지 않으니 git 호출을 반복하지 않도록 */
	private val beforeCache = ConcurrentHashMap<String, String>()

	private class Source(
		val path: FilePath,
		val language: Language,
		val change: Change?,
		val status: FileStatus,
		val before: String?,
	) {
		var after: String? = null
		var tooLarge = false
		var changes: LineChanges? = null
	}

	/**
	 * 읽기 잠금 밖에서 불러야 함. 무거운 일(VCS 읽기, 줄 비교)은 잠금 밖에서 하고
	 * PSI 만 취소 가능한 읽기 동작 안에서. 오래 잡으면 편집(쓰기)이 막혀 타이핑이 끊김
	 */
	fun collect(indicator: ProgressIndicator): ScanResult {
		val clm = ChangeListManager.getInstance(project)
		val errors = mutableListOf<String>()
		val sources = mutableListOf<Source>()
		val skipped = mutableListOf<Pair<FilePath, FileStatus>>()
		val used = HashSet<String>()
		for (change in clm.allChanges) {
			indicator.checkCanceled()
			val path = ChangesUtil.getFilePath(change)
			val type = fileTypeOf(path.name)
			val language = languageOf(type)
			if (language == null) {
				if (isTextMate(type)) {
					skipped += path to change.fileStatus
				}
				continue
			}
			val before = change.beforeRevision?.let { revision ->
				loadBefore(revision, used).also { if (it == null) errors += message("error.baseUnreadable", path.name) }
			}
			sources += Source(path, language, change, change.fileStatus, before)
		}
		for (path in clm.unversionedFilesPaths) {
			val type = fileTypeOf(path.name)
			val language = languageOf(type)
			if (language != null) {
				sources += Source(path, language, null, FileStatus.UNKNOWN, null)
			} else if (isTextMate(type)) {
				skipped += path to FileStatus.UNKNOWN
			}
		}
		beforeCache.keys.retainAll(used)

		for (source in sources) {
			indicator.checkCanceled()
			source.after = currentText(source)
			if (source.tooLarge || (source.before?.length ?: 0) > MAX_CHARS || (source.after?.length ?: 0) > MAX_CHARS) {
				errors += message("error.fileTooLarge", source.path.name)
				continue
			}
			try {
				source.changes = lineChanges(source.before, source.after, indicator)
			} catch (e: DiffTooBigException) {
				errors += message("error.diffTooLarge", source.path.name)
			}
		}

		return ReadAction.nonBlocking<ScanResult> { scanAll(sources, errors, skipped) }
			.wrapProgress(indicator)
			.executeSynchronously()
	}

	private fun loadBefore(revision: ContentRevision, used: MutableSet<String>): String? {
		val key = "${revision.file.path}@${revision.revisionNumber.asString()}"
		used += key
		beforeCache[key]?.let { return it }
		return try {
			// git 이 CRLF 그대로 주면 LF 인 문서와 파일 전체가 다르게 비교됨
			val content = revision.content?.let(StringUtil::convertLineSeparators) ?: return null
			beforeCache[key] = content
			content
		} catch (e: VcsException) {
			null
		}
	}

	private fun scanAll(sources: List<Source>, errors: List<String>, skipped: List<Pair<FilePath, FileStatus>>): ScanResult {
		val files = mutableListOf<FileComments>()
		val quiet = mutableListOf<Pair<FilePath, FileStatus>>()
		for (source in sources) {
			// 읽기 동작의 취소(쓰기 대기)는 바깥 indicator 가 아니라 여기로 전달됨
			ProgressManager.checkCanceled()
			val changes = source.changes ?: continue
			val comments = scanComments(project, source.path.name, source.language, source.before, source.after, changes)
			if (comments.isEmpty()) {
				quiet += source.path to source.status
			} else {
				files += FileComments(source.path, source.change, source.status, comments)
			}
		}
		return ScanResult(files, quiet, errors, skipped)
	}

	/** 저장 안 한 편집은 열린 문서에만 있음. 문서가 없으면 디스크에서 읽어 읽기 잠금을 짧게 */
	private fun currentText(source: Source): String? {
		if (source.change != null && source.change.afterRevision == null) {
			return null
		}
		val file = source.path.virtualFile ?: return null
		val cached = ApplicationManager.getApplication().runReadAction<String?> {
			FileDocumentManager.getInstance().getCachedDocument(file)?.immutableCharSequence?.toString()
		}
		if (cached != null) {
			return cached
		}
		// 바이트가 글자 한도의 4배를 넘으면 글자도 넘음. 읽기 전에 거름
		if (file.length > MAX_CHARS * 4L) {
			source.tooLarge = true
			return null
		}
		return LoadTextUtil.loadText(file).toString()
	}

	companion object {
		/** 이보다 큰 파일은 건너뜀. 생성 코드·데이터 파일을 파싱하느라 멈추지 않도록 */
		const val MAX_CHARS = 1_000_000

		/** 언어 플러그인이 없을 때 IDE 가 문법 강조용으로 붙이는 언어 */
		private const val TEXTMATE = "textmate"

		/** 이름으로 찾으면 TextMate 를 못 알아봄(Unknown). TextMate 는 파일 객체로 물어야 자기 파일이라고 답함 */
		fun fileTypeOf(fileName: String): FileType = FileTypeManager.getInstance().getFileTypeByFile(LightVirtualFile(fileName))

		fun languageOf(fileName: String): Language? = languageOf(fileTypeOf(fileName))

		fun languageOf(type: FileType): Language? {
			val language = (type as? LanguageFileType)?.language ?: return null
			// TextMate 는 주석 처리기·파서가 있어 통과하지만 PSI 에 주석이 없음. 읽으면 "주석 없음"으로 잘못 나옴
			if (isTextMate(type)
				|| LanguageCommenters.INSTANCE.forLanguage(language) == null
				|| LanguageParserDefinitions.INSTANCE.forLanguage(language) == null) {
				return null
			}
			return language
		}

		/** 코드 파일인데 언어 플러그인이 없어 TextMate 문법으로만 열리는 파일 */
		fun isTextMate(type: FileType): Boolean = (type as? LanguageFileType)?.language?.id == TEXTMATE

		fun lineChanges(before: String?, after: String?, indicator: ProgressIndicator): LineChanges? = when {
			after != null && before != null -> LineChanges.compare(before, after, indicator)
			after != null -> LineChanges.allAdded(after)
			before != null -> LineChanges.allRemoved(before)
			else -> null
		}

		/** 읽기 잠금 안에서 부름 */
		fun scanComments(
			project: Project,
			fileName: String,
			language: Language,
			before: String?,
			after: String?,
			changes: LineChanges,
		): List<CommentHit> {
			val factory = PsiFileFactory.getInstance(project)
			val newHits = after?.let { text ->
				CommentScanner.scan(factory.createFileFromText(fileName, language, text), text, changes.added, changes.cuts)
					.map { it.copy(status = if (it.full) CommentStatus.ADDED else CommentStatus.MODIFIED) }
			}.orEmpty()
			val oldHits = before?.takeIf { changes.removed.isNotEmpty() }?.let { text ->
				CommentScanner.scan(factory.createFileFromText(fileName, language, text), text, changes.removed)
					.filter { it.full }
					.map { it.copy(status = CommentStatus.DELETED) }
			}.orEmpty()
			return Reconciler.reconcile(newHits, oldHits, changes)
				.sortedWith(compareBy<CommentHit>({ it.status == CommentStatus.DELETED }, { it.startLine }, { it.column }))
		}

		/** 테스트·스냅샷용 */
		fun commentChanges(
			project: Project,
			fileName: String,
			language: Language,
			before: String?,
			after: String?,
			indicator: ProgressIndicator,
		): List<CommentHit> {
			val normalizedBefore = before?.let(StringUtil::convertLineSeparators)
			val changes = lineChanges(normalizedBefore, after, indicator) ?: return emptyList()
			return scanComments(project, fileName, language, normalizedBefore, after, changes)
		}
	}
}
