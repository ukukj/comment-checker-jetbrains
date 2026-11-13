package dev.commentchecker

import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.commentchecker.core.ChangedCommentsCollector
import dev.commentchecker.model.CommentKind
import dev.commentchecker.model.ScanResult
import dev.commentchecker.ui.GroupNode
import dev.commentchecker.ui.QUIET_GROUP_KEY
import dev.commentchecker.ui.QuietFileNode
import dev.commentchecker.ui.SKIPPED_GROUP_KEY
import dev.commentchecker.ui.TreeBuilder
import dev.commentchecker.ui.ViewSettings
import javax.swing.tree.DefaultMutableTreeNode

class SkippedFilesTest : BasePlatformTestCase() {
	/** 테스트 환경에 Ruby 플러그인이 없어 .rb 는 TextMate 의 기본 문법으로만 열림. 실제 IDE 에서 언어 플러그인이 없을 때와 같음 */
	fun testFileWithoutLanguagePluginIsSkippedNotScanned() {
		assertTrue(ChangedCommentsCollector.isTextMate(ChangedCommentsCollector.fileTypeOf("a.rb")))
		// 읽으면 PSI 에 주석이 없어 "바뀐 줄에 주석이 없는 파일"로 잘못 나옴
		assertNull(ChangedCommentsCollector.languageOf("a.rb"))
	}

	fun testOrdinaryFilesAreNotSkipped() {
		for (name in listOf("a.kt", "logo.png", "notes.txt")) {
			assertFalse(name, ChangedCommentsCollector.isTextMate(ChangedCommentsCollector.fileTypeOf(name)))
		}
	}

	fun testSkippedGroupShowsEvenWhenFiltered() {
		val settings = ViewSettings(project)
		settings.show(CommentKind.DOC, false)
		try {
			val quiet = LocalFilePath("/repo/src/b.kt", false) to FileStatus.MODIFIED
			val skipped = LocalFilePath("/repo/src/a.rb", false) to FileStatus.ADDED
			val root = TreeBuilder(settings, "/repo").build(ScanResult(emptyList(), listOf(quiet), emptyList(), listOf(skipped)))
			val groups = root.children().asSequence().map { it as DefaultMutableTreeNode }.toList()
			// 필터가 걸리면 "주석 없는 파일" 묶음은 숨지만 건너뛴 파일 안내는 남아야 함
			assertEquals(listOf(SKIPPED_GROUP_KEY), groups.map { (it.userObject as GroupNode).key })
			assertEquals("a.rb", (groups.single().firstChild as DefaultMutableTreeNode).let { (it.userObject as QuietFileNode).path.name })
		} finally {
			settings.show(CommentKind.DOC, true)
		}
		val all = TreeBuilder(settings, "/repo").build(
			ScanResult(emptyList(), listOf(LocalFilePath("/repo/b.kt", false) to FileStatus.MODIFIED), emptyList(), listOf(LocalFilePath("/repo/a.rb", false) to FileStatus.ADDED)),
		)
		assertEquals(listOf(QUIET_GROUP_KEY, SKIPPED_GROUP_KEY), all.children().asSequence().map { ((it as DefaultMutableTreeNode).userObject as GroupNode).key }.toList())
	}
}
