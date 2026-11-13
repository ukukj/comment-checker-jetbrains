package dev.commentchecker

import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.commentchecker.core.ChangedCommentsCollector
import dev.commentchecker.core.CommentScanner
import dev.commentchecker.model.CommentStatus
import org.jetbrains.kotlin.idea.KotlinLanguage

class CommentScannerTest : BasePlatformTestCase() {
	private fun chains(text: String, ext: String = "kt"): Map<String, List<String>> {
		val language = if (ext == "java") JavaLanguage.INSTANCE else KotlinLanguage.INSTANCE
		val file = PsiFileFactory.getInstance(project).createFileFromText("A.$ext", language, text)
		val every = (0..text.count { it == '\n' }).toSet()
		return CommentScanner.scan(file, text, every).associate { hit ->
			hit.text to hit.owners.map { "${it.kind} ${it.name}${it.signature}" }
		}
	}

	private fun scan(text: String, touched: Set<Int>, cuts: Set<Int> = emptySet()) =
		CommentScanner.scan(PsiFileFactory.getInstance(project).createFileFromText("A.kt", KotlinLanguage.INSTANCE, text), text, touched, cuts)

	fun testKdocRightAfterPackageAttachesToClass() {
		assertEquals(listOf("CLASS E"), chains("package a.b\n\n/** 이벤트 */\ndata class E(\n\tval x: Long,\n)\n")["/** 이벤트 */"])
	}

	fun testKdocAboveAnnotationsAttachesToClass() {
		val got = chains("package a\n\nimport x.Y\n\n/** 전표 */\n@Entity\n@Table(name = \"s\")\nclass S(\n\ta: Long,\n)\n")
		assertEquals(listOf("CLASS S"), got["/** 전표 */"])
	}

	fun testTrailingCommentAttachesToEnumEntry() {
		val got = chains("package a\n\nenum class T {\n\tSALE,\t\t// 판매\n\tPROD,\t// 생산\n}\n")
		assertEquals(listOf("ENUM T", "ENTRY SALE"), got["// 판매"])
		assertEquals(listOf("ENUM T", "ENTRY PROD"), got["// 생산"])
	}

	fun testCommentBeforeLocalValStaysOnFunction() {
		assertEquals(listOf("CLASS A", "FUNCTION f()"), chains("class A {\n\tfun f() {\n\t\t// when\n\t\tval rows = 1\n\t}\n}\n")["// when"])
	}

	fun testBlankLineDetachesCommentFromNextMember() {
		assertEquals(listOf("CLASS A"), chains("class A {\n\tfun f() {}\n\n\t// 묶음 설명\n\n\tfun g() {}\n}\n")["// 묶음 설명"])
	}

	fun testCommentBetweenFunctionsGoesToNext() {
		val got = chains("class A {\n\tfun f() {\n\t}\n\t/** g 설명 */\n\tfun g(x: Int): Long = 1\n}\n")
		assertEquals(listOf("CLASS A", "FUNCTION g(x: Int): Long"), got["/** g 설명 */"])
	}

	fun testNestedDataClassInInterface() {
		val got = chains("interface R {\n\t/** 줄 */\n\tdata class Line(val id: Long)\n\n\tfun find(): List<Line>\n}\n")
		assertEquals(listOf("INTERFACE R", "CLASS Line"), got["/** 줄 */"])
	}

	fun testTrailingAfterKoreanString() {
		assertEquals(listOf("CLASS A", "PROPERTY name"), chains("class A {\n\tval name = \"한글이름\" // 표시명\n}\n")["// 표시명"])
	}

	fun testTopLevelConstKdoc() {
		assertEquals(listOf("PROPERTY D"), chains("package a\n\n/**\n * 자릿수\n */\nconst val D = 5\n")["/**\n * 자릿수\n */"])
	}

	fun testFileHeaderCommentHasNoOwner() {
		assertEquals(emptyList<String>(), chains("/* 라이선스 */\npackage a\n\nimport b.C\n\nclass A\n")["/* 라이선스 */"])
	}

	fun testStackedLineCommentsAllAttachToNextFunction() {
		val got = chains("class A {\n\t// one\n\t// two\n\t// three\n\tfun f() {}\n}\n")
		assertEquals(List(3) { listOf("CLASS A", "FUNCTION f()") }, listOf(got["// one"], got["// two"], got["// three"]))
	}

