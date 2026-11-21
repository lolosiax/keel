import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

// SQLite amalgamation 的生成侧。
//
// 与 codecs.gradle.kts 共用目录布局（build/thirdparty/{src,tree,out}），但源码是 zip、
// 上游也不需要 CMake：下载 → 解压 → 用 Kotlin/Native 自带的 clang 把 sqlite3.c 编成
// `out/<target>/libsqlite3.a`，头文件汇总到 `out/include/sqlite/`，之后 stageAll 原样接手。
//
// 只做静态库：`keel-database-sqlite` 把 sqlite3.a 静态并进能力包，部署时只有一个 .dll/.so。

/** manifest 里 `libraries.sqlite` 的描述。 */
data class SqliteSpec(
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
) {
    val fileName: String get() = url.substringAfterLast('/')

    /** 上游解压出来的顶层目录就是去掉 `.zip` 的文件名（`sqlite-amalgamation-3460100`）。 */
    val treeName: String get() = fileName.removeSuffix(".zip")
}

private class SqliteTarget(
    val triple: String,
    val sysrootPrefix: String,
    /** mingw 的 sysroot 就是 msys2 目录本身；linux 在其子目录 sysroot/ 下。 */
    val sysrootInPrefix: String?,
)

private val sqliteTargets = mapOf(
    "mingwX64" to SqliteTarget("x86_64-w64-mingw32", "msys2-mingw-w64-x86_64-", null),
    "linuxArm64" to SqliteTarget("aarch64-linux-gnu", "aarch64-unknown-linux-gnu-gcc-", "aarch64-unknown-linux-gnu/sysroot"),
    "linuxX64" to SqliteTarget("x86_64-linux-gnu", "x86_64-unknown-linux-gnu-gcc-", "x86_64-unknown-linux-gnu/sysroot"),
)

private fun konanDependencies(): File {
    val root = System.getenv("KONAN_DATA_DIR")?.let(::File)
        ?: File(System.getProperty("user.home"), ".konan")
    return File(root, "dependencies")
}

private fun newest(candidates: List<File>): File? =
    candidates.maxWithOrNull { left, right -> compareVersions(versionOf(left.name), versionOf(right.name)) }

private fun compareVersions(left: List<Int>, right: List<Int>): Int {
    for (index in 0 until maxOf(left.size, right.size)) {
        val l = left.getOrElse(index) { 0 }
        val r = right.getOrElse(index) { 0 }
        if (l != r) return l.compareTo(r)
    }
    return 0
}

private fun versionOf(name: String): List<Int> =
    Regex("""\d+(?:\.\d+)*""").find(name)?.value
        ?.split('.')
        ?.mapNotNull { it.toIntOrNull() }
        .orEmpty()

private fun File.executable(name: String): File? =
    listOf(File(this, "bin/$name.exe"), File(this, "bin/$name")).firstOrNull { it.isFile }

private class SqliteToolchain(val clang: File, val llvmAr: File, private val sysroots: Map<String, File>) {

    fun sysroot(target: String): File = sysroots[target]
        ?: throw GradleException(
            "缺少 $target 的 sysroot；请先执行一次 Kotlin/Native 构建以下载工具链，" +
                "或换一台支持该目标的机器"
        )
}

private fun detectSqliteToolchain(): SqliteToolchain {
    val dependencies = konanDependencies()
    if (!dependencies.isDirectory) {
        throw GradleException("找不到 Kotlin/Native 依赖目录：${dependencies.absolutePath}")
    }
    val dirs = dependencies.listFiles().orEmpty().filter { it.isDirectory }
    val llvm = newest(
        dirs.filter { it.name.startsWith("llvm-") && (it.name.contains("windows") || it.name.contains("linux")) }
    ) ?: throw GradleException("${dependencies.absolutePath} 里没有 llvm")
    val clang = llvm.executable("clang")
        ?: throw GradleException("${llvm.absolutePath}/bin 里找不到 clang")
    val llvmAr = llvm.executable("llvm-ar")
        ?: throw GradleException("${llvm.absolutePath}/bin 里找不到 llvm-ar")

    val sysroots = mutableMapOf<String, File>()
    sqliteTargets.forEach { (name, target) ->
        val prefix = newest(dirs.filter { it.name.startsWith(target.sysrootPrefix) })
            ?: return@forEach
        val sysroot = target.sysrootInPrefix?.let { File(prefix, it) } ?: prefix
        if (sysroot.isDirectory) sysroots[name] = sysroot
    }
    return SqliteToolchain(clang, llvmAr, sysroots)
}

private class SqliteBuild(private val project: org.gradle.api.Project) {

