import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// MariaDB Connector/C 的生成侧。
//
// 与 sqlite.gradle.kts 共用 build/thirdparty/{src,tree,out} 布局，但 Connector/C 是
// CMake 工程：下载源码 → 用 K/N 自带 clang / sysroot 交叉配置 → 构建 libmariadb 共享库。
//
// 产物布局（交给 stageAll 原样拆分）：
//   out/<target>/libmariadb.dll + libmariadb.dll.a
//   out/include/mariadb/**（源码头 + CMake 生成的 ma_config.h / mariadb_version.h）
//
// 只做 mingwX64：Linux 的 Connector/C 需要 OpenSSL/GnuTLS 与 $ORIGIN rpath，
// 留到 Linux 构建环境再接。

/** manifest 里 `libraries.mariadb` 的描述。 */
data class MariadbSpec(
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
) {
    val fileName: String get() = "$name-$version.tar.gz"
    val treeName: String get() = "$name-$version"
}

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

/** K/N 自带的 mingw 工具链；产物与 Host / 插件的 glibc / ABI 保持一致。 */
private class MariadbToolchain(
    val clang: File,
    val archiver: File,
    val ranlib: File,
    val sysroot: File,
    val windres: File,
)

private fun detectMariadbToolchain(): MariadbToolchain {
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

    val msys2 = newest(dirs.filter { it.name.startsWith("msys2-mingw-w64-x86_64-") })
        ?: throw GradleException("缺少 msys2-mingw-w64-x86_64 工具链；请先执行一次 Kotlin/Native 构建")
    val windres = msys2.executable("windres")
        ?: throw GradleException("${msys2.absolutePath}/bin 里找不到 windres")
    val archiver = msys2.executable("ar")
        ?: throw GradleException("${msys2.absolutePath}/bin 里找不到 ar")
    val ranlib = msys2.executable("ranlib")
        ?: throw GradleException("${msys2.absolutePath}/bin 里找不到 ranlib")

    return MariadbToolchain(clang, archiver, ranlib, msys2, windres)
}

/** 从属性 / 环境变量 / PATH / 常见安装位置找工具；找不到返回 null。 */
private fun findTool(
    project: Project,
    property: String,
    environment: String,
    name: String,
): File? {
    (project.findProperty(property) as String?)?.takeIf { it.isNotBlank() }?.let { return File(it) }
    System.getenv(environment)?.takeIf { it.isNotBlank() }?.let { return File(it) }

    val extra = mutableListOf<File>()
    System.getenv("APPDATA")?.let { appData ->
        File(appData, "Python").listFiles().orEmpty()
            .filter { it.isDirectory }
            .forEach { extra += File(it, "Scripts") }
    }
    extra += File("C:/Program Files/CMake/bin")

    val pathDirs = System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map(::File)
    (pathDirs + extra).forEach { dir ->
        listOf(File(dir, "$name.exe"), File(dir, name)).firstOrNull { it.isFile }?.let { return it }
    }
    return null
}

private class MariadbBuild(private val project: Project) {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    fun register(manifestFile: File) {
        val spec = readSpec(manifestFile)
        val buildRoot = project.layout.buildDirectory.dir("thirdparty").get().asFile
        val sourcesDir = File(buildRoot, "src")
        val treeDir = File(buildRoot, "tree")
        val outDir = File(buildRoot, "out")
        val cmakeBuildDir = File(buildRoot, "cmake/mingwX64/mariadb")
        val targetOut = File(outDir, "mingwX64")
        val includeOut = File(outDir, "include/mariadb")

