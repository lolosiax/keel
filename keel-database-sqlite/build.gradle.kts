import groovy.json.JsonSlurper
import java.io.File
import org.gradle.api.publish.maven.MavenPublication

/**
 * SQLite 能力包：纯 C 动态库 + `plugin.json` 打成 `.keel`。
 *
 * 与 `keel-database-mariadb` 的差别只在依赖：这里从 `keel-thirdparty` 消费
 * `sqlite-<target>` 的 `include/` + `libsqlite3.a`，静态并进能力包，
 * 部署时 `lib/<target>/` 只有入口库一个文件。
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

// SQLite 的坐标来自 keel-thirdparty 的 manifest：版本 + revision → `<version>-keel.<revision>`
val thirdpartyManifest = JsonSlurper()
    .parse(layout.projectDirectory.file("../keel-thirdparty/manifest.json").asFile) as Map<*, *>
@Suppress("UNCHECKED_CAST")
val sqliteSpec = (thirdpartyManifest["libraries"] as Map<String, Map<String, Any?>>).getValue("sqlite")
val sqliteVersion = sqliteSpec["version"].toString()
val sqliteRevision = (sqliteSpec["revision"] as Number).toInt()
val thirdpartyVersion = "$sqliteVersion-keel.$sqliteRevision"

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
    if (target.startsWith("mingw")) "keel_capability_sqlite.dll"
    else "libkeel_capability_sqlite.so"

/** 默认只编当前主机目标；其它目标在对应平台构建，或用 `-Pkeel.db.targets=linuxArm64` 指定。 */
val targets: List<String> =
    (findProperty("keel.db.targets") as String?)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.ifEmpty { null }
        ?: listOf(hostTargetName())

val sources = cppDir.asFile.listFiles { file -> file.extension == "c" }.orEmpty()

/** 每个目标一份 thirdparty 依赖：zip 里是 include/sqlite 的头文件与 lib 下的 libsqlite3.a。 */
val thirdpartyConfigs = targets.associateWith { target ->
    val name = "keelSqliteNative${target.replaceFirstChar { it.uppercaseChar() }}"
    val configuration = configurations.create(name) {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        description = "SQLite 静态库与头文件（$target）"
    }
    dependencies.add(
        configuration.name,
        "moe.lolosia.keel.thirdparty:sqlite-$target:$thirdpartyVersion@zip",
    )
    configuration
}

/**
 * 第三方产物的取用方式：
 * - 仓库内开发（存在 `../keel-thirdparty`）→ 直接读 `build/dist`，并由任务图自动触发
 *   `:stageSqlite<target>`（进而编译 sqlite）；可用 `-Pkeel.thirdparty.dist=<dir>` 覆盖；
 * - 单独 clone 本目录（没有兄弟目录）→ 按 Maven 坐标解析已发布的 zip。
 */
val thirdpartyProjectDir = layout.projectDirectory.dir("../keel-thirdparty").asFile
val distOverride: File? =
    (findProperty("keel.thirdparty.dist") as String?)?.let { file(it) }
        ?: File(thirdpartyProjectDir, "build/dist").takeIf { thirdpartyProjectDir.isDirectory }

val unpackTasks = targets.associateWith { target ->
    tasks.register("keelSqliteUnpack$target") {
        group = "keel"
        description = "解出 SQLite 的 include/ 与 libsqlite3.a（$target）"
        val output = layout.buildDirectory.dir("thirdparty/$target")
        outputs.dir(output)
        // 仓库内：把生成侧的 stage 任务接进同一张任务图
        if (thirdpartyProjectDir.isDirectory) {
            val stage = "stageSqlite${target.replaceFirstChar { it.uppercaseChar() }}"
            dependsOn(gradle.includedBuild("keel-thirdparty").task(":$stage"))
        }
        doLast {
            val out = output.get().asFile
            out.deleteRecursively()
            out.mkdirs()
            if (distOverride != null) {
                val source = File(distOverride, "sqlite/$target")
                check(source.isDirectory) {
                    "keel.thirdparty.dist 里没有 sqlite/$target：${source.absolutePath}"
                }
                copy { from(File(source, "include")); into(File(out, "include")) }
                copy { from(File(source, "lib")); into(File(out, "lib")) }
            } else {
                // 发布 zip 的布局是 lib/<target>/libsqlite3.a，规整成 include/ + lib/
                val zip = thirdpartyConfigs.getValue(target).singleFile
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

val libraryTasks = targets.associateWith { target ->
    tasks.register<Exec>("keelDatabaseLibrary$target") {
        group = "keel"
        description = "用 K/N 自带交叉 gcc 编译 SQLite 能力包（$target）"

        val toolchain = toolchainFor(target)
        val thirdparty = layout.buildDirectory.dir("thirdparty/$target")
        val outDir = outputRoot.map { it.dir("lib/$target").asFile }
        val library = outDir.map { File(it, libraryName(target)) }

        dependsOn(unpackTasks.getValue(target))
        inputs.files(
            sources.toList() + listOf(
                dbAbiDir.file("keel_database.h").asFile,
                keelAbiDir.file("keel_abi.h").asFile,
                keelAbiDir.file("keel_platform.h").asFile,
            )
        )
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
                add("-I${File(thirdparty.get().asFile, "include/sqlite").absolutePath}")
                add("-o")
                add(library.get().absolutePath)
                addAll(sources.map { it.absolutePath })
                add(File(thirdparty.get().asFile, "lib/libsqlite3.a").absolutePath)
                if (target.startsWith("mingw")) {
                    addAll(listOf("-lws2_32"))
                } else {
                    addAll(listOf("-lm", "-lpthread"))
                }
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
              "id": "database.sqlite",
              "name": "SQLite Driver",
              "kind": "capability",
              "version": "$versionText",
              "abiVersion": 1,
              "entry": "keel_capability_entry",
              "provides": ["database"],
              "schemes": ["sqlite"],
              "dialect": "sqlite",
              "features": ["transactions", "prepared", "last_insert_id"],
              "lib": {
            $entries
              },
              "authors": ["lolosia"],
              "description": "SQLite 数据库能力包（静态链接 amalgamation）"
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

    archiveFileName.set("keel-database-sqlite-$version.keel")
    destinationDirectory.set(outputRoot)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    from(generateManifest.map { layout.buildDirectory.file("keel/plugin.json") }) { into("") }
    targets.forEach { target ->
        from(outputRoot.map { it.dir("lib/$target") }) { into("lib/$target") }
    }
}

tasks.named("assemble") { dependsOn(keelDatabasePackage) }

publishing {
    publications {
        create<MavenPublication>("keel") {
            artifactId = "keel-database-sqlite"
            // 必须保留 .keel 扩展名：Host 只扫描 plugins/<id>.keel
            artifact(keelDatabasePackage) { extension = "keel" }
        }
    }
}
