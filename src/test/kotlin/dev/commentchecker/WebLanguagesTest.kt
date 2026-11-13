package dev.commentchecker

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.commentchecker.core.ChangedCommentsCollector
import dev.commentchecker.core.CommentScanner

fun ownersOf(project: Project, name: String, text: String, comment: String): List<String>? {
	val language = ChangedCommentsCollector.languageOf(name) ?: error("$name 언어 없음")
	val file = PsiFileFactory.getInstance(project).createFileFromText(name, language, text)
	val every = (0..text.count { it == '\n' }).toSet()
	val hit = CommentScanner.scan(file, text, every).firstOrNull { it.text == comment } ?: return null
	return hit.owners.map { it.name + it.signature }
}

class WebLanguagesTest : BasePlatformTestCase() {
	fun testDocOnExportedArrowConst() {
		val text = "/** 저장 */\nexport const save = async (user) => {\n  await db.put(user)\n}\n"
		assertEquals(listOf("save()"), ownersOf(project, "a.js", text, "/** 저장 */"))
	}

	fun testLocalConstIsNotOwner() {
		val text = "class R {\n  find(id) {\n    // 캐시 먼저\n    const hit = cache.get(id)\n    return hit\n  }\n}\n"
		assertEquals(listOf("R", "find()"), ownersOf(project, "a.js", text, "// 캐시 먼저"))
	}

	fun testParameterIsNotOwner() {
		val text = "class R {\n  save(user) { // 저장\n    return user\n  }\n}\n"
		assertEquals(listOf("R", "save()"), ownersOf(project, "a.js", text, "// 저장"))
	}

	fun testVarAndNestedFunctionAreNotOwners() {
		val text = "function outer() {\n  // 변수\n  var x = 1\n  // 안쪽 함수\n  function inner() {}\n  return x\n}\n"
		assertEquals(listOf("outer()"), ownersOf(project, "a.js", text, "// 변수"))
		assertEquals(listOf("outer()"), ownersOf(project, "a.js", text, "// 안쪽 함수"))
	}

	fun testFunctionsInsideIifeStayOwners() {
		val text = "(function () {\n  // foo 설명\n  function foo() {\n    // 본문\n    var y = 1\n  }\n})()\n"
		assertEquals(listOf("foo()"), ownersOf(project, "a.js", text, "// foo 설명"))
		assertEquals(listOf("foo()"), ownersOf(project, "a.js", text, "// 본문"))
	}

	fun testLocalInsideTestCallbackIsNotOwner() {
		val text = "describe('R', () => {\n  it('a', () => {\n    const r = new R() // 준비\n  })\n})\n"
		assertEquals(emptyList<String>(), ownersOf(project, "a.js", text, "// 준비"))
	}

	fun testCallbackParameterIsNotOwner() {
		val text = "app.get('/u', (req, res) => { // 조회\n  res.send(1)\n})\n"
		assertEquals(emptyList<String>(), ownersOf(project, "a.js", text, "// 조회"))
	}

	fun testObjectKeysInsideFunctionAreNotOwners() {
		val text = "export async function save(u) {\n  await fetch('/u', {\n    method: 'POST', // 생성\n  })\n}\n"
		assertEquals(listOf("save()"), ownersOf(project, "a.ts", text, "// 생성"))
	}

	fun testNamedArrowBodyIsLocalLikeFunction() {
		val arrow = "export const Card = () => {\n  // 제출\n  function onSubmit() {}\n}\n"
		val function = "export function Card() {\n  // 제출\n  function onSubmit() {}\n}\n"
		assertEquals(1, ownersOf(project, "a.tsx", arrow, "// 제출")?.size)
		assertEquals(listOf("Card()"), ownersOf(project, "a.tsx", function, "// 제출"))
	}

	fun testVarInAnonymousCallbackIsNotOwner() {
		for (callback in listOf("function ()", "(e) =>", "e =>")) {
			val text = "run($callback {\n  // 값\n  var x = 1\n})\n"
			assertEquals(callback, emptyList<String>(), ownersOf(project, "a.js", text, "// 값"))
		}
	}

	fun testFunctionValuedVarInsideIifeStaysOwner() {
		val text = "(function () {\n  // 도우미\n  var helper = function () {}\n})()\n"
		assertEquals(1, ownersOf(project, "a.js", text, "// 도우미")?.size)
	}

	fun testObjectLiteralMethodStaysOwner() {
		val text = "export const api = {\n  // 사용자 조회\n  getUser() {}\n}\n"
		assertEquals(listOf("api", "getUser()"), ownersOf(project, "a.ts", text, "// 사용자 조회"))
	}

	fun testNamespaceFunctionStaysOwner() {
		val text = "namespace N {\n  // 안쪽\n  function priv() {}\n}\n"
		assertEquals(listOf("N", "priv()"), ownersOf(project, "a.ts", text, "// 안쪽"))
	}

	// 클래스 본문이 {…} 노드인 언어. private 멤버는 범위가 클래스로 좁아도 선언
	fun testGroovyPrivateMethodStaysOwner() {
		val owners = ownersOf(project, "a.groovy", "class Repo {\n  // 캐시 비우기\n  private void evict() {}\n}\n", "// 캐시 비우기")
		assertEquals(2, owners?.size)
		assertTrue(owners.toString(), owners!![1].startsWith("evict"))
	}

	fun testFirstOfSeveralConsts() {
		assertEquals(listOf("a"), ownersOf(project, "a.ts", "// a 설명\nconst a = 1, b = 2\n", "// a 설명"))
	}

	fun testInterfaceFieldAndTypeAlias() {
		val text = "/** 사용자 */\nexport interface User {\n  // 식별자\n  id: string\n}\n\n// 상태\ntype Status = 'on' | 'off'\n"
		assertEquals(listOf("User"), ownersOf(project, "a.ts", text, "/** 사용자 */"))
		assertEquals(listOf("User", "id"), ownersOf(project, "a.ts", text, "// 식별자"))
		assertEquals(listOf("Status"), ownersOf(project, "a.ts", text, "// 상태"))
	}

	fun testPrivateMembersStayOwners() {
		val text = "class S {\n  // 비밀\n  private readonly secret = 1\n  // 도우미\n  private helper() {}\n}\n"
		assertEquals(listOf("S", "secret"), ownersOf(project, "a.ts", text, "// 비밀"))
		assertEquals(listOf("S", "helper()"), ownersOf(project, "a.ts", text, "// 도우미"))
	}

	fun testLocalHandlerInComponentIsNotOwner() {
		val text = "export function Card() {\n  // 클릭 핸들러\n  const onClick = () => {}\n  return <div onClick={onClick}/>\n}\n"
		assertEquals(listOf("Card()"), ownersOf(project, "a.tsx", text, "// 클릭 핸들러"))
	}

	fun testJsxTagsAreNotOwners() {
		val text = "export function Card() {\n  return (\n    <Flex>\n      {/* 안내 */}\n      <div/>\n    </Flex>\n  )\n}\n"
		assertEquals(listOf("Card()"), ownersOf(project, "a.tsx", text, "/* 안내 */"))
	}

	// 목록 항목 위 주석은 첫 항목이든 아니든 같게 붙도록. "- " 같은 기호는 넘어 넓히지 않음
	fun testYamlSequenceItemsAttachAlike() {
		val text = "containers:\n  # 메인\n  - name: app\n  # 사이드카\n  - name: proxy\n"
		assertEquals(ownersOf(project, "a.yaml", text, "# 사이드카"), ownersOf(project, "a.yaml", text, "# 메인"))
	}
}
