import java.io.File
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("moe.lolosia.keel.plugin")
    `maven-publish`
}

group = "moe.lolosia.keel.plugins"
version = libs.versions.keel.get()

apply(from = "../gradle/publishing.gradle.kts")

// 插件包以 .keel 工件发布；其它工程用
// keelPlugin.executable.include("moe.lolosia.keel.plugins:keel-plugin-console:<version>@keel") 引用
publishing {
    publications {
        create<MavenPublication>("keel") {
            artifactId = "keel-plugin-console"
            artifact(tasks.named("keelPackage")) { extension = "keel" }
        }
    }
}

keelPlugin {
    // 控制台窗口只存在于 Windows
    target("mingwX64")
    // helper 只把 native/ 目录进包（RC / 目标文件留在 build/console-work）
    resources("build/generated/console-stage")
}

kotlin {
    mingwX64 { binaries.sharedLib { baseName = "keel-plugin-console" } }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
        }
    }
}

// ---------------------------------------------------------------------------
// helper：纯 C 控制台宿主（独立进程，CREATE_NEW_CONSOLE）
// ---------------------------------------------------------------------------

/** K/N 下载的交叉工具链目录；目录名带版本号，按前缀找。 */
fun konanDependencies(): File {
    val root = System.getenv("KONAN_DATA_DIR")?.let(::File)
        ?: File(System.getProperty("user.home"), ".konan")
    return File(root, "dependencies")
}

/** 与 keel-abi 一样，用 K/N 自带的 msys2 工具链；找不到时给出可操作的报错。 */
fun mingwBin(): File {
    val dir = konanDependencies()
        .listFiles { file -> file.isDirectory && file.name.startsWith("msys2-mingw-w64-x86_64") }
        ?.maxByOrNull { it.name }
        ?.resolve("bin")
    return dir ?: error("未找到 msys2 工具链，请先让 Kotlin/Native 下载一次 mingwX64 产物：${konanDependencies().absolutePath}")
}

val helperSourceDir = layout.projectDirectory.dir("src/nativeCpp")
val helperOutputDir = layout.buildDirectory.dir("generated/console-stage/native")
val helperObjectDir = layout.buildDirectory.dir("generated/console-work/obj")
val helperRcFile = layout.buildDirectory.file("generated/console-work/console_host.rc")

/** 把版本号写进 RC 模板：避免给 windres 传带引号的 -D（不同版本的行为不一致）。 */
val consoleHelperRc by tasks.registering {
    group = "keel"
    description = "由模板生成 helper 的 RC（写入当前版本号）"

    val template = helperSourceDir.file("console_host.rc").asFile
    val version = project.version.toString()

    inputs.file(template)
    outputs.file(helperRcFile)
    doLast {
        val output = helperRcFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(template.readText().replace("@KEEL_VERSION@", version))
    }
}

val consoleHelperResource by tasks.registering(Exec::class) {
    group = "keel"
    description = "把 helper 的 RC（版本资源 + 图标）编译成目标文件"

    dependsOn(consoleHelperRc)

    val bin = mingwBin()
    val icon = helperSourceDir.file("keel-icon.ico").asFile
    val resourceObject = helperObjectDir.map { it.file("console_host_res.o") }

    inputs.files(helperRcFile, icon)
    outputs.file(resourceObject)

    doFirst { resourceObject.get().asFile.parentFile.mkdirs() }
    workingDir = helperSourceDir.asFile
    // windres 通过 PATH 找 gcc 做预处理；konan 的 msys2 不在系统 PATH 里
    environment("PATH", "${bin.absolutePath}${File.pathSeparator}${System.getenv("PATH")}")
    commandLine(
        File(bin, "windres.exe").absolutePath,
        "-I${helperSourceDir.asFile.absolutePath}",
        "-i${helperRcFile.get().asFile.absolutePath}",
        "-o${resourceObject.get().asFile.absolutePath}",
    )
}

val consoleHelper by tasks.registering(Exec::class) {
    group = "keel"
    description = "编译控制台 helper（输出 resources/native/keel-console-host.exe）"

    dependsOn(consoleHelperResource)

    val bin = mingwBin()
    val source = helperSourceDir.file("console_host.c").asFile
    val resourceObject = helperObjectDir.map { it.file("console_host_res.o") }
    val exe = helperOutputDir.map { it.file("keel-console-host.exe") }

    inputs.files(source, resourceObject)
    outputs.file(exe)

    doFirst { exe.get().asFile.parentFile.mkdirs() }
    workingDir = helperSourceDir.asFile
    environment("PATH", "${bin.absolutePath}${File.pathSeparator}${System.getenv("PATH")}")
    commandLine(
        File(bin, "gcc.exe").absolutePath,
        "-O2",
        "-s",
        "-municode",
        "-o", exe.get().asFile.absolutePath,
        source.absolutePath,
        resourceObject.get().asFile.absolutePath,
        "-luser32",
    )
}

tasks.named("keelPackage") {
    dependsOn(consoleHelper)
}
