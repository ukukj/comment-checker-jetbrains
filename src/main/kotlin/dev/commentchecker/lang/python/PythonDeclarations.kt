package dev.commentchecker.lang.python

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SyntaxTraverser
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyDocStringOwner
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import dev.commentchecker.core.Declarations
import dev.commentchecker.core.DocComment
import dev.commentchecker.core.oneLine
import dev.commentchecker.model.Owner
import dev.commentchecker.model.OwnerKind

/** Python 은 중첩 def(데코레이터 래퍼 등)가 흔해서 함수 안의 함수도 선언으로 침 */
class PythonDeclarations : Declarations {
	override fun isMember(element: PsiElement) = element is PyClass || element is PyFunction

	override fun ownerOf(element: PsiElement): Owner = when (element) {
		is PyClass -> Owner(OwnerKind.CLASS, nameOf(element))
		is PyFunction -> {
			val params = element.parameterList.parameters.joinToString(", ") { oneLine(it.text) }
			val returns = element.annotation?.value?.let { " -> ${oneLine(it.text)}" }.orEmpty()
			Owner(OwnerKind.FUNCTION, nameOf(element), "($params)$returns")
		}
		else -> Owner(OwnerKind.OTHER, oneLine(element.text).take(20))
	}

	/** PyClass.name 은 실험적 API(PyAst*)로 연결돼 마켓 검증에 경고가 남음. 플랫폼 공통 인터페이스로 */
	private fun nameOf(element: PsiNamedElement) = element.name.orEmpty()

	/** docstring 은 문법상 문자열이라 주석 노드로는 안 잡힘. 모듈 docstring 은 주인 없이 */
	override fun docComments(file: PsiFile): List<DocComment> {
		return SyntaxTraverser.psiTraverser(file).filter(PyDocStringOwner::class.java).toList().mapNotNull { owner ->
			owner.docStringExpression?.let { DocComment(it, owner.takeUnless { o -> o is PyFile }) }
		}
	}
}
