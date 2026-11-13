package dev.commentchecker

import com.intellij.DynamicBundle
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.Locale
import java.util.Properties

class BundleTest : BasePlatformTestCase() {
	private val path = "messages.CommentCheckerBundle"
	private val loader = CommentCheckerBundle::class.java.classLoader

	private fun load(suffix: String): Properties {
		val stream = loader.getResourceAsStream("messages/CommentCheckerBundle$suffix.properties") ?: error("$suffix 번들 없음")
		return Properties().apply { stream.reader(Charsets.UTF_8).use(::load) }
	}

	private fun placeholders(text: String) = Regex("\\{\\d+}").findAll(text).map { it.value }.toSet()

	fun testKoreanHasSameKeysAndPlaceholders() {
		val en = load("")
		val ko = load("_ko")
		assertEquals(en.stringPropertyNames(), ko.stringPropertyNames())
		for (key in en.stringPropertyNames()) {
			assertEquals(key, placeholders(en.getProperty(key)), placeholders(ko.getProperty(key)))
		}
	}

	/** 코드에서 쓰는 키가 다 있고, 번들에 안 쓰는 키가 남지 않았는지 */
	fun testKeysMatchUsages() {
		val sources = File("src/main/kotlin").walk().filter { it.extension == "kt" }.map { it.readText() }.toList()
		assertTrue(sources.isNotEmpty())
		val used = sources.flatMap { Regex("message\\(\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }.toSet()
		val actions = Regex("<action id=\"([^\"]+)\"").findAll(File("src/main/resources/META-INF/plugin.xml").readText())
			.flatMap { m -> sequenceOf("action.${m.groupValues[1]}.text", "action.${m.groupValues[1]}.description") }.toSet()
		assertEquals(load("").stringPropertyNames(), used + actions)
	}

	/** 캐시가 로캘 없이 경로로만 잡혀 있어 비우고 읽어야 함. 플러그인이 뜰 때 이미 영어로 읽어 둠 */
	private fun resolve(locale: Locale): String {
		DynamicBundle.clearCache()
		try {
			return DynamicBundle.getResourceBundle(loader, path, locale).getString("panel.empty.noComments")
		} finally {
			DynamicBundle.clearCache()
		}
	}

	fun testResolvesKoreanForKoreanIde() {
		assertEquals("바뀐 줄에 주석이 없음", resolve(Locale.KOREAN))
	}

	/** 맥 시스템 언어가 한국어여도 IDE 가 영어면 영어로. JVM 기본 로캘로 넘어가면 안 됨 */
	fun testEnglishIdeOnKoreanSystemStaysEnglish() {
		val saved = Locale.getDefault()
		try {
			Locale.setDefault(Locale.KOREA)
			// IDE 가 영어면 플랫폼은 ENGLISH 대신 ROOT 로 찾음
			assertEquals("No comments on changed lines", resolve(Locale.ROOT))
			assertEquals("No comments on changed lines", resolve(Locale.ENGLISH))
		} finally {
			Locale.setDefault(saved)
		}
	}

	fun testPluginXmlActionUsesBundle() {
		val presentation = ActionManager.getInstance().getAction("CommentChecker.Show").templatePresentation
		assertEquals("Show Changed Comments", presentation.text)
		assertEquals("Show comments on changed lines in the Comments tab", presentation.description)
	}

	fun testFormatsArguments() {
		assertEquals("Comments 3 · Files 1/2 · Errors 1", CommentCheckerBundle.message("panel.summary.withErrors", 3, 1, 2, 1))
		assertEquals("(4 lines)", CommentCheckerBundle.message("tree.comment.lines", 4))
	}
}
