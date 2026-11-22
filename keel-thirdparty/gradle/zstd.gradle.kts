import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// zstd 的生成侧（仅解压器）。
//
// 与 sqlite / bcrypt 共用 build/thirdparty/{src,tree,out} 布局：下载上游源码包 →
// 只解出 lib/ 与 LICENSE（上游 tar 里的 tests/ 含符号链接，Windows tar 解不动）→
// 用 K/N 自带 clang + sysroot 把 common/ 与 decompress/ 编成 `out/<target>/libzstd.a`，
// 头文件汇总到 `out/include/zstd/`，之后 stageAll 原样接手。
//
// Host 的 KELB bundle 只需要解压：不编 compress/、dictBuilder/、legacy/、deprecated/，
// 也不定义 ZSTD_MULTITHREAD（pool.c / threading.c 因此不参与）。
// 结果由 keel-abi 静态并入 libkeel_abi.a，运行期不再单独带动态库。

/** manifest 里 `libraries.zstd` 的描述。 */
data class ZstdSpec(
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
) {
    val fileName: String get() = "$name-$version.tar.gz"

    /** 上游 tar.gz 解出来的顶层目录（`zstd-1.5.6`）。 */
    val treeName: String get() = "$name-$version"
}

private class ZstdTarget(
    val triple: String,
    val sysrootPrefix: String,
    /** mingw 的 sysroot 就是 msys2 目录本身；linux 在其子目录 sysroot/ 下。 */
    val sysrootInPrefix: String?,
)

private val zstdTargets = mapOf(
    "mingwX64" to ZstdTarget("x86_64-w64-mingw32", "msys2-mingw-w64-x86_64-", null),
    "linuxArm64" to ZstdTarget("aarch64-linux-gnu", "aarch64-unknown-linux-gnu-gcc-", "aarch64-unknown-linux-gnu/sysroot"),
    "linuxX64" to ZstdTarget("x86_64-linux-gnu", "x86_64-unknown-linux-gnu-gcc-", "x86_64-unknown-linux-gnu/sysroot"),
)

/** 解压器的最小源集；路径相对上游 `lib/`。 */
private val zstdSources = listOf(
    "common/debug.c",
    "common/entropy_common.c",
    "common/error_private.c",
    "common/fse_decompress.c",
    "common/xxhash.c",
    "common/zstd_common.c",
    "decompress/huf_decompress.c",
    "decompress/zstd_ddict.c",
    "decompress/zstd_decompress.c",
    "decompress/zstd_decompress_block.c",
)

/** `zstd.h` / `zstd_errors.h` 对外保留，方便以后有消费方需要 cinterop。 */
private val zstdHeaders = listOf("zstd.h", "zstd_errors.h")

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

private class ZstdToolchain(val clang: File, val llvmAr: File, private val sysroots: Map<String, File>) {

    fun sysroot(target: String): File = sysroots[target]
        ?: throw GradleException(
            "缺少 $target 的 sysroot；请先执行一次 Kotlin/Native 构建以下载工具链，" +
                "或换一台支持该目标的机器"
        )
}

private fun detectZstdToolchain(): ZstdToolchain {
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
    zstdTargets.forEach { (name, target) ->
        val prefix = newest(dirs.filter { it.name.startsWith(target.sysrootPrefix) })
            ?: return@forEach
        val sysroot = target.sysrootInPrefix?.let { File(prefix, it) } ?: prefix
        if (sysroot.isDirectory) sysroots[name] = sysroot
    }
    return ZstdToolchain(clang, llvmAr, sysroots)
}

private class ZstdBuild(private val project: Project) {

