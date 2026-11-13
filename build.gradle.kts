import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "io.github.ukukj"
version = "1.0.0"

// 설치된 IDE 를 그대로 빌드 대상으로. 버전 불일치 없고 IDE 를 따로 내려받지 않음
val localIde = providers.gradleProperty("localIde").orElse("/Applications/IntelliJ IDEA.app")

// Python 플러그인은 IntelliJ 기본 탑재가 아니라 마켓에서 설치한 것을 그대로 씀. 컴파일·테스트에만 필요
val pythonPlugin = providers.gradleProperty("pythonPlugin")
    .orElse("${System.getProperty("user.home")}/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins/python-ce")

val userPlugins = "${System.getProperty("user.home")}/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins"
val goPlugin = providers.gradleProperty("goPlugin").orElse("$userPlugins/go-plugin")
val goTemplatePlugin = providers.gradleProperty("goTemplatePlugin").orElse("$userPlugins/go-template")
// Go 플러그인은 유료판에서 직접 설치한 것이라 없는 컴퓨터가 있음. 없으면 Go 테스트를 통째로 뺌(통과로 세지 않도록)
val hasGo = file(goPlugin.get()).isDirectory && file(goTemplatePlugin.get()).isDirectory

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(localIde)
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        localPlugin(pythonPlugin)
        // 테스트 전용. 본 코드에서 이 플러그인들의 클래스를 참조하지 못하도록 컴파일 대상에서 뺌
        testBundledPlugin("JavaScript")
        testBundledPlugin("org.jetbrains.plugins.yaml")
        testBundledPlugin("org.intellij.groovy")
        testBundledPlugin("org.jetbrains.plugins.textmate")
        if (hasGo) {
            testLocalPlugin(goTemplatePlugin)
            testLocalPlugin(goPlugin)
        }
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // IDE 에 들어 있는 stdlib 보다 새 API 를 쓰지 않도록
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    // 마켓플레이스 심사와 같은 검사. 설치된 IDE 대상으로만 돌려 IDE 를 따로 내려받지 않음
    pluginVerification {
        ides {
            local(file(localIde.get()))
            // -PverifyPyCharm 을 줄 때만. Java·Kotlin 없는 IDE 에서 로드되는지 확인용 (PyCharm 을 내려받음)
            if (providers.gradleProperty("verifyPyCharm").isPresent) {
                create(IntelliJPlatformType.PyCharm, "2026.1")
            }
        }
    }
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            // 상한을 두지 않음. IDE 업데이트 때 꺼지지 않는 대신 API 가 바뀌면 오류로 드러나니 새 메이저마다 검증기를 돌릴 것
            untilBuild = provider { null }
        }
        changeNotes = """
            <b>1.0.0</b>
            <ul>
              <li>First public release.</li>
              <li>Comments tab in the Commit tool window: comments on changed lines, grouped by file, class and method,
                  with added, modified and deleted marks.</li>
              <li>Dedicated rules for Java, Kotlin and Python. Other languages use the editor breadcrumbs
                  (verified on JavaScript, TypeScript and Go).</li>
              <li>Files whose language plugin is not installed are listed as not checked.</li>
              <li>English and Korean UI.</li>
            </ul>
        """.trimIndent()
    }
    // 마켓에 올릴 때만 씀. 값은 환경 변수로만 넘기고 저장소에 두지 않음
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks.test {
    systemProperty("idea.kotlin.plugin.use.k2", "true")
    // PanelSnapshotTest 는 검증이 아니라 화면 그리기라 -PsnapshotRepo 를 줄 때만 돌림
    for (name in listOf("repo", "base", "out", "size", "scale")) {
        val value = providers.gradleProperty("snapshot${name.replaceFirstChar(Char::uppercase)}").getOrElse("")
        systemProperty("commentchecker.snapshot.$name", value)
    }
    if (!providers.gradleProperty("snapshotRepo").isPresent) {
        exclude("**/PanelSnapshotTest*")
    }
    if (!hasGo) {
        exclude("**/GoLanguageTest*")
    }
}
