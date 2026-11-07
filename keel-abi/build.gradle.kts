import java.util.*

/**
 * 跨运行时 ABI 的唯一实现：C 源码 + 每目标静态库。
 *
 * `src/nativeCpp/keel_abi.h` 是 ABI 的唯一布局来源，`keel-api` 的 cinterop 指向它。
 * 静态库用 Kotlin/Native 自带的交叉 gcc 编译（与 K/N 链接时用的是同一套工具链），
 * 产物是 `<target>/libkeel_abi.a`，由 Host 与插件 dylib 在链接期各自并进一份。
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

kotlin {
    mingwX64()
    linuxArm64()
    linuxX64()

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }
}

// ---------------------------------------------------------------------------
// C 静态库
// ---------------------------------------------------------------------------

val cppDir = layout.projectDirectory.dir("src/nativeCpp")
val abiBuildDir = layout.buildDirectory.dir("abi")

/** K/N 下载的交叉工具链目录；目录名带版本号，按前缀找。 */
fun konanDependencies(): File {
    val root = System.getenv("KONAN_DATA_DIR")?.let(::File)
        ?: File(System.getProperty("user.home"), ".konan")
    return File(root, "dependencies")
}

/** 主机上可直接运行的目标；跨目标在对应平台上构建。 */
private fun hostTargetName(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("win") -> "mingwX64"
        arch.contains("aarch64") || arch.contains("arm64") -> "linuxArm64"
        else -> "linuxX64"
    }
}

private class CToolchain(val binDir: File, val gcc: String, val ar: String)

private fun toolchainFor(target: String): CToolchain {
    val deps = konanDependencies()
    fun findDir(prefix: String): File =
        deps.listFiles { file -> file.isDirectory && file.name.startsWith(prefix) }
            ?.maxByOrNull { it.name }
            ?: error("未找到 $prefix 工具链，请先让 Kotlin/Native 下载一次该目标的产物：${deps.absolutePath}")

    return when (target) {
        "mingwX64" -> {
            val dir = File(findDir("msys2-mingw-w64-x86_64"), "bin")
            CToolchain(dir, File(dir, "gcc.exe").absolutePath, File(dir, "ar.exe").absolutePath)
        }

        "linuxX64" -> {
            val dir = File(findDir("x86_64-unknown-linux-gnu-gcc"), "bin")
            CToolchain(
                dir,
                File(dir, "x86_64-unknown-linux-gnu-gcc").absolutePath,
                File(dir, "x86_64-unknown-linux-gnu-ar").absolutePath,
            )
        }

        "linuxArm64" -> {
            val dir = File(findDir("aarch64-unknown-linux-gnu-gcc"), "bin")
            CToolchain(
                dir,
                File(dir, "aarch64-unknown-linux-gnu-gcc").absolutePath,
                File(dir, "aarch64-unknown-linux-gnu-ar").absolutePath,
            )
        }

        else -> error("未知目标：$target")
    }
}

/**
 * 参与构建的目标。
 *
 * 默认只编当前主机目标；K/N 下载的 linux gcc 是 Linux 可执行文件，在 Windows 上跑不起来，
 * 所以跨目标构建放到对应平台（板子 / CI）上做，或用 `-Pkeel.abi.targets=linuxX64` 显式指定。
 */
val abiTargets: List<String> =
    (findProperty("keel.abi.targets") as String?)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.ifEmpty { null }
        ?: listOf(hostTargetName())

val objectTasks = abiTargets.associateWith { target ->
    tasks.register<Exec>("keelAbiObjects$target") {
        group = "keel"
        description = "用 K/N 自带交叉 gcc 编译 keel-abi 的 C 源码（$target）"

        val toolchain = toolchainFor(target)
        val outDir = abiBuildDir.map { it.dir("$target/obj").asFile }
        val sources = cppDir.asFile.listFiles { file -> file.extension == "c" }.orEmpty()

        inputs.files(sources.toList() + cppDir.file("keel_abi.h").asFile + cppDir.file("keel_platform.h").asFile)
        outputs.dir(outDir)
        workingDir = outDir.get()

        environment(
            "PATH",
            "${toolchain.binDir.absolutePath}${File.pathSeparator}${System.getenv("PATH")}",
        )
        doFirst { outDir.get().mkdirs() }
        commandLine(
            buildList {
                add(toolchain.gcc)
                addAll(listOf("-c", "-O2", "-fvisibility=hidden", "-std=c99", "-Wall", "-Wextra"))
                // 目标文件落在 outDir（workingDir），源码用绝对路径，避免污染源码目录
                addAll(sources.map { "-I${cppDir.asFile.absolutePath}" }.distinct())
                addAll(sources.map { it.absolutePath })
            }
        )
    }
}

val archiveTasks = abiTargets.associateWith { target ->
    tasks.register<Exec>("keelAbiArchive$target") {
        group = "keel"
        description = "把目标文件归档成 libkeel_abi.a（$target）"

        dependsOn(objectTasks.getValue(target))
        val toolchain = toolchainFor(target)
        val outDir = abiBuildDir.map { it.dir("$target").asFile }
        val objects = cppDir.asFile.listFiles { file -> file.extension == "c" }
            .orEmpty()
            .map { "obj/${it.nameWithoutExtension}.o" }

        outputs.file(outDir.map { File(it, "libkeel_abi.a") })
        // 没有输入声明时，改 .c 只会重编对象、归档保持旧文件
        inputs.dir(outDir.map { File(it, "obj") })
        workingDir = outDir.get()

        doFirst { outDir.get().mkdirs() }
        commandLine(listOf(toolchain.ar, "rcs", "libkeel_abi.a") + objects)
    }
}

val keelAbiPackage by tasks.registering(Zip::class) {
    group = "keel"
    description = "打包 ABI 头文件与各目标静态库"
    dependsOn(archiveTasks.values)
    archiveFileName.set("keel-abi-$version.zip")
    destinationDirectory.set(layout.buildDirectory.dir("keel"))
    abiTargets.forEach { target ->
        from(abiBuildDir.map { it.file("$target/libkeel_abi.a") }) { into("lib/$target") }
    }
    from(cppDir.file("keel_abi.h")) { into("include") }
}

tasks.named("assemble") { dependsOn(keelAbiPackage) }

// ---------------------------------------------------------------------------
// 发布
// ---------------------------------------------------------------------------

publishing {
    repositories {
        val repoFile = rootProject.file("../local.properties")
        val localProperties = Properties().apply {
            if (repoFile.isFile) repoFile.inputStream().use { load(it) }
        }
        val repoUrl = localProperties.getProperty("keel.repo").orEmpty()
        if (repoUrl.isNotBlank()) {
            maven {
                url = uri(repoUrl)
                isAllowInsecureProtocol = true
                val repoUsername = localProperties.getProperty("keel.username").orEmpty()
                val repoPassword = localProperties.getProperty("keel.password").orEmpty()
                if (repoUsername.isNotBlank() || repoPassword.isNotBlank()) {
                    credentials {
                        username = repoUsername
                        password = repoPassword
                    }
                }
            }
        }
    }
}
