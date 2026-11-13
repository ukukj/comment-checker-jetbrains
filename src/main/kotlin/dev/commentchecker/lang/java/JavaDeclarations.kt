package dev.commentchecker.lang.java

import com.intellij.psi.PsiAnonymousClass
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassInitializer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.util.PsiUtil
import dev.commentchecker.core.Declarations
import dev.commentchecker.model.Owner
import dev.commentchecker.model.OwnerKind

class JavaDeclarations : Declarations {
	override fun isMember(element: PsiElement): Boolean = when (element) {
		is PsiTypeParameter, is PsiAnonymousClass -> false
		is PsiClass -> !PsiUtil.isLocalOrAnonymousClass(element) && (element.containingClass?.let(::isMember) ?: true)
		// 익명 클래스 안의 메서드는 containingClass 가 익명 클래스라 여기서 걸러짐
		is PsiMethod, is PsiField, is PsiClassInitializer -> (element as PsiMember).containingClass?.let(::isMember) ?: false
		else -> false
	}

	override fun ownerOf(element: PsiElement): Owner = when (element) {
		is PsiClass -> Owner(classKind(element), element.name.orEmpty())
		is PsiMethod -> {
			val params = element.parameterList.parameters.joinToString(", ") { it.type.presentableText }
			val returns = element.returnType?.let { ": ${it.presentableText}" }.orEmpty()
			Owner(if (element.isConstructor) OwnerKind.CONSTRUCTOR else OwnerKind.FUNCTION, element.name, "($params)$returns")
		}
		is PsiEnumConstant -> Owner(OwnerKind.ENTRY, element.name)
		is PsiField -> Owner(OwnerKind.FIELD, element.name)
		is PsiClassInitializer -> {
			val isStatic = element.hasModifierProperty(PsiModifier.STATIC)
			Owner(OwnerKind.INITIALIZER, if (isStatic) "static {...}" else "{...}", distinct = element.textRange.startOffset)
		}
		else -> Owner(OwnerKind.CLASS, element.text.take(20))
	}

	private fun classKind(cls: PsiClass) = when {
		cls.isAnnotationType -> OwnerKind.ANNOTATION
		cls.isInterface -> OwnerKind.INTERFACE
		cls.isEnum -> OwnerKind.ENUM
		cls.isRecord -> OwnerKind.RECORD
		else -> OwnerKind.CLASS
	}
}
