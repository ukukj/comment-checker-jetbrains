package dev.commentchecker.core

import com.intellij.lang.Language
import com.intellij.lang.LanguageExtension
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import dev.commentchecker.model.Owner
import dev.commentchecker.model.OwnerKind
import java.util.concurrent.CancellationException

/** 주석의 주인이 될 수 있는 선언 판정. Java·Kotlin·Python 구현은 선택 의존 묶음에 있어 여기서 직접 참조하지 말 것 */
interface Declarations {
	fun isMember(element: PsiElement): Boolean

	fun ownerOf(element: PsiElement): Owner

	/** 주석 노드는 아니지만 문서 주석으로 칠 것(Python docstring)과 그 주인 선언. 파일 단위면 owner 는 null */
	fun docComments(file: PsiFile): List<DocComment> = emptyList()

	/** 이름 앞 키워드(export const, type, private readonly)가 선언 요소 밖 문장에 있을 수 있는지. 위 주석이 붙을 시작 위치 계산용 */
	val keywordsOutside: Boolean get() = false

	companion object {
		private val EP = LanguageExtension<Declarations>("io.github.ukukj.commentchecker.declarations")

		/** 언어 전용 규칙이 없으면 편집기 브레드크럼(클래스 › 메서드)을 빌려 씀. 그것도 없으면 주인 없이 주석만 */
		fun forFile(file: PsiFile): Declarations? = EP.forLanguage(file.language) ?: BreadcrumbsDeclarations.forLanguage(file.language)
	}
}

class DocComment(val element: PsiElement, val owner: PsiElement?)

fun oneLine(text: String?) = text?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

/** 브레드크럼이 받아 주는 이름 있는 요소를 선언으로 봄. if·for 같은 이름 없는 블록은 빼야 클래스·함수만 남음 */
class BreadcrumbsDeclarations(private val provider: BreadcrumbsProvider) : Declarations {
	override val keywordsOutside = true

	override fun isMember(element: PsiElement) = accepted(element) && !isLocal(element) && !inNamedFunctionBody(element)

	private fun accepted(element: PsiElement): Boolean {
		return element is PsiNamedElement
			&& !orDefault(null) { element.name }.isNullOrBlank()
			&& orDefault(false) { provider.acceptElement(element) }
			&& !isTagInCode(element)
	}

	/** JSX 태그. XML·HTML 에서는 태그가 구조지만 코드 안의 태그는 화면 마크업이라 소속으로 안 침 */
	private fun isTagInCode(element: PsiElement): Boolean {
		val xml = Language.findLanguageByID("XML") ?: return false
		return !element.language.isKindOf(xml) && IS_XML_TAG.get(element.javaClass)
	}

	/**
	 * 함수 매개변수·본문 안의 지역 선언. 브레드크럼은 이것도 받아 줌. 선언이 보이는 범위(use scope)를 쥔 요소로 가름:
	 * 블록·함수 안이면 지역(Go x := 1, JS const, 매개변수). 클래스면 private 멤버라 남김
	 */
	private fun isLocal(element: PsiElement): Boolean {
		val scope = orDefault(null) { element.useScope } as? LocalSearchScope ?: return false
		// JS 매개변수 범위에는 함수 앞 JSDoc 이 같이 들어 있음
		val holder = scope.scope.firstOrNull { it !is PsiComment } ?: return false
		if (holder is PsiFile) {
			return false
		}
		if (isWrapped(holder, "{", "}")) {
			return !accepted(holder) && !keptInWrapper(element, holder.parent)
		}
		val params = parameterLists(holder)
		if (params.isEmpty()) {
			return !accepted(holder)
		}
		if (params.any { PsiTreeUtil.isAncestor(it, element, false) }) {
			return true
		}
		return !keptInWrapper(element, holder)
	}

	/** 이름 없는 함수(IIFE·define 콜백) 본문의 함수는 모듈 수준으로 봄. var 함수 = function () {} 포함, 변수는 지역 */
	private fun keptInWrapper(element: PsiElement, function: PsiElement?): Boolean {
		return function != null && isFunction(function) && !isNamed(function) && functionOf(element) != null
	}

