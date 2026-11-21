import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// crypt_blowfish 的生成侧。
//
// 与 sqlite.gradle.kts 共用 build/thirdparty/{src,tree,out} 布局：下载 → 解压 →
// 用 K/N 自带 clang + sysroot 把 crypt_blowfish.c 编成 `out/<target>/libbcrypt.a`，
// 头文件汇总到 `out/include/bcrypt/`，之后 stageAll 原样接手。
//
// 只编一个源文件：`crypt_rn` / `crypt_gensalt_rn` 之外的算法与 bcrypt 专用的
// `_crypt_gensalt_blowfish_rn` 都在 crypt_blowfish.c 里，wrapper.c 只提供 crypt(3)
// 的全局状态接口，用不到。
//
// `-O2` 固定写死：bcrypt 的 cost 10 意味着 2^10 轮 Eksblowfish，debug 插件也必须
// 跑原生优化版本，否则单次登录要几十秒。

/** manifest 里 `libraries.bcrypt` 的描述。 */
data class BcryptSpec(
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
) {
    val fileName: String get() = "$name-$version.tar.gz"

    /** 上游 tar.gz 解出来的顶层目录（`crypt_blowfish-1.3`）。 */
    val treeName: String get() = "$name-$version"
}

private class BcryptTarget(
    val triple: String,
    val sysrootPrefix: String,
    /** mingw 的 sysroot 就是 msys2 目录本身；linux 在其子目录 sysroot/ 下。 */
    val sysrootInPrefix: String?,
)