        val download = project.tasks.register("downloadMariadbSource") {
            group = "thirdparty"
            description = "下载 MariaDB Connector/C 源码包（校验 sha256）"
            outputs.file(File(sourcesDir, spec.fileName))
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

        val extract = project.tasks.register("extractMariadbSource") {
            group = "thirdparty"
            description = "把 Connector/C 源码包解到 build/thirdparty/tree"
            dependsOn(download)
            outputs.dir(File(treeDir, spec.treeName))
            doLast {
                val tree = File(treeDir, spec.treeName)
                if (File(tree, "CMakeLists.txt").isFile) return@doLast
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

        val compile = project.tasks.register("compileMariadbMingwX64") {
            group = "thirdparty"
            description = "用 K/N clang + CMake 交叉编译 MariaDB Connector/C（mingwX64）"
            dependsOn(extract)
            // 交叉编译依赖 K/N 的 msys2 工具链；Linux 构建机先跳过（Linux 目标另接 OpenSSL）
            onlyIf { isWindows }
            outputs.dir(targetOut)
            outputs.dir(includeOut)

            doLast {
                val cmake = findTool(project, "keel.cmake", "CMAKE_COMMAND", "cmake")
                    ?: throw GradleException(
                        "找不到 cmake。请安装 CMake，或用 -Pkeel.cmake=<path> 指定；" +
                            "Python 用户可 python -m pip install cmake ninja"
                    )
                val ninja = findTool(project, "keel.ninja", "NINJA", "ninja")
                    ?: throw GradleException(
                        "找不到 ninja。请安装 Ninja，或用 -Pkeel.ninja=<path> 指定；" +
                            "Python 用户可 python -m pip install ninja"
                    )

                val toolchain = detectMariadbToolchain()
                val tree = File(treeDir, spec.treeName)
                cmakeBuildDir.deleteRecursively()
                cmakeBuildDir.mkdirs()
                targetOut.mkdirs()
                includeOut.mkdirs()

                val path = buildList {
                    add(toolchain.sysroot.resolve("bin"))
                    add(toolchain.clang.parentFile)
                    add(cmake.parentFile)
                    add(ninja.parentFile)
                    addAll(System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map(::File))
                }.joinToString(File.pathSeparator) { it.absolutePath }

                run(
                    listOf(
                        cmake.absolutePath,
                        "-S", tree.cmakePath(),
                        "-B", cmakeBuildDir.cmakePath(),
                        "-G", "Ninja",
                        "-DCMAKE_SYSTEM_NAME=Windows",
                        "-DCMAKE_SYSTEM_PROCESSOR=x86_64",
                        "-DCMAKE_C_COMPILER=${toolchain.clang.cmakePath()}",
                        "-DCMAKE_C_COMPILER_TARGET=x86_64-w64-mingw32",
                        "-DCMAKE_SYSROOT=${toolchain.sysroot.cmakePath()}",
                        "-DCMAKE_RC_COMPILER=${toolchain.windres.cmakePath()}",
                        "-DCMAKE_AR=${toolchain.archiver.cmakePath()}",
                        "-DCMAKE_RANLIB=${toolchain.ranlib.cmakePath()}",
                        "-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DCMAKE_COMPILE_WARNING_AS_ERROR=OFF",
                        "-DBUILD_SHARED_LIBS=ON",
                        "-DWITH_UNIT_TESTS=OFF",
                        "-DWITH_CURL=OFF",
                        "-DWITH_SSL=SCHANNEL",
                        "-DWITH_EXTERNAL_ZLIB=OFF",
                        "-DWITH_DYNCOL=ON",
                        // MinGW 有 nl_langinfo 符号但没有 langinfo.h；上游 WindowsCache 用空值，
                        // 这里显式定义成空，跳过 CHECK_FUNCTION_EXISTS 的误判。
                        "-DHAVE_NL_LANGINFO=",
                        // K/N 的 mingw sysroot 头文件较旧；补齐 Schannel 常量与 TLS 1.3 结构。
                        "-DCMAKE_C_FLAGS=-include" +
                            project.file("mariadb/keel_schannel_compat.h").cmakePath(),
                    ),
                    path,
                    "cmake configure",
                )

                run(
                    listOf(cmake.absolutePath, "--build", cmakeBuildDir.cmakePath(), "--target", "libmariadb", "--parallel"),
                    path,
                    "cmake build",
                )

                val dll = findNamed(cmakeBuildDir, "libmariadb.dll")
                    ?: throw GradleException("libmariadb.dll 未生成 [mingwX64]")
                // CMake 的 MinGW 导入库带 `lib` 前缀叠成 liblibmariadb.dll.a。
                val importLib = findNamed(cmakeBuildDir, "liblibmariadb.dll.a")
                    ?: findNamed(cmakeBuildDir, "libmariadb.dll.a")
                    ?: throw GradleException("libmariadb.dll.a 未生成 [mingwX64]")
                copy(dll, File(targetOut, "libmariadb.dll"))
                copy(importLib, File(targetOut, "libmariadb.dll.a"))

                // 源码头 + CMake 生成头；都按 mariadb/ 前缀暴露，驱动侧 -I 指向该目录。
                copyTree(File(tree, "include"), includeOut)
                listOf("ma_config.h", "mariadb_version.h", "config.h").forEach { name ->
                    val generated = File(cmakeBuildDir, "include/$name")
                    if (generated.isFile) copy(generated, File(includeOut, name))
                }
                logger.lifecycle("    -> mingwX64/libmariadb.dll + libmariadb.dll.a, include/mariadb")
            }
        }

        project.tasks.register("compileMariadbAll") {
            group = "thirdparty"
            description = "编译 MariaDB Connector/C（mingwX64）"
            dependsOn(compile)
            onlyIf {
                if (isWindows) true else {
                    project.logger.warn("MariaDB Connector/C 目前只在 Windows 构建机编译，跳过")
                    false
                }
            }
        }
    }

    private fun readSpec(manifestFile: File): MariadbSpec {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parse(manifestFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val libraries = root["libraries"] as? Map<String, Map<String, Any?>>
            ?: throw GradleException("${manifestFile.name} 缺少 libraries")
        val mariadb = libraries["mariadb"]
            ?: throw GradleException("${manifestFile.name}：libraries.mariadb 不存在")
        @Suppress("UNCHECKED_CAST")
        val source = mariadb["source"] as? Map<String, Any?>
            ?: throw GradleException("${manifestFile.name}：libraries.mariadb.source 不能为空")
        return MariadbSpec(
            name = source["name"]?.toString().orEmpty(),
            version = mariadb["version"]?.toString().orEmpty(),
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

    private fun copyTree(from: File, to: File) {
        if (!from.isDirectory) throw GradleException("缺少头文件目录：${from.absolutePath}")
        from.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = from.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            copy(file, File(to, relative))
        }
    }

    private fun copy(from: File, to: File) {
        if (!from.isFile) throw GradleException("缺少文件：${from.absolutePath}")
        to.parentFile?.mkdirs()
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun findNamed(root: File, name: String): File? =
        root.walkTopDown().firstOrNull { it.isFile && it.name == name }

    private fun run(command: List<String>, path: String, label: String) {
        project.logger.lifecycle("==> $label: ${command.joinToString(" ")}")
        val process = try {
            ProcessBuilder(command)
                .apply { environment()["PATH"] = path }
                .redirectErrorStream(true)
                .start()
        } catch (e: java.io.IOException) {
            throw GradleException("无法执行 ${command.first()}：${e.message}", e)
        }
        process.inputStream.use { it.copyTo(System.out) }
        val exit = process.waitFor()
        if (exit != 0) throw GradleException("$label 失败（exit $exit）")
    }

    /** CMake 写入 .cmake 文件时不会转义反斜杠；所有 -D 路径统一用正斜杠。 */
    private fun File.cmakePath(): String = absolutePath.replace('\\', '/')

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

MariadbBuild(project).register(file("manifest.json"))
