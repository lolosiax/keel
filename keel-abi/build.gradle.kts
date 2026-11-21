import groovy.json.JsonSlurper
import org.gradle.api.publish.maven.MavenPublication

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

private class CToolchain(
    val binDir: File,
    val compiler: String,
    val ar: String,
    /** 编译器附加参数（交叉编译时的 target / sysroot / 特性宏）。 */
    val args: List<String> = emptyList(),
)

/** 按前缀找 konan 下载的工具链目录；目录名带版本号，取最新的一个。 */
private fun konanDir(prefix: String): File {
    val deps = konanDependencies()
    return deps.listFiles { file -> file.isDirectory && file.name.startsWith(prefix) }
        ?.maxByOrNull { it.name }
        ?: error("未找到 $prefix 工具链，请先让 Kotlin/Native 下载一次该目标的产物：${deps.absolutePath}")
}

private fun toolchainFor(target: String): CToolchain =
    if (target == hostTargetName()) hostToolchain(target) else crossToolchain(target)

/** 主机目标：直接用 konan 里与 K/N 链接同一套的本机工具链。 */
private fun hostToolchain(target: String): CToolchain = when (target) {
    "mingwX64" -> {
        val dir = File(konanDir("msys2-mingw-w64-x86_64"), "bin")
        CToolchain(dir, File(dir, "gcc.exe").absolutePath, File(dir, "ar.exe").absolutePath)
    }

    "linuxX64" -> {
        val dir = File(konanDir("x86_64-unknown-linux-gnu-gcc"), "bin")
        CToolchain(
            dir,
            File(dir, "x86_64-unknown-linux-gnu-gcc").absolutePath,
            File(dir, "x86_64-unknown-linux-gnu-ar").absolutePath,
        )
    }

    "linuxArm64" -> {
        val dir = File(konanDir("aarch64-unknown-linux-gnu-gcc"), "bin")
        CToolchain(
            dir,
            File(dir, "aarch64-unknown-linux-gnu-gcc").absolutePath,
            File(dir, "aarch64-unknown-linux-gnu-ar").absolutePath,
        )
    }

    else -> error("未知目标：$target")
}

/**
 * 跨目标：konan 里的 linux gcc 是目标平台的可执行文件（Windows / 异架构主机上跑不起来），
 * 但同一份 konan 目录里还有本机可执行的 LLVM 工具链；配合 linux sysroot 直接产出目标 ELF
 * 目标文件，静态归档交给 `llvm-ar`（与架构无关）。
 */
private fun crossToolchain(target: String): CToolchain {
    val triple = when (target) {
        "linuxX64" -> "x86_64-unknown-linux-gnu"
        "linuxArm64" -> "aarch64-unknown-linux-gnu"
        else -> error("目标 $target 暂不支持交叉编译")
    }
    val bin = File(konanDir("llvm-"), "bin")
    val exe = if (System.getProperty("os.name").lowercase().contains("win")) ".exe" else ""
    val sysroot = File(konanDir("$triple-gcc-"), "$triple/sysroot")
    return CToolchain(
        bin,
        File(bin, "clang$exe").absolutePath,
        File(bin, "llvm-ar$exe").absolutePath,
        listOf(
            "--target=$triple",
            "--sysroot=${sysroot.absolutePath}",
            // 严格 c99 下 glibc 不暴露 clock_gettime 等 POSIX 接口
            "-D_POSIX_C_SOURCE=200809L",
        ),
    )
}

/**
 * 参与构建的目标。
 *
 * 默认覆盖消费方会链接的全部目标：`keel-host` / `keel-launcher` 都声明了 mingwX64 与两个 linux 目标，
 * ABI 包缺任何一个都会在那一边链接失败。Windows 主机用交叉 clang 编 linux，
 * 其它主机只编 linux（K/N 不支持在非 Windows 主机链接 mingw 目标）。
 * 仍可用 `-Pkeel.abi.targets=...` 显式指定。
 */
private fun defaultTargets(): List<String> =
    if (hostTargetName() == "mingwX64") listOf("mingwX64", "linuxX64", "linuxArm64")
    else listOf("linuxX64", "linuxArm64")

val abiTargets: List<String> =
    (findProperty("keel.abi.targets") as String?)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.ifEmpty { null }
        ?: defaultTargets()