private val bcryptTargets = mapOf(
    "mingwX64" to BcryptTarget("x86_64-w64-mingw32", "msys2-mingw-w64-x86_64-", null),
    "linuxArm64" to BcryptTarget("aarch64-linux-gnu", "aarch64-unknown-linux-gnu-gcc-", "aarch64-unknown-linux-gnu/sysroot"),
    "linuxX64" to BcryptTarget("x86_64-linux-gnu", "x86_64-unknown-linux-gnu-gcc-", "x86_64-unknown-linux-gnu/sysroot"),
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

private class BcryptToolchain(val clang: File, val llvmAr: File, private val sysroots: Map<String, File>) {

    fun sysroot(target: String): File = sysroots[target]
        ?: throw GradleException(
            "缺少 $target 的 sysroot；请先执行一次 Kotlin/Native 构建以下载工具链，" +
                "或换一台支持该目标的机器"
        )
}

private fun detectBcryptToolchain(): BcryptToolchain {
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
    bcryptTargets.forEach { (name, target) ->
        val prefix = newest(dirs.filter { it.name.startsWith(target.sysrootPrefix) })
            ?: return@forEach
        val sysroot = target.sysrootInPrefix?.let { File(prefix, it) } ?: prefix
        if (sysroot.isDirectory) sysroots[name] = sysroot
    }
    return BcryptToolchain(clang, llvmAr, sysroots)
}

private class BcryptBuild(private val project: Project) {

    fun register(manifestFile: File) {
        val spec = readSpec(manifestFile)
        val buildRoot = project.layout.buildDirectory.dir("thirdparty").get().asFile
        val sourcesDir = File(buildRoot, "src")
        val treeDir = File(buildRoot, "tree")
        val outDir = File(buildRoot, "out")
        // 不能叫 libbcrypt.a：Windows 的 CNG 导入库同名（BCryptGenRandom），
        // -L 顺序会让链接器优先命中我们这份。
        val archiveName = "libcrypt_blowfish.a"

        val download = project.tasks.register("downloadBcryptSource") {
            group = "thirdparty"
            description = "下载 crypt_blowfish 源码包（校验 sha256）"
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

        val extract = project.tasks.register("extractBcryptSource") {
            group = "thirdparty"
            description = "把 crypt_blowfish 源码包解到 build/thirdparty/tree"
            dependsOn(download)
            doLast {
                val tree = File(treeDir, spec.treeName)
                if (File(tree, "crypt_blowfish.c").isFile) return@doLast
                tree.mkdirs()
                logger.lifecycle("==> 解压 ${spec.fileName}")
                val process = ProcessBuilder(
                    "tar", "-xzf", File(sourcesDir, spec.fileName).path, "-C", treeDir.path
                ).redirectErrorStream(true).start()
                process.inputStream.use { it.copyTo(System.out) }
                val exit = process.waitFor()
                if (exit != 0) throw GradleException("解压失败（exit $exit）：${spec.fileName}")
            }
        }

        // 仓库自带的适配层（不 vendor 上游源码，只有这两个文件是我们自己的）
        val shimDir = project.file("bcrypt")

        val compiled = bcryptTargets.keys.map { target ->
            project.tasks.register("compileBcrypt${target.replaceFirstChar { it.uppercaseChar() }}") {
                group = "thirdparty"
                description = "交叉编译 crypt_blowfish 静态库（$target）"
                dependsOn(extract)
                inputs.files(File(shimDir, "keel_bcrypt.c"), File(shimDir, "keel_bcrypt.h"))
                outputs.file(File(outDir, "$target/$archiveName"))
                doLast {
                    val toolchain = detectBcryptToolchain()
                    val tree = File(treeDir, spec.treeName)
                    val targetOut = File(outDir, target)
                    val objectDir = File(buildRoot, "cmake/$target/bcrypt").also { it.mkdirs() }
                    targetOut.mkdirs()

                    val sources = listOf(
                        File(tree, "crypt_blowfish.c"),
                        File(shimDir, "keel_bcrypt.c"),
                    )
                    val objects = sources.map { source ->
                        val obj = File(objectDir, source.name.removeSuffix(".c") + ".o")
                        run(
                            listOf(
                                toolchain.clang.path,
                                "--target=${bcryptTargets.getValue(target).triple}",
                                "--sysroot=${toolchain.sysroot(target).path}",
                                "-O2", "-fPIC", "-std=gnu99", "-Wall",
                                "-I${tree.path}",
                                "-I${shimDir.path}",
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

        val headers = project.tasks.register("bcryptHeaders") {
            group = "thirdparty"
            description = "把 crypt_blowfish.h 与 keel_bcrypt.h 汇总到 out/include/bcrypt"
            dependsOn(extract)
            inputs.files(File(shimDir, "keel_bcrypt.h"))
            outputs.dir(File(outDir, "include/bcrypt"))
            doLast {
                val tree = File(treeDir, spec.treeName)
                val dest = File(outDir, "include/bcrypt").also { it.mkdirs() }
                listOf(File(tree, "crypt_blowfish.h"), File(shimDir, "keel_bcrypt.h")).forEach { from ->
                    if (!from.isFile) throw GradleException("缺少 ${from.absolutePath}")
                    Files.copy(from.toPath(), File(dest, from.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }

        project.tasks.register("compileBcryptAll") {
            group = "thirdparty"
            description = "编译 crypt_blowfish 静态库并汇总头文件（三个目标）"
            dependsOn(compiled, headers)
        }
    }

    private fun readSpec(manifestFile: File): BcryptSpec {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(manifestFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val libraries = root["libraries"] as? Map<String, Map<String, Any?>>
            ?: throw GradleException("${manifestFile.name} 缺少 libraries")
        val bcrypt = libraries["bcrypt"]
            ?: throw GradleException("${manifestFile.name}：libraries.bcrypt 不存在")
        @Suppress("UNCHECKED_CAST")
        val source = bcrypt["source"] as? Map<String, Any?>
            ?: throw GradleException("${manifestFile.name}：libraries.bcrypt.source 不能为空")
        return BcryptSpec(
            name = source["name"]?.toString().orEmpty(),
            version = bcrypt["version"]?.toString().orEmpty(),
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

BcryptBuild(project).register(file("manifest.json"))
