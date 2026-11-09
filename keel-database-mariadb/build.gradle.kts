import java.io.File
import org.gradle.api.publish.maven.MavenPublication

/**
 * MariaDB 能力包：纯 C 动态库 + `plugin.json` 打成 `.keel`。
 *
 * 与 Kotlin 插件的区别只有两点：没有 K/N 运行时，也没有 `lib/<target>` 之外的资源；
 * 因此这里不用 KMP，直接用 K/N 自带的交叉 gcc 编译（与 keel-abi 同一套工具链）。
 */
plugins {
    base
    `maven-publish`
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

apply(from = "../gradle/publishing.gradle.kts")

val cppDir = layout.projectDirectory.dir("src/nativeCpp")
val dbAbiDir = layout.projectDirectory.dir("../keel-database-api/src/nativeCpp")
val keelAbiDir = layout.projectDirectory.dir("../keel-abi/src/nativeCpp")
val outputRoot = layout.buildDirectory.dir("keel")

/** K/N 下载的交叉工具链目录；目录名带版本号，按前缀找。 */
fun konanDependencies(): File {
    val root = System.getenv("KONAN_DATA_DIR")?.let(::File)
        ?: File(System.getProperty("user.home"), ".konan")
    return File(root, "dependencies")
}

private class CToolchain(val binDir: File, val gcc: String)

private fun findToolchainDir(prefix: String): File {
    val deps = konanDependencies()
    return deps.listFiles { file -> file.isDirectory && file.name.startsWith(prefix) }
        ?.maxByOrNull { it.name }
        ?: error("未找到 $prefix 工具链，请先让 Kotlin/Native 下载一次该目标的产物：${deps.absolutePath}")
}

private fun toolchainFor(target: String): CToolchain = when (target) {
    "mingwX64" -> {
        val dir = File(findToolchainDir("msys2-mingw-w64-x86_64"), "bin")
        CToolchain(dir, File(dir, "gcc.exe").absolutePath)
    }

    "linuxX64" -> {
        val dir = File(findToolchainDir("x86_64-unknown-linux-gnu-gcc"), "bin")
        CToolchain(dir, File(dir, "x86_64-unknown-linux-gnu-gcc").absolutePath)
    }

    "linuxArm64" -> {
        val dir = File(findToolchainDir("aarch64-unknown-linux-gnu-gcc"), "bin")
        CToolchain(dir, File(dir, "aarch64-unknown-linux-gnu-gcc").absolutePath)
    }

    else -> error("未知目标：$target")
}

private fun hostTargetName(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        os.contains("win") -> "mingwX64"
        arch.contains("aarch64") || arch.contains("arm64") -> "linuxArm64"
        else -> "linuxX64"
    }
}

private fun libraryName(target: String): String =
    if (target.startsWith("mingw")) "keel_capability_mariadb.dll"
    else "libkeel_capability_mariadb.so"

/**
 * 参与构建的目标。
 *
 * 默认只编当前主机目标；交叉 gcc 是 Linux 可执行文件，在 Windows 上跑不起来，
 * 所以其它目标在对应平台构建，或用 `-Pkeel.db.targets=linuxArm64` 显式指定。
 */
val targets: List<String> =
    (findProperty("keel.db.targets") as String?)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.ifEmpty { null }
        ?: listOf(hostTargetName())

val sources = cppDir.asFile.listFiles { file -> file.extension == "c" }.orEmpty()

val libraryTasks = targets.associateWith { target ->
    tasks.register<Exec>("keelDatabaseLibrary$target") {
        group = "keel"
        description = "用 K/N 自带交叉 gcc 编译 MariaDB 能力包（$target）"

        val toolchain = toolchainFor(target)
        val outDir = outputRoot.map { it.dir("lib/$target").asFile }
        val library = outDir.map { File(it, libraryName(target)) }

        inputs.files(sources.toList() + listOf(dbAbiDir.file("keel_database.h").asFile, keelAbiDir.file("keel_abi.h").asFile))
        outputs.file(library)

        environment(
            "PATH",
            "${toolchain.binDir.absolutePath}${File.pathSeparator}${System.getenv("PATH")}",
        )
        doFirst { outDir.get().mkdirs() }
        commandLine(
            buildList {
                add(toolchain.gcc)
                add("-shared")
                if (!target.startsWith("mingw")) add("-fPIC")
                addAll(listOf("-O2", "-std=c99", "-Wall", "-Wextra"))
                add("-I${dbAbiDir.asFile.absolutePath}")
                add("-I${keelAbiDir.asFile.absolutePath}")
                add("-o")
                add(library.get().absolutePath)
                addAll(sources.map { it.absolutePath })
            }
        )
    }
}

val generateManifest = tasks.register("keelDatabaseManifest") {
    group = "keel"
    description = "生成能力包的 plugin.json（lib 只列本次构建的目标）"

    val output = layout.buildDirectory.file("keel/plugin.json")
    val libs = targets.associateWith { "lib/$it/${libraryName(it)}" }
    val versionText = version.toString()
    outputs.file(output)
    doLast {
        val entries = libs.entries.joinToString(",\n") { (target, path) ->
            "    \"$target\": \"$path\""
        }
        output.get().asFile.writeText(
            """
            {
              "id": "database.mariadb",
              "name": "MariaDB Driver",
              "kind": "capability",
              "version": "$versionText",
              "abiVersion": 1,
              "entry": "keel_capability_entry",
              "provides": ["database"],
              "schemes": ["mysql", "mariadb"],
              "lib": {
            $entries
              },
              "authors": ["lolosia"],
              "description": "MariaDB 数据库能力包"
            }
            """.trimIndent() + "\n"
        )
    }
}

val keelDatabasePackage by tasks.registering(Zip::class) {
    group = "keel"
    description = "把能力包打成 .keel（plugin.json + lib/<target>）"
    dependsOn(libraryTasks.values)
    dependsOn(generateManifest)

    archiveFileName.set("keel-database-mariadb-$version.keel")
    destinationDirectory.set(outputRoot)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    from(generateManifest.map { layout.buildDirectory.file("keel/plugin.json") }) { into("") }
    targets.forEach { target ->
        from(outputRoot.map { it.dir("lib/$target") }) { into("lib/$target") }
    }
}

tasks.named("assemble") { dependsOn(keelDatabasePackage) }

// 能力包以 .keel 工件发布，keelRun 通过 requires: ["database"] 自动解析
publishing {
    publications {
        create<MavenPublication>("keel") {
            artifactId = "keel-database-mariadb"
            // 必须保留 .keel 扩展名：Host 只扫描 plugins/<id>.keel
            artifact(keelDatabasePackage) { extension = "keel" }
        }
    }
}