/**
 * 预编译目标：只参与打包，不执行编译与归档。
 *
 * 已有静态库的平台（`build/abi/<target>/libkeel_abi.a`）可以直接复用，
 * 用 `-Pkeel.abi.prebuilt=linuxX64,linuxArm64` 跳过这两个目标的本机构建。
 */
val prebuiltTargets: Set<String> =
    (findProperty("keel.abi.prebuilt") as String?)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()

/** 需要在本机编译的目标；[prebuiltTargets] 里的直接复用已有静态库。 */
val compiledTargets: List<String> = abiTargets.filterNot { it in prebuiltTargets }

val objectTasks = compiledTargets.associateWith { target ->
    tasks.register<Exec>("keelAbiObjects$target") {
        group = "keel"
        description = "用 K/N 自带工具链编译 keel-abi 的 C 源码（$target）"

        val toolchain = toolchainFor(target)
        val outDir = abiBuildDir.map { it.dir("$target/obj").asFile }
        val sources = cppDir.asFile.listFiles { file -> file.extension == "c" }.orEmpty()

        inputs.files(
            sources.toList() +
                cppDir.file("keel_abi.h").asFile +
                cppDir.file("keel_platform.h").asFile +
                cppDir.file("keel_zstd.h").asFile
        )
        outputs.dir(outDir)
        workingDir = outDir.get()

        environment(
            "PATH",
            "${toolchain.binDir.absolutePath}${File.pathSeparator}${System.getenv("PATH")}",
        )
        doFirst { outDir.get().mkdirs() }
        commandLine(
            buildList {
                add(toolchain.compiler)
                addAll(toolchain.args)
                addAll(listOf("-c", "-O2", "-fvisibility=hidden", "-std=c99", "-Wall", "-Wextra"))
                // 目标文件落在 outDir（workingDir），源码用绝对路径，避免污染源码目录
                addAll(sources.map { "-I${cppDir.asFile.absolutePath}" }.distinct())
                addAll(sources.map { it.absolutePath })
            }
        )
    }
}

// ---------------------------------------------------------------------------
// zstd 解压实现（keel-thirdparty）
// ---------------------------------------------------------------------------

/**
 * zstd 解压库来自 `keel-thirdparty` 的上游源码构建：只编 `common/` 与 `decompress/`，
 * 产物 `libzstd.a` 在这里解出目标文件后并入 `libkeel_abi.a`，消费方仍然只链一份静态库。
 *
 * 版本以 manifest 的 `version` + `revision` 为准；单独 clone（没有兄弟目录）时退回
 * 同一坐标的已发布工件。
 */
val zstdVersion: String = run {
    val fallback = "1.5.6-keel.1"
    val manifest = file("../keel-thirdparty/manifest.json")
    if (!manifest.isFile) return@run fallback
    @Suppress("UNCHECKED_CAST")
    val root = JsonSlurper().parse(manifest) as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val spec = (root["libraries"] as? Map<String, Any?>)?.get("zstd") as? Map<String, Any?>
    val version = spec?.get("version")?.toString().orEmpty()
    val revision = (spec?.get("revision") as? Number)?.toInt() ?: 1
    if (version.isBlank()) fallback else "$version-keel.$revision"
}

val thirdpartyProjectDir = layout.projectDirectory.dir("../keel-thirdparty").asFile

/** 仓库内开发直接读 `keel-thirdparty/build/dist`；可用 `-Pkeel.thirdparty.dist=<dir>` 覆盖。 */
val thirdpartyDist: File? =
    (findProperty("keel.thirdparty.dist") as String?)?.let(::file)
        ?: File(thirdpartyProjectDir, "build/dist").takeIf { thirdpartyProjectDir.isDirectory }

private fun targetSuffix(target: String): String = target.replaceFirstChar { it.uppercaseChar() }

val zstdConfigs = compiledTargets.associateWith { target ->
    val name = "keelZstd${targetSuffix(target)}"
    val configuration = configurations.create(name) {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        description = "zstd 解压静态库（$target）"
    }
    dependencies.add(name, "moe.lolosia.keel.thirdparty:zstd-$target:$zstdVersion@zip")
    configuration
}