	/** 이름 있는 함수의 본문 안. 객체 속성·공개 멤버는 범위가 넓어 isLocal 로는 안 걸림 */
	private fun inNamedFunctionBody(element: PsiElement): Boolean {
		var node = element.parent ?: return false
		while (node !is PsiFile) {
			val parent = node.parent ?: return false
			if (isWrapped(node, "{", "}") && isFunction(parent) && isNamed(parent)) {
				return true
			}
			node = parent
		}
		return false
	}

	/** const Card = () => {} 처럼 이름이 변수에 있는 함수도 이름 있는 것으로 */
	private fun isNamed(function: PsiElement) = accepted(function) || function.parent?.let(::accepted) == true

	/** 매개변수 (…) 와 본문 {…} 이 다 있는 요소. 호출식은 본문이 없어 빠짐 */
	private fun isFunction(node: PsiElement): Boolean {
		return parameterLists(node).isNotEmpty() && children(node).any { isWrapped(it, "{", "}") }
	}

	/** 함수 자신이거나 함수를 담은 변수(var f = function () {}) */
	private fun functionOf(element: PsiElement): PsiElement? {
		return element.takeIf(::isFunction) ?: children(element).firstOrNull(::isFunction)
	}

	private fun children(node: PsiElement) = generateSequence(node.firstChild) { it.nextSibling }

	/** 함수의 (…) 목록. 바로 아래 자식(JS 매개변수, Go 리시버)이거나 그 첫 자식(Go 시그니처 안 매개변수) */
	private fun parameterLists(holder: PsiElement): List<PsiElement> {
		return children(holder)
			.flatMap { sequenceOf(it, it.firstChild) }
			.filterNotNull()
			.filter { isWrapped(it, "(", ")") }
			.toList()
	}

	private fun isWrapped(node: PsiElement, open: String, close: String): Boolean {
		val first = node.firstChild ?: return false
		val last = node.lastChild ?: return false
		return first !== last && first.textMatches(open) && last.textMatches(close)
	}

	override fun ownerOf(element: PsiElement): Owner {
		val info = orDefault(null) { provider.getElementInfo(element) }?.takeIf { it.isNotBlank() }
			?: (element as? PsiNamedElement)?.name.orEmpty()
		return Owner(OwnerKind.OTHER, oneLine(info).take(MAX_LABEL)).also {
			it.icon = orDefault(null) { provider.getElementIcon(element) }
		}
	}

	companion object {
		private const val MAX_LABEL = 80

		/** XML 모듈을 직접 참조하면 그 모듈이 없는 IDE 에서 못 뜸. 이름으로만 확인하고 클래스별로 기억 */
		private val IS_XML_TAG = object : ClassValue<Boolean>() {
			override fun computeValue(type: Class<*>) = implements(type, "com.intellij.psi.xml.XmlTag")
		}

		private fun implements(type: Class<*>, name: String): Boolean {
			return generateSequence(type) { it.superclass }.any { c -> c.interfaces.any { it.name == name || implements(it, name) } }
		}

		/** BreadcrumbsProvider.EP_NAME 은 내부 API 라 마켓 심사에 걸림. 공개 확장 지점을 이름으로 직접 조회 */
		private val BREADCRUMBS = ExtensionPointName<BreadcrumbsProvider>("com.intellij.breadcrumbsInfoProvider")

		fun forLanguage(language: Language): Declarations? = BREADCRUMBS.extensionList
			.firstOrNull { provider -> provider.languages.any { language.isKindOf(it) } }
			?.let(::BreadcrumbsDeclarations)
	}
}

/**
 * 남의 플러그인 코드가 던진 예외는 기본값으로. 인덱싱 중(IndexNotReadyException)도 여기로.
 * 취소(ProcessCanceledException)는 IDE 가 읽기 작업을 멈추라는 신호라 삼키면 입력이 멎음. 다시 던짐
 */
private inline fun <T> orDefault(default: T, block: () -> T): T {
	return try {
		block()
	} catch (e: Exception) {
		if (e is ControlFlowException || e is CancellationException) {
			throw e
		}
		default
	}
}
