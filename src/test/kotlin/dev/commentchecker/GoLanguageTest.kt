package dev.commentchecker

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Go 플러그인은 직접 설치한 것을 씀. 없는 컴퓨터에서는 build.gradle.kts 가 이 클래스를 뺌 */
class GoLanguageTest : BasePlatformTestCase() {
	fun testDocOnTypeAndTrailingFieldComment() {
		val text = "package p\n\n// Repo 는 저장소\ntype Repo struct {\n\tdb int // 연결\n}\n"
		assertEquals(listOf("Repo"), ownersOf(project, "a.go", text, "// Repo 는 저장소"))
		assertEquals(listOf("Repo", "db: int"), ownersOf(project, "a.go", text, "// 연결"))
	}

	fun testMethodWithReceiverSignature() {
		val text = "package p\n\n// Find 는 찾기\nfunc (r *Repo) Find(id int64) (*User, error) {\n\treturn nil, nil\n}\n"
		assertEquals(listOf("*Repo.Find(id int64) (*User, error)"), ownersOf(project, "a.go", text, "// Find 는 찾기"))
	}

	fun testShortVarIsNotOwner() {
		assertEquals(listOf("F()"), ownersOf(project, "a.go", "package p\n\nfunc F() {\n\t// 합계\n\tsum := 0\n\t_ = sum\n}\n", "// 합계"))
	}

	fun testGroupedTypeVarAndConst() {
		val text = "package p\n\ntype (\n\t// A 설명\n\tA struct{}\n)\n\n// Default 는 기본값\nvar Default = 1\n\n// Max 는 최대\nconst Max = 3\n"
		assertEquals(listOf("A"), ownersOf(project, "a.go", text, "// A 설명"))
		assertEquals(listOf("Default"), ownersOf(project, "a.go", text, "// Default 는 기본값"))
		assertEquals(listOf("Max"), ownersOf(project, "a.go", text, "// Max 는 최대"))
	}

	fun testFuncLiteralParamsAndLocalsAreNotOwners() {
		val text = "package p\n\nvar root = &Command{\n\tRun: func(cmd *Command, args []string) { // 실행\n\t\t// 설정\n\t\tcfg := load()\n\t\t_ = cfg\n\t},\n}\n"
		for (comment in listOf("// 실행", "// 설정")) {
			val owners = ownersOf(project, "a.go", text, comment).orEmpty()
			assertTrue("$comment $owners", owners.none { it.startsWith("cmd") || it.startsWith("args") || it.startsWith("cfg") })
		}
	}

	fun testNamedResultOfPackageLevelFuncLiteralIsNotOwner() {
		val text = "package p\n\nvar handle = func() (err error) { // 처리\n\treturn nil\n}\n"
		val owners = ownersOf(project, "a.go", text, "// 처리").orEmpty()
		assertTrue(owners.toString(), owners.none { it.startsWith("err") })
	}

	fun testReceiverAndParamsAreNotOwners() {
		val text = "package p\n\nfunc (r *Repo) Put(id int) { // 저장\n}\n"
		assertEquals(listOf("*Repo.Put(id int)"), ownersOf(project, "a.go", text, "// 저장"))
	}
}
