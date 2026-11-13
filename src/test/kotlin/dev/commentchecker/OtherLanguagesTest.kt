package dev.commentchecker

import com.intellij.lang.Language
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.PythonLanguage
import dev.commentchecker.core.ChangedCommentsCollector
import dev.commentchecker.core.CommentScanner
import dev.commentchecker.model.CommentHit
import dev.commentchecker.model.CommentKind
import dev.commentchecker.model.CommentStatus
import dev.commentchecker.model.OwnerKind

class OtherLanguagesTest : BasePlatformTestCase() {
	private fun scan(name: String, language: Language, text: String): Map<String, CommentHit> {
		val file = PsiFileFactory.getInstance(project).createFileFromText(name, language, text)
		val every = (0..text.count { it == '\n' }).toSet()
		return CommentScanner.scan(file, text, every).associateBy { it.text }
	}

	private fun py(text: String) = scan("a.py", PythonLanguage.INSTANCE, text)

	private fun owners(hit: CommentHit?) = hit?.owners?.map { "${it.kind} ${it.name}${it.signature}" }

	fun testFunctionDocstringIsDocCommentOfFunction() {
		val hit = py("class A:\n    def f(self):\n        \"\"\"함수 설명\"\"\"\n        return 1\n")["\"\"\"함수 설명\"\"\""]
		assertEquals(CommentKind.DOC, hit?.kind)
		assertEquals("Doc", hit?.tag)
		assertEquals(listOf("CLASS A", "FUNCTION f(self)"), owners(hit))
	}

	fun testModuleDocstringHasNoOwner() {
		val hit = py("\"\"\"모듈 설명\"\"\"\nimport os\n")["\"\"\"모듈 설명\"\"\""]
		assertEquals(CommentKind.DOC, hit?.kind)
		assertEquals(emptyList<String>(), owners(hit))
	}

	fun testHashCommentAboveDefAttachesToFunction() {
		val hit = py("# g 설명\ndef g(x: int) -> int:\n    return x\n")["# g 설명"]
		assertEquals("#", hit?.tag)
		assertEquals(listOf("FUNCTION g(x: int) -> int"), owners(hit))
	}

	fun testCommentAboveDecoratorAttachesToFunction() {
		assertEquals(listOf("FUNCTION h()"), owners(py("# 데코레이터 위\n@cache\ndef h():\n    pass\n")["# 데코레이터 위"]))
	}

	fun testCommentInFunctionBody() {
		assertEquals(listOf("FUNCTION k()"), owners(py("def k():\n    # 본문\n    x = 1\n")["# 본문"]))
	}

	fun testTrailingCommentOnClassAttribute() {
		assertEquals(listOf("CLASS B"), owners(py("class B:\n    x = 1  # 기본값\n")["# 기본값"]))
	}

	fun testBlankLineDetachesPythonComment() {
		assertEquals(listOf("CLASS C"), owners(py("class C:\n    # 묶음\n\n    def m(self):\n        pass\n")["# 묶음"]))
	}

	fun testMultiLineDocstringHeadline() {
		val hit = py("def f():\n    \"\"\"\n    첫 줄 요약.\n\n    자세한 설명\n    \"\"\"\n").values.single()
		assertEquals("\"\"\" 첫 줄 요약.", hit.headline)
	}

	fun testEditedDocstringIsModified() {
		val got = ChangedCommentsCollector.commentChanges(
			project, "a.py", PythonLanguage.INSTANCE,
			"def f():\n    \"\"\"옛 설명\"\"\"\n    return 1\n",
			"def f():\n    \"\"\"새 설명\"\"\"\n    return 1\n",
			EmptyProgressIndicator(),
		)
		assertEquals(listOf(CommentStatus.MODIFIED to "\"\"\"옛 설명\"\"\""), got.map { it.status to it.oldText })
	}

	fun testXmlCommentUsesBreadcrumbs() {
		val hit = scan("a.xml", XMLLanguage.INSTANCE, "<root>\n  <!-- 설정 설명 -->\n  <child name=\"a\"/>\n</root>\n")["<!-- 설정 설명 -->"]
		assertEquals(CommentKind.BLOCK, hit?.kind)
		assertEquals("<!-- -->", hit?.tag)
		assertEquals(listOf(OwnerKind.OTHER, OwnerKind.OTHER), hit?.owners?.map { it.kind })
		assertTrue(hit!!.owners.last().name, hit.owners.last().name.contains("child"))
	}

	fun testLanguageDetection() {
		assertEquals("Python", ChangedCommentsCollector.languageOf("a.py")?.id)
		assertEquals("kotlin", ChangedCommentsCollector.languageOf("a.kt")?.id)
		assertEquals("JAVA", ChangedCommentsCollector.languageOf("A.java")?.id)
		assertNull(ChangedCommentsCollector.languageOf("notes.txt"))
		assertNull(ChangedCommentsCollector.languageOf("logo.png"))
	}
}
