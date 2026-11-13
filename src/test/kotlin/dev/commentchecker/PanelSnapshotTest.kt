package dev.commentchecker

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.commentchecker.core.ChangedCommentsCollector
import dev.commentchecker.model.FileComments
import dev.commentchecker.model.ScanResult
import dev.commentchecker.ui.CommentsPanel
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * 실제 저장소의 변경으로 Comments 패널을 그려 PNG 로 저장. IDE 창을 띄우지 않고 화면 확인용.
 * -PsnapshotRepo=<저장소> [-PsnapshotBase=dev] -PsnapshotOut=<png> 를 줄 때만 돎
 */
class PanelSnapshotTest : BasePlatformTestCase() {
	fun testRenderPanel() {
		val repo = System.getProperty("commentchecker.snapshot.repo").orEmpty()
		val out = System.getProperty("commentchecker.snapshot.out").orEmpty()
		if (repo.isEmpty() || out.isEmpty()) {
			return
		}
		val base = System.getProperty("commentchecker.snapshot.base").orEmpty().ifEmpty { "HEAD" }
		val rev = if (base == "HEAD") "HEAD" else git(repo, "merge-base", base, "HEAD").trim()

		val entries = git(repo, "diff", "--no-ext-diff", rev, "--name-status", "--no-renames", "-z").split('\u0000')
		val changed = (0 until entries.size - 1 step 2).map { i ->
			val status = when (entries[i].first()) {
				'A' -> FileStatus.ADDED
				'D' -> FileStatus.DELETED
				else -> FileStatus.MODIFIED
			}
			entries[i + 1] to status
		}
		// git add 안 한 새 파일. IDE 에선 Unversioned Files 로 잡힘
		val unversioned = git(repo, "ls-files", "--others", "--exclude-standard", "-z").split('\u0000')
			.filter { it.isNotEmpty() }
			.map { it to FileStatus.UNKNOWN }

		// 경로를 테스트 프로젝트 기준으로 둬야 패널에 저장소 안 폴더가 나옴. 내용은 저장소에서 읽음
		val root = project.basePath ?: repo
		val files = mutableListOf<FileComments>()
		val quiet = mutableListOf<Pair<com.intellij.openapi.vcs.FilePath, FileStatus>>()
		for ((rel, status) in changed + unversioned) {
			val path = LocalFilePath("$root/$rel", false)
			val language = ChangedCommentsCollector.languageOf(path.name) ?: continue
			val isNew = status == FileStatus.ADDED || status == FileStatus.UNKNOWN
			val before = if (isNew) null else git(repo, "show", "$rev:$rel")
			val after = if (status == FileStatus.DELETED) null else File(repo, rel).readText()
			val hits = ChangedCommentsCollector.commentChanges(project, path.name, language, before, after, EmptyProgressIndicator())
			if (hits.isEmpty()) quiet += path to status else files += FileComments(path, null, status, hits)
		}

		val panel = CommentsPanel(project)
		Disposer.register(testRootDisposable, panel)
		panel.showForTest(ScanResult(files, quiet, emptyList()))
		// -PsnapshotSize=600x380 -PsnapshotScale=2 처럼 주면 마켓 스크린샷 크기(1200x760)로
		val (width, height) = System.getProperty("commentchecker.snapshot.size").orEmpty().ifEmpty { "1000x720" }
			.split('x').map(String::toInt)
		val scale = System.getProperty("commentchecker.snapshot.scale").orEmpty().ifEmpty { "1" }.toInt()
		panel.setSize(width, height)
		layoutAll(panel)
		val image = BufferedImage(width * scale, height * scale, BufferedImage.TYPE_INT_ARGB)
		val g = image.createGraphics()
		g.scale(scale.toDouble(), scale.toDouble())
		panel.paint(g)
		g.dispose()
		ImageIO.write(image, "png", File(out))
		// 화면 한 장에 다 안 들어가서 전체 결과는 글로도 남김
		File("$out.txt").writeText(files.joinToString("\n") { file ->
			file.filePath.path.removePrefix("$root/") + "\n" + file.comments.joinToString("\n") { hit ->
				"  L${hit.startLine + 1} ${hit.status} [${hit.tag}] ${hit.headline.take(60)}  =>  ${hit.owners.joinToString(" › ") { it.name + it.signature }}"
			}
		} + "\n\nquiet: ${quiet.size}\n")
	}

	private fun layoutAll(component: Component) {
		component.doLayout()
		if (component is Container) {
			component.components.forEach(::layoutAll)
		}
	}

	private fun git(repo: String, vararg args: String): String {
		val process = ProcessBuilder(listOf("git", "-C", repo) + args).redirectErrorStream(false).start()
		val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
		check(process.waitFor() == 0) { "git ${args.joinToString(" ")} 실패" }
		return text
	}
}