	fun testDocThenTodoAttachToFunction() {
		assertEquals(listOf("CLASS A", "FUNCTION f()"), chains("class A {\n\t/** doc */\n\t// TODO 정리\n\tfun f() {}\n}\n")["/** doc */"])
	}

	fun testClassKindIgnoresWordsInAnnotations() {
		assertEquals(listOf("CLASS Resp"), chains("/** a */\n@Comment(\"enum interface\")\nclass Resp(val data: String)\n")["/** a */"])
	}

	fun testGetterBodyComment() {
		val got = chains("class A {\n\tval x: Int\n\t\tget() {\n\t\t\t// 계산\n\t\t\treturn 1\n\t\t}\n}\n")
		assertEquals(listOf("CLASS A", "PROPERTY x", "ACCESSOR x.get()"), got["// 계산"])
	}

	fun testExtensionAndVarargSignature() {
		val got = chains("class A {\n\t/** d */\n\tprivate fun String.order(vararg lines: Pair<Long, String>): Int = 1\n}\n")
		assertEquals(listOf("CLASS A", "FUNCTION String.order(vararg lines: Pair<Long, String>): Int"), got["/** d */"])
	}

	fun testCompanionAndObjectLiteral() {
		val got = chains("class A {\n\tcompanion object {\n\t\t// 상수\n\t\tconst val X = 1\n\t}\n\tfun f() = object : Runnable {\n\t\t// 익명\n\t\toverride fun run() {}\n\t}\n}\n")
		assertEquals(listOf("CLASS A", "OBJECT companion object", "PROPERTY X"), got["// 상수"])
		assertEquals(listOf("CLASS A", "FUNCTION f()"), got["// 익명"])
	}

	fun testJavadocAboveAnnotatedMethod() {
		val src = "class A {\n\t/** 조회 */\n\t@Transactional\n\tpublic Long find(Long id) { return id; }\n}\n"
		assertEquals(listOf("CLASS A", "FUNCTION find(Long): Long"), chains(src, "java")["/** 조회 */"])
	}

	fun testJavaTrailingFieldComment() {
		assertEquals(listOf("CLASS A", "FIELD count"), chains("class A {\n\tprivate int count; // 개수\n}\n", "java")["// 개수"])
	}

	fun testJavaCommentInsideMethodBody() {
		val src = "class A {\n\tvoid run() {\n\t\t// 준비\n\t\tint x = 1;\n\t}\n}\n"
		assertEquals(listOf("CLASS A", "FUNCTION run(): void"), chains(src, "java")["// 준비"])
	}

	fun testJavaInterfaceConstant() {
		assertEquals(listOf("INTERFACE I", "FIELD MAX"), chains("interface I {\n\t/** 상한 */\n\tint MAX = 1;\n}\n", "java")["/** 상한 */"])
	}

	fun testJavaRecordCompactConstructor() {
		// compact 생성자는 PSI 가 레코드 컴포넌트를 암묵 파라미터로 넣어 둠
		assertEquals(listOf("RECORD R", "CONSTRUCTOR R(int)"), chains("record R(int a) {\n\tR {\n\t\t// 검증\n\t}\n}\n", "java")["// 검증"])
	}

	fun testJavaAnonymousClassMethodStaysOnOuterMethod() {
		val src = "class A {\n\tvoid run() {\n\t\tnew Thread() {\n\t\t\t// 안쪽\n\t\t\tpublic void run() {}\n\t\t};\n\t}\n}\n"
		assertEquals(listOf("CLASS A", "FUNCTION run(): void"), chains(src, "java")["// 안쪽"])
	}

	fun testOnlyCommentsOnTouchedLines() {
		val got = scan("class A {\n\t// old\n\tfun f() {}\n\t// new\n\tfun g() {}\n}\n", setOf(3))
		assertEquals(listOf("// new"), got.map { it.text })
	}

	fun testPartiallyTouchedBlockIsNotFull() {
		val got = scan("/**\n * a\n * b\n */\nclass A\n", setOf(2))
		assertEquals(0 to false, got.single().startLine to got.single().full)
	}

	fun testCutInsideBlockCountsAsTouched() {
		val got = scan("/**\n * a\n * c\n */\nclass A\n", emptySet(), setOf(2))
		assertFalse(got.single().full)
	}

