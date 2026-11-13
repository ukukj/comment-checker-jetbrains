package dev.commentchecker.lang.kotlin

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.commentchecker.core.Declarations
import dev.commentchecker.core.oneLine
import dev.commentchecker.model.Owner
import dev.commentchecker.model.OwnerKind
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtTypeAlias

class KotlinDeclarations : Declarations {
	override fun isMember(element: PsiElement): Boolean = when (element) {
		is KtObjectDeclaration -> !element.isObjectLiteral() && !element.isLocal && outerIsMember(element)
		is KtEnumEntry -> outerIsMember(element)
		is KtClassOrObject -> !element.isLocal && outerIsMember(element)
		is KtNamedFunction -> !element.isLocal && outerIsMember(element)
		is KtProperty -> !element.isLocal && outerIsMember(element)
		// getter/setter 는 프로퍼티를 따라감
		is KtPropertyAccessor -> isMember(element.property)
		is KtParameter -> element.hasValOrVar() && element.ownerFunction is KtPrimaryConstructor && outerIsMember(element)
		is KtSecondaryConstructor, is KtAnonymousInitializer -> outerIsMember(element)
		is KtTypeAlias -> element.isTopLevel() || outerIsMember(element)
		else -> false
	}

	/** 지역 클래스·object 식 안의 멤버는 바깥 클래스를 보고 걸러냄 */
	private fun outerIsMember(element: PsiElement): Boolean {
		val outer = PsiTreeUtil.getParentOfType(element, KtClassOrObject::class.java, true) ?: return true
		return isMember(outer)
	}

	override fun ownerOf(element: PsiElement): Owner = when (element) {
		is KtObjectDeclaration -> when {
			element.isCompanion() -> Owner(OwnerKind.OBJECT, element.name?.takeIf { it != "Companion" }
				?.let { "companion object $it" } ?: "companion object")
			else -> Owner(OwnerKind.OBJECT, element.name.orEmpty())
		}
		is KtEnumEntry -> Owner(OwnerKind.ENTRY, element.name.orEmpty())
		is KtClass -> Owner(
			when {
				element.isAnnotation() -> OwnerKind.ANNOTATION
				element.isInterface() -> OwnerKind.INTERFACE
				element.isEnum() -> OwnerKind.ENUM
				else -> OwnerKind.CLASS
			},
			element.name.orEmpty(),
		)
		is KtNamedFunction -> {
			val receiver = element.receiverTypeReference?.let { "${oneLine(it.text)}." }.orEmpty()
			val returns = element.typeReference?.let { ": ${oneLine(it.text)}" }.orEmpty()
			Owner(OwnerKind.FUNCTION, receiver + element.name.orEmpty(), "(${parameters(element.valueParameters)})$returns")
		}
		is KtProperty -> Owner(OwnerKind.PROPERTY, element.name.orEmpty())
		is KtPropertyAccessor -> Owner(
			OwnerKind.ACCESSOR,
			"${element.property.name}.${if (element.isGetter) "get" else "set"}",
			"()",
		)
		is KtParameter -> Owner(OwnerKind.PROPERTY, element.name.orEmpty())
		is KtSecondaryConstructor -> Owner(OwnerKind.CONSTRUCTOR, "constructor", "(${parameters(element.valueParameters)})")
		is KtAnonymousInitializer -> Owner(OwnerKind.INITIALIZER, "init", distinct = element.textRange.startOffset)
		is KtTypeAlias -> Owner(OwnerKind.TYPEALIAS, element.name.orEmpty())
		else -> Owner(OwnerKind.CLASS, element.text.take(20))
	}

	private fun parameters(params: List<KtParameter>) = params.joinToString(", ") {
		val vararg = if (it.isVarArg) "vararg " else ""
		"$vararg${it.name}: ${oneLine(it.typeReference?.text)}"
	}
}