    fun register(manifestFile: File) {
        val spec = readSpec(manifestFile)
        val buildRoot = project.layout.buildDirectory.dir("thirdparty").get().asFile
        val sourcesDir = File(buildRoot, "src")
        val treeDir = File(buildRoot, "tree")
        val outDir = File(buildRoot, "out")
        val archiveName = "libsqlite3.a"

        val download = project.tasks.register("downloadSqliteSource") {
            group = "thirdparty"
            description = "下载 SQLite amalgamation 源码包（校验 sha256）"
            doLast {
                sourcesDir.mkdirs()
                val tarball = File(sourcesDir, spec.fileName)
                if (tarball.isFile && tarball.sha256() == spec.sha256.lowercase()) {
                    logger.info("已存在 ${spec.fileName}")
                    return@doLast
                }
                tarball.delete()
                logger.lifecycle("==> 下载 ${spec.fileName} <- ${spec.url}")
                fetch(spec.url, tarball)
                val actual = tarball.sha256()
                if (actual != spec.sha256.lowercase()) {
                    tarball.delete()
                    throw GradleException("sha256 不匹配（期望 ${spec.sha256}，实际 $actual）")
                }
            }
        }

        val extract = project.tasks.register("extractSqliteSource") {
            group = "thirdparty"
            description = "把 SQLite 源码包解到 build/thirdparty/tree"
            dependsOn(download)
            doLast {
                val tree = File(treeDir, spec.treeName)
                if (tree.isDirectory && File(tree, "sqlite3.c").isFile) return@doLast
                tree.mkdirs()
                logger.lifecycle("==> 解压 ${spec.fileName}")
                unzip(File(sourcesDir, spec.fileName), treeDir)
            }
        }

        val compiled = sqliteTargets.keys.map { target ->
            project.tasks.register("compileSqlite${target.replaceFirstChar { it.uppercaseChar() }}") {
                group = "thirdparty"
                description = "交叉编译 SQLite 静态库（$target）"
                dependsOn(extract)
                outputs.file(File(outDir, "$target/$archiveName"))
                doLast {
                    val toolchain = detectSqliteToolchain()
                    val tree = File(treeDir, spec.treeName)
                    val targetOut = File(outDir, target)
                    val objectDir = File(buildRoot, "cmake/$target/sqlite").also { it.mkdirs() }
                    targetOut.mkdirs()

                    val obj = File(objectDir, "sqlite3.o")
                    run(
                        listOf(
                            toolchain.clang.path,
                            "--target=${sqliteTargets.getValue(target).triple}",
                            "--sysroot=${toolchain.sysroot(target).path}",
                            "-O2", "-fPIC", "-std=gnu99", "-Wall",
                            "-DSQLITE_THREADSAFE=1",
                            "-DSQLITE_OMIT_LOAD_EXTENSION=1",
                            "-DSQLITE_DQS=0",
                            "-c", File(tree, "sqlite3.c").path,
                            "-o", obj.path,
                        )
                    )
                    val lib = File(targetOut, archiveName)
                    lib.delete()
                    run(listOf(toolchain.llvmAr.path, "rcs", lib.path, obj.path))
                    logger.lifecycle("    -> $target/$archiveName (${lib.length() / 1024} KB)")
                }
            }
        }

        val headers = project.tasks.register("sqliteHeaders") {
            group = "thirdparty"
            description = "把 sqlite3.h / sqlite3ext.h 汇总到 out/include/sqlite"
            dependsOn(extract)
            outputs.dir(File(outDir, "include/sqlite"))
            doLast {
                val tree = File(treeDir, spec.treeName)
                val dest = File(outDir, "include/sqlite").also { it.mkdirs() }
                listOf("sqlite3.h", "sqlite3ext.h").forEach { name ->
                    val from = File(tree, name)
                    if (!from.isFile) throw GradleException("缺少 ${from.absolutePath}")
                    Files.copy(from.toPath(), File(dest, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        project.tasks.register("compileSqliteAll") {
            group = "thirdparty"
            description = "编译 SQLite 静态库并汇总头文件（三个目标）"
            dependsOn(compiled, headers)
        }
    }

    private fun readSpec(manifestFile: File): SqliteSpec {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(manifestFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val libraries = root["libraries"] as? Map<String, Map<String, Any?>>
            ?: throw GradleException("${manifestFile.name} 缺少 libraries")
        val sqlite = libraries["sqlite"]
            ?: throw GradleException("${manifestFile.name}：libraries.sqlite 不存在")
        @Suppress("UNCHECKED_CAST")
        val source = sqlite["source"] as? Map<String, Any?>
            ?: throw GradleException("${manifestFile.name}：libraries.sqlite.source 不能为空")
        return SqliteSpec(
            name = source["name"]?.toString().orEmpty(),
            version = sqlite["version"]?.toString().orEmpty(),
            url = source["url"]?.toString().orEmpty(),
            sha256 = source["sha256"]?.toString().orEmpty(),
        )
    }

    private fun fetch(url: String, target: File) {
        target.parentFile.mkdirs()
        if (url.startsWith("file:")) {
            Files.copy(File(URI(url)).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        }
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000
        connection.instanceFollowRedirects = true
        try {
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unzip(zip: File, destination: File) {
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val target = File(destination, entry.name)
                if (!target.canonicalPath.startsWith(destination.canonicalPath)) {
                    throw GradleException("非法压缩条目：${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                input.closeEntry()
            }
        }
    }

    private fun run(command: List<String>) {
        project.logger.info("执行：${command.joinToString(" ")}")
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            throw GradleException("无法执行 ${command.first()}：${e.message}", e)
        }
        process.inputStream.use { it.copyTo(System.out) }
        val exitCode = process.waitFor()
        if (exitCode != 0) throw GradleException("命令失败（exit $exitCode）：${command.joinToString(" ")}")
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

SqliteBuild(project).register(file("manifest.json"))