	fun testCutRightAfterCommentIsNotInside() {
		assertEmpty(scan("// a\nclass A\n", emptySet(), setOf(1)))
	}

	private fun diff(before: String?, after: String?) = ChangedCommentsCollector.commentChanges(
		project, "A.kt", KotlinLanguage.INSTANCE, before, after, EmptyProgressIndicator(),
	).map { Triple(it.status, it.text, it.oldText) }

	fun testDeletingLineInsideKdocReportsComment() {
		val got = diff(
			"class A {\n\t/**\n\t * a\n\t * b\n\t * c\n\t */\n\tfun f() {}\n}\n",
			"class A {\n\t/**\n\t * a\n\t * c\n\t */\n\tfun f() {}\n}\n",
		)
		assertEquals(listOf(CommentStatus.MODIFIED), got.map { it.first })
	}

	fun testCodeChangeWithSameTrailingCommentIsNotReported() {
		assertEmpty(diff("class A {\n\tval x = 1 // unit count\n}\n", "class A {\n\tval x = 2 // unit count\n}\n"))
	}

	fun testReindentIsNotReported() {
		assertEmpty(diff("class A {\n\t// keep\n\tfun f() {}\n}\n", "class A {\n        // keep\n        fun f() {}\n}\n"))
	}

	fun testOneLineEditIsSingleModifiedWithOldText() {
		val got = diff("class A {\n\t// 판매\n\tfun f() {}\n}\n", "class A {\n\t// 판매 전표\n\tfun f() {}\n}\n")
		assertEquals(listOf(Triple(CommentStatus.MODIFIED, "// 판매 전표", "// 판매")), got)
	}

	fun testRemovedCommentIsDeleted() {
		val got = diff("class A {\n\t// TODO 지우지 말 것\n\tfun f() {}\n}\n", "class A {\n\tfun f() {}\n}\n")
		assertEquals(listOf(Triple(CommentStatus.DELETED, "// TODO 지우지 말 것", null)), got)
	}

	fun testSameTextInDifferentFragmentsIsNotCancelled() {
		val got = diff(
			"class T {\n\tfun a() {\n\t\t// given\n\t\tx()\n\t}\n\n\tfun keep() {}\n}\n",
			"class T {\n\tfun keep() {}\n\n\tfun b() {\n\t\t// given\n\t\ty()\n\t}\n}\n",
		)
		assertEquals(listOf(CommentStatus.ADDED, CommentStatus.DELETED), got.map { it.first })
	}

	fun testCrlfBeforeContentDoesNotPairUnrelatedComments() {
		val got = diff(
			"class A {\r\n\t// gone\r\n\tfun f() {}\r\n}\r\n",
			"class A {\n\tfun f() {}\n\t// new\n\tfun g() {}\n}\n",
		)
		assertEquals(listOf(Triple(CommentStatus.ADDED, "// new", null), Triple(CommentStatus.DELETED, "// gone", null)), got)
	}

	fun testBlankLineInsideBlockCommentDoesNotDetach() {
		val got = chains("class A {\n\t// TODO fix\n\t/* a\n\n\t b */\n\tfun f() {}\n}\n")
		assertEquals(listOf("CLASS A", "FUNCTION f()"), got["// TODO fix"])
	}

	fun testCommentAfterCommentOnSameLineIsNotTrailing() {
		assertEquals(listOf("CLASS A", "FUNCTION f()"), chains("class A {\n\t/* a */ // b\n\tfun f() {}\n}\n")["// b"])
	}

	fun testTwoInitBlocksAreSeparateOwners() {
		val text = "class A {\n\tinit {\n\t\t// one\n\t}\n\tinit {\n\t\t// two\n\t}\n}\n"
		val owners = scan(text, (0..8).toSet()).map { it.owners.last().key }
		assertEquals(2, owners.toSet().size)
	}

	fun testNewFileReportsAllComments() {
		assertEquals(listOf(CommentStatus.ADDED), diff(null, "// 새 파일\nclass A\n").map { it.first })
	}

	fun testDeletedFileReportsAllComments() {
		assertEquals(listOf(CommentStatus.DELETED), diff("// 지운 파일\nclass A\n", null).map { it.first })
	}
}