    fun register(manifestFile: File) {
        val spec = readSpec(manifestFile)
        val buildRoot = project.layout.buildDirectory.dir("thirdparty").get().asFile
        val sourcesDir = File(buildRoot, "src")
        val treeDir = File(buildRoot, "tree")
        val outDir = File(buildRoot, "out")
        val archiveName = "libzstd.a"

        val download = project.tasks.register("downloadZstdSource") {
            group = "thirdparty"
            description = "下载 zstd 源码包（校验 sha256）"
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

        val extract = project.tasks.register("extractZstdSource") {
            group = "thirdparty"
            description = "把 zstd 源码包的 lib/ 与 LICENSE 解到 build/thirdparty/tree"
            dependsOn(download)
            doLast {
                val tree = File(treeDir, spec.treeName)
                if (File(tree, "lib/decompress/zstd_decompress.c").isFile) return@doLast
                tree.mkdirs()
                logger.lifecycle("==> 解压 ${spec.fileName}（只取 lib/ 与 LICENSE）")
                // 上游 tests/ 里有符号链接，Windows tar 解整个包会失败；只解需要的成员
                val process = ProcessBuilder(
                    "tar", "-xzf", File(sourcesDir, spec.fileName).path, "-C", treeDir.path,
                    "${spec.treeName}/lib", "${spec.treeName}/LICENSE",
                ).redirectErrorStream(true).start()
                process.inputStream.use { it.copyTo(System.out) }
                val exit = process.waitFor()
                if (exit != 0) throw GradleException("解压失败（exit $exit）：${spec.fileName}")
            }
        }

        val compiled = zstdTargets.keys.map { target ->
            project.tasks.register("compileZstd${target.replaceFirstChar { it.uppercaseChar() }}") {
                group = "thirdparty"
                description = "交叉编译 zstd 解压静态库（$target）"
                dependsOn(extract)
                outputs.file(File(outDir, "$target/$archiveName"))
                doLast {
                    val toolchain = detectZstdToolchain()
                    val libDir = File(File(treeDir, spec.treeName), "lib")
                    val targetOut = File(outDir, target)
                    val objectDir = File(buildRoot, "cmake/$target/zstd").also { it.mkdirs() }
                    targetOut.mkdirs()

                    val objects = zstdSources.map { relative ->
                        val source = File(libDir, relative)
                        if (!source.isFile) throw GradleException("缺少 ${source.absolutePath}")
                        val obj = File(objectDir, relative.removeSuffix(".c") + ".o").also { it.parentFile.mkdirs() }
                        run(
                            listOf(
                                toolchain.clang.path,
                                "--target=${zstdTargets.getValue(target).triple}",
                                "--sysroot=${toolchain.sysroot(target).path}",
                                "-O2", "-fPIC", "-std=gnu99", "-Wall",
                                // 与 keel-abi 其余目标文件一致：符号不导出，避免多个二进制互相抢占
                                "-fvisibility=hidden",
                                // 只留当前版本格式；legacy 解码需要 lib/legacy 全套源码
                                "-DZSTD_LEGACY_SUPPORT=0",
                                // 解压热路径的 x86-64 BMI2 手写汇编在 huf_decompress_amd64.S 里，
                                // 本脚本只编 .c；关掉它以免 linuxX64 链接时缺符号
                                "-DZSTD_DISABLE_ASM",
                                "-I${libDir.path}",
                                "-c", source.path,
                                "-o", obj.path,
                            )
                        )
                        obj
                    }
                    val lib = File(targetOut, archiveName)
                    lib.delete()
                    run(listOf(toolchain.llvmAr.path, "rcs", lib.path) + objects.map { it.path })
                    logger.lifecycle("    -> $target/$archiveName (${lib.length() / 1024} KB)")
                }
            }
        }

        val headers = project.tasks.register("zstdHeaders") {
            group = "thirdparty"
            description = "把 zstd.h / zstd_errors.h 汇总到 out/include/zstd"
            dependsOn(extract)
            outputs.dir(File(outDir, "include/zstd"))
            doLast {
                val libDir = File(File(treeDir, spec.treeName), "lib")
                val dest = File(outDir, "include/zstd").also { it.mkdirs() }
                zstdHeaders.forEach { name ->
                    val from = File(libDir, name)
                    if (!from.isFile) throw GradleException("缺少 ${from.absolutePath}")
                    Files.copy(from.toPath(), File(dest, name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        project.tasks.register("compileZstdAll") {
            group = "thirdparty"
            description = "编译 zstd 解压静态库并汇总头文件（三个目标）"
            dependsOn(compiled, headers)
        }
    }

    private fun readSpec(manifestFile: File): ZstdSpec {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(manifestFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val libraries = root["libraries"] as? Map<String, Map<String, Any?>>
            ?: throw GradleException("${manifestFile.name} 缺少 libraries")
        val zstd = libraries["zstd"]
            ?: throw GradleException("${manifestFile.name}：libraries.zstd 不存在")
        @Suppress("UNCHECKED_CAST")
        val source = zstd["source"] as? Map<String, Any?>
            ?: throw GradleException("${manifestFile.name}：libraries.zstd.source 不能为空")
        return ZstdSpec(
            name = source["name"]?.toString().orEmpty(),
            version = zstd["version"]?.toString().orEmpty(),
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

    private fun run(command: List<String>) {
        project.logger.lifecycle("==> ${command.joinToString(" ")}")
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            throw GradleException("无法执行 ${command.first()}：${e.message}", e)
        }
        process.inputStream.use { it.copyTo(System.out) }
        val exit = process.waitFor()
        if (exit != 0) throw GradleException("编译失败（exit $exit）：${command.joinToString(" ")}")
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

ZstdBuild(project).register(file("manifest.json"))