val zstdUnpackTasks = compiledTargets.associateWith { target ->
    tasks.register("keelAbiZstdUnpack$target") {
        group = "keel"
        description = "解出 zstd 的 include/ 与 libzstd.a（$target）"
        val output = layout.buildDirectory.dir("thirdparty/zstd/$target")
        outputs.dir(output)
        if (thirdpartyProjectDir.isDirectory) {
            dependsOn(gradle.includedBuild("keel-thirdparty").task(":stageZstd${targetSuffix(target)}"))
        }
        doLast {
            val out = output.get().asFile
            out.deleteRecursively()
            out.mkdirs()
            if (thirdpartyDist != null) {
                val source = File(thirdpartyDist, "zstd/$target")
                check(source.isDirectory) {
                    "keel.thirdparty.dist 里没有 zstd/$target：${source.absolutePath}"
                }
                copy { from(File(source, "lib")); into(File(out, "lib")) }
            } else {
                // 发布 zip 的布局是 lib/<target>/libzstd.a，规整成 lib/
                val zip = zstdConfigs.getValue(target).singleFile
                copy { from(zipTree(zip)); into(out) }
                val nested = File(out, "lib/$target")
                if (nested.isDirectory) {
                    nested.listFiles().orEmpty().forEach { file ->
                        file.renameTo(File(File(out, "lib"), file.name))
                    }
                    nested.deleteRecursively()
                }
            }
        }
    }
}

val zstdObjectTasks = compiledTargets.associateWith { target ->
    tasks.register<Exec>("keelAbiZstdObjects$target") {
        group = "keel"
        description = "从 libzstd.a 解出目标文件，供归档并进 libkeel_abi.a（$target）"

        val archive = layout.buildDirectory.file("thirdparty/zstd/$target/lib/libzstd.a")
        val objDir = abiBuildDir.map { it.dir("$target/zstd-obj").asFile }
        dependsOn(zstdUnpackTasks.getValue(target))
        inputs.file(archive)
        outputs.dir(objDir)
        workingDir = objDir.get()
        doFirst {
            val out = objDir.get()
            out.deleteRecursively()
            out.mkdirs()
        }
        commandLine(toolchainFor(target).ar, "x", archive.get().asFile.absolutePath)
    }
}

val archiveTasks = compiledTargets.associateWith { target ->
    tasks.register<Exec>("keelAbiArchive$target") {
        group = "keel"
        description = "把目标文件归档成 libkeel_abi.a（$target）"

        dependsOn(objectTasks.getValue(target), zstdObjectTasks.getValue(target))
        val toolchain = toolchainFor(target)
        val outDir = abiBuildDir.map { it.dir("$target").asFile }
        val zstdObjDir = abiBuildDir.map { it.dir("$target/zstd-obj").asFile }

        outputs.file(outDir.map { File(it, "libkeel_abi.a") })
        // 没有输入声明时，改 .c 只会重编对象、归档保持旧文件
        inputs.dir(outDir.map { File(it, "obj") })
        inputs.dir(zstdObjDir)
        workingDir = outDir.get()

        doFirst {
            val out = outDir.get()
            out.mkdirs()
            // ar 只增不删：先删掉旧归档，否则删掉的成员会一直留在里面
            File(out, "libkeel_abi.a").delete()
            // 只归档当前源码对应的目标文件，避免删掉源码后残留的旧 .o 被一并打进去
            val own = cppDir.asFile.listFiles { file -> file.extension == "c" }.orEmpty()
                .map { File(File(out, "obj"), it.nameWithoutExtension + ".o") }
            // zstd 的目标文件由 libzstd.a 解出（该目录每次执行前会清空）
            val zstd = zstdObjDir.get().listFiles { file -> file.extension == "o" }.orEmpty().asList()
            val objects = (own + zstd).sortedBy { it.name }
            commandLine(listOf(toolchain.ar, "rcs", "libkeel_abi.a") + objects.map { it.absolutePath })
        }
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
    // bundle 的 zstd 解压声明；实现（keel-thirdparty 编译的 libzstd.a）已并进静态库
    from(cppDir.file("keel_zstd.h")) { into("include") }
}

tasks.named("assemble") { dependsOn(keelAbiPackage) }

// ---------------------------------------------------------------------------
// 发布
// ---------------------------------------------------------------------------

apply(from = "../gradle/publishing.gradle.kts")

/**
 * 发布 `keel-abi-<version>.zip`（`include/keel_abi.h` + 各目标静态库）。
 *
 * 消费方（`keel-abi-gradle-plugin`）在独立构建里按 `moe.lolosia.keel:keel-abi:<version>@zip`
 * 解析这个工件；打包产物与 composite 下的本地路径是同一个文件内容。
 */
publishing {
    publications {
        create<MavenPublication>("keelAbi") {
            artifactId = "keel-abi"
            artifact(keelAbiPackage)
        }
    }
}
