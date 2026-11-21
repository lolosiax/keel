import groovy.json.JsonSlurper
import java.io.File
import org.gradle.api.publish.maven.MavenPublication

/**
 * MariaDB 能力包：纯 C 动态库 + `plugin.json` 打成 `.keel`。
 *
 * 与 SQLite 的差别是链接方式：Connector/C 以 **动态库** 消费（LGPL），
 * 所以 `lib/<target>/` 同时放入口库与 `libmariadb.dll`，Host 会整目录物化。
 * Connector/C 由 `keel-thirdparty` 从源码构建（当前仅 mingwX64）。
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

    else -> error("MariaDB 能力包当前只支持 mingwX64：$target")
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
 * Connector/C 目前只接了 mingwX64（源码构建用 Schannel；Linux 还需要 OpenSSL 与
 * `$ORIGIN` rpath），其它平台先跳过，避免把 `buildAll` 卡死。
 */
val requestedTargets: List<String> =
    (findProperty("keel.db.targets") as String?)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.ifEmpty { null }
        ?: listOf(hostTargetName())

val targets: List<String> = requestedTargets.filter { it == "mingwX64" }

if (targets.isEmpty()) {
    logger.warn("keel-database-mariadb: Connector/C 目前只支持 mingwX64，跳过能力包构建")
}

val sources = cppDir.asFile.listFiles { file -> file.extension == "c" }.orEmpty()

// Connector/C 的坐标来自 keel-thirdparty manifest：版本 + revision → `<version>-keel.<revision>`
val thirdpartyManifest = JsonSlurper()
    .parse(layout.projectDirectory.file("../keel-thirdparty/manifest.json").asFile) as Map<*, *>
@Suppress("UNCHECKED_CAST")
val mariadbSpec = (thirdpartyManifest["libraries"] as Map<String, Map<String, Any?>>).getValue("mariadb")
val mariadbVersion = mariadbSpec["version"].toString()
val mariadbRevision = (mariadbSpec["revision"] as Number).toInt()
val thirdpartyVersion = "$mariadbVersion-keel.$mariadbRevision"

/** 每个目标一份 thirdparty 依赖：zip 里是 include/mariadb 与 lib/<target>/libmariadb.*。 */
val thirdpartyConfigs = targets.associateWith { target ->
    val name = "keelMariadbNative${target.replaceFirstChar { it.uppercaseChar() }}"
    val configuration = configurations.create(name) {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
        description = "MariaDB Connector/C 头文件与动态库（$target）"
    }
    dependencies.add(
        configuration.name,
        "moe.lolosia.keel.thirdparty:mariadb-$target:$thirdpartyVersion@zip",
    )
    configuration
}

/**
 * 第三方产物的取用方式：
 * - 仓库内开发（存在 `../keel-thirdparty`）→ 直接读 `build/dist`，并由任务图自动触发
 *   `:stageMariadb<target>`（进而 CMake 编译 Connector/C）；可用 `-Pkeel.thirdparty.dist=<dir>` 覆盖；
 * - 单独 clone 本目录（没有兄弟目录）→ 按 Maven 坐标解析已发布的 zip。
 */
val thirdpartyProjectDir = layout.projectDirectory.dir("../keel-thirdparty").asFile
val distOverride: File? =
    (findProperty("keel.thirdparty.dist") as String?)?.let { file(it) }
        ?: File(thirdpartyProjectDir, "build/dist").takeIf { thirdpartyProjectDir.isDirectory }

val unpackTasks = targets.associateWith { target ->
    tasks.register("keelMariadbUnpack$target") {
        group = "keel"
        description = "解出 Connector/C 的 include/ 与 libmariadb 动态库（$target）"
        val output = layout.buildDirectory.dir("thirdparty/$target")
        outputs.dir(output)
        // 仓库内：把生成侧的 stage 任务接进同一张任务图
        if (thirdpartyProjectDir.isDirectory) {
            val stage = "stageMariadb${target.replaceFirstChar { it.uppercaseChar() }}"
            dependsOn(gradle.includedBuild("keel-thirdparty").task(":$stage"))
        }
        doLast {
            val out = output.get().asFile
            out.deleteRecursively()
            out.mkdirs()
            if (distOverride != null) {
                val source = File(distOverride, "mariadb/$target")
                check(source.isDirectory) {
                    "keel.thirdparty.dist 里没有 mariadb/$target：${source.absolutePath}"
                }
                copy { from(File(source, "include")); into(File(out, "include")) }
                copy { from(File(source, "lib")); into(File(out, "lib")) }
            } else {
                // 发布 zip 的布局是 lib/<target>/libmariadb.*，规整成 include/ + lib/
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
        description = "用 K/N 自带交叉 gcc 编译 MariaDB 能力包（$target）"

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
        outputs.dir(outDir)

        environment(
            "PATH",
            "${toolchain.binDir.absolutePath}${File.pathSeparator}${System.getenv("PATH")}",
        )
        doFirst {
            val include = File(thirdparty.get().asFile, "include/mariadb")
            val link = File(thirdparty.get().asFile, "lib/libmariadb.dll.a")
            check(File(include, "mysql.h").isFile) {
                "Connector/C 头文件缺失：${include.absolutePath}"
            }
            check(link.isFile) { "Connector/C 导入库缺失：${link.absolutePath}" }
            outDir.get().mkdirs()
            commandLine(
                buildList {
                    add(toolchain.gcc)
                    add("-shared")
                    addAll(listOf("-O2", "-std=c99", "-Wall", "-Wextra"))
                    add("-I${dbAbiDir.asFile.absolutePath}")
                    add("-I${keelAbiDir.asFile.absolutePath}")
                    add("-I${include.absolutePath}")
                    add("-o")
                    add(library.get().absolutePath)
                    addAll(sources.map { it.absolutePath })
                    // 能力包必须自带运行时依赖：静态并入 libgcc / winpthread，
                    // 否则 LoadLibraryExW 在插件目录 / System32 里找不到这些 DLL。
                    add("-static")
                    add(link.absolutePath)
                    add("-lws2_32")
                }
            )
        }
        doLast {
            val runtime = File(thirdparty.get().asFile, "lib/libmariadb.dll")
            check(runtime.isFile) { "运行期动态库缺失：${runtime.absolutePath}" }
            runtime.copyTo(File(outDir.get(), runtime.name), overwrite = true)
        }
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
              "dialect": "mariadb",
              "features": ["transactions", "prepared", "last_insert_id", "ping"],
              "lib": {
            $entries
              },
              "authors": ["lolosia"],
              "description": "MariaDB 数据库能力包（动态链接 Connector/C）"
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
    onlyIf { targets.isNotEmpty() }

    archiveFileName.set("keel-database-mariadb-$version.keel")
    destinationDirectory.set(outputRoot)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    from(generateManifest.map { layout.buildDirectory.file("keel/plugin.json") }) { into("") }
    // LGPL：随包携带许可文本，动态库可被用户替换
    from(layout.projectDirectory.dir("licenses")) { into("licenses") }
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
