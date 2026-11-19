import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.logging.Logger
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

// 第三方原生库的生成侧：下载/解压上游源码 → 用 Kotlin/Native 自带的 clang + sysroot 交叉编译。
//
// 由 build.gradle.kts 的 `apply(from = "gradle/codecs.gradle.kts")` 引入；Windows / Linux 共用这一份，
// OS 差异只在 CodecToolchain 的工具链定位与 CMake 生成器选择上。
// 迁移自原来的 build-codecs.ps1 / toolchain.ps1，产物布局不变：
//   build/thirdparty/out/<target>/*.a  +  build/thirdparty/out/include/
// 因此 stageAll / publishAll 不需要任何改动。

/** 交叉编译目标，名字与 Kotlin/Native 目标名一致。 */
enum class CodecTarget(
    val systemName: String,
    val processor: String,
    val triple: String,
) {
    mingwX64("Windows", "x86_64", "x86_64-w64-mingw32"),
    linuxArm64("Linux", "aarch64", "aarch64-linux-gnu"),
    linuxX64("Linux", "x86_64", "x86_64-linux-gnu"),
}

/** manifest 里一个库的上游源码包；[name] 是上游包名（png → libpng 这类差异靠它）。 */
data class CodecSource(
    val library: String,
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
) {
    val fileName: String get() = "$name-$version.tar.gz"
    val treeName: String get() = "$name-$version"
}

/**
 * Kotlin/Native 自带的交叉工具链：clang、llvm-ar 与各目标的 sysroot。
 *
 * Gradle 构建 Kotlin/Native 时已经把 `llvm-*`、`msys2-mingw-w64-*`、
 * `aarch64/x86_64-unknown-linux-gnu-gcc-*` 下载到 `~/.konan/dependencies`，
 * 直接复用可以保证这里产出的静态库与链接期的 ABI / glibc 版本完全一致。
 */
class CodecToolchain(
    val clang: File,
    val llvmAr: File,
    private val sysroots: Map<CodecTarget, File>,
) {

    fun sysroot(target: CodecTarget): File = sysroots[target]
        ?: throw GradleException(
            "缺少 ${target.name} 的 sysroot（${target.triple}）；" +
                "请先执行一次 Kotlin/Native 构建以下载工具链，或换一台支持该目标的机器"
        )

    companion object {

        /** 构建机是否是 Windows；只影响 CMake 生成器（MinGW Makefiles）。 */
        val isWindowsHost: Boolean = System.getProperty("os.name").lowercase().contains("win")

        fun detect(dependencies: File = File(System.getProperty("user.home"), ".konan/dependencies")): CodecToolchain {
            if (!dependencies.isDirectory) {
                throw GradleException(
                    "找不到 Kotlin/Native 依赖目录：${dependencies.absolutePath}；" +
                        "请先执行一次 Gradle 构建以下载工具链"
                )
            }
            val dirs = dependencies.listFiles().orEmpty().filter { it.isDirectory }
            val llvm = newest(
                dirs.filter { it.name.startsWith("llvm-") && (it.name.contains("windows") || it.name.contains("linux")) }
            ) ?: throw GradleException("${dependencies.absolutePath} 里没有 llvm；请先执行一次 Gradle 构建")

            val clang = llvm.executable("clang")
                ?: throw GradleException("${llvm.absolutePath}/bin 里找不到 clang")
            val llvmAr = llvm.executable("llvm-ar")
                ?: throw GradleException("${llvm.absolutePath}/bin 里找不到 llvm-ar")

            val sysroots = mutableMapOf<CodecTarget, File>()
            newest(dirs.filter { it.name.startsWith("msys2-mingw-w64-x86_64-") })
                ?.let { sysroots[CodecTarget.mingwX64] = it }
            newest(dirs.filter { it.name.startsWith("aarch64-unknown-linux-gnu-gcc-") })
                ?.let { sysroots[CodecTarget.linuxArm64] = File(it, "aarch64-unknown-linux-gnu/sysroot") }
            newest(dirs.filter { it.name.startsWith("x86_64-unknown-linux-gnu-gcc-") })
                ?.let { sysroots[CodecTarget.linuxX64] = File(it, "x86_64-unknown-linux-gnu/sysroot") }
            sysroots.entries.removeAll { !it.value.isDirectory }

            return CodecToolchain(clang, llvmAr, sysroots)
        }

        /** 依赖目录里同名组件的版本号不同（llvm-11 / llvm-21），取数字最大的那个。 */
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
    }
}

/**
 * 生成侧的目录布局，默认根目录是 `build/thirdparty`（已被 `.gitignore` 忽略）。
 *
 * ```text
 * build/thirdparty/
 *   src/<name>-<version>.tar.gz   下载的源码包
 *   tree/<name>-<version>/        解压后的源码树
 *   cmake/<target>/<lib>/         CMake 构建目录
 *   deps/<target>/zlib/           给 libpng 用的 zlib prefix
 *   out/<target>/<lib>.a          产物
 *   out/include/                  公共头文件
 * ```
 */
class CodecWorkspace(val root: File) {

    val sourcesDir = File(root, "src")
    val treeDir = File(root, "tree")
    val cmakeDir = File(root, "cmake")
    val depsDir = File(root, "deps")
    val outDir = File(root, "out")
    val includeDir = File(outDir, "include")

    fun tree(source: CodecSource): File = File(treeDir, source.treeName)

    fun targetOut(target: CodecTarget): File = File(outDir, target.name)

    fun buildDir(target: CodecTarget, label: String): File = File(cmakeDir, "${target.name}/$label")
}

/**
 * 自建工件仓库的源码镜像，没配置时返回 `null`。
 *
 * 地址与账号只有本机知道、不入库，取值顺序：
 * 1. 环境变量 `LOLOSIA_THIRDPARTY_REPO` / `_USERNAME` / `_PASSWORD`；
 * 2. `keel/local.properties` 的 `lolosia.thirdparty.repo` / `.username` / `.password`。
 *
 * 源码放在同一仓库的 `/sources/<name>/<version>/<file>` 下，取不到再回退上游 URL。
 */
class CodecRepository(
    val sourcesUrl: String,
    private val username: String?,
    private val password: String?,
) {

    fun authorization(url: String): String? {
        if (!url.startsWith(sourcesUrl) || username.isNullOrBlank()) return null
        val pair = "$username:${password.orEmpty()}"
        return "Basic " + java.util.Base64.getEncoder().encodeToString(pair.toByteArray(Charsets.UTF_8))
    }

    companion object {

        fun resolve(project: Project): CodecRepository? {
            val env = System.getenv()
            var url = env["LOLOSIA_THIRDPARTY_REPO"].orEmpty()
            var username = env["LOLOSIA_THIRDPARTY_USERNAME"].orEmpty()
            var password = env["LOLOSIA_THIRDPARTY_PASSWORD"].orEmpty()

            if (url.isBlank()) {
                val properties = File(project.rootProject.projectDir.parentFile, "local.properties")
                if (properties.isFile) {
                    val values = properties.readLines()
                        .mapNotNull { line ->
                            val index = line.indexOf('=')
                            if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
                        }
                        .toMap()
                    url = values["lolosia.thirdparty.repo"].orEmpty()
                    username = values["lolosia.thirdparty.username"].orEmpty()
                    password = values["lolosia.thirdparty.password"].orEmpty()
                }
            }

            if (url.isBlank()) return null
            // 配置里写的是 Maven 根地址，源码镜像固定在同级的 /sources 下
            val sources = url.replace(Regex("/maven/?$"), "/sources").trimEnd('/')
            return CodecRepository(sources, username.ifBlank { null }, password.ifBlank { null })
        }
    }
}

/**
 * 用 Kotlin/Native 自带的 clang + 本机 cmake 交叉编译 6 个 codec。
 *
 * zlib / libpng / libwebp / libjpeg-turbo 走各自上游的 CMake 工程（避免手工维护 config.h
 * 与 SIMD 源文件清单）；giflib / msgpack-c 没有可用的 CMake 工程，直接 clang 编译源文件归档。
 */
class CodecCompiler(
    private val workspace: CodecWorkspace,
    private val toolchain: CodecToolchain,
    private val logger: Logger,
) {

    fun compile(target: CodecTarget, sources: List<CodecSource>) {
        val zlibTree = tree(sources, "zlib")
        val pngTree = tree(sources, "png")
        val webpTree = tree(sources, "webp")
        val jpegTree = tree(sources, "jpeg")
        val gifTree = tree(sources, "gif")
        val msgpackTree = tree(sources, "msgpack")
        val outDir = workspace.targetOut(target).also { it.mkdirs() }

        logger.lifecycle("")
        logger.lifecycle("==> ================ 目标 ${target.name} ================")

        // ---- zlib ----
        val zlibBuild = workspace.buildDir(target, "zlib")
        cmake("zlib", target, zlibTree, zlibBuild, listOf("-DZLIB_BUILD_EXAMPLES=OFF"), listOf("zlibstatic"))
        val zlibLib = pickArchive(zlibBuild) { !it.name.endsWith(".dll.a") && it.name != "objects.a" }
        copyArtifact(zlibLib, outDir, "libz.a")

        // libpng 只认 find_package(ZLIB)：按约定给它摆一个 prefix 出来；
        // zconf.h 由 zlib 的 CMake 生成在构建目录（源码树里那份已改名成 zconf.h.included）
        val zlibPrefix = File(workspace.depsDir, "${target.name}/zlib")
        copy(File(zlibTree, "zlib.h"), File(zlibPrefix, "include/zlib.h"))
        copy(File(zlibBuild, "zconf.h"), File(zlibPrefix, "include/zconf.h"))
        copy(zlibLib, File(zlibPrefix, "lib/libz.a"))

        // ---- libpng ----
        val pngBuild = workspace.buildDir(target, "libpng")
        cmake(
            "libpng", target, pngTree, pngBuild, listOf(
                "-DPNG_SHARED=OFF",
                "-DPNG_STATIC=ON",
                "-DPNG_TESTS=OFF",
                "-DPNG_TOOLS=OFF",
                "-DPNG_HARDWARE_OPTIMIZATIONS=OFF",
                "-DZLIB_ROOT=${zlibPrefix.path.forCMake()}",
            ), listOf("png_static")
        )
        val pngLib = pickArchive(pngBuild) { it.name.contains("png") && it.name != "objects.a" }
        copyArtifact(pngLib, outDir, "libpng.a")

        // ---- libwebp ----
        // 1.6 起编码侧的 SharpYuv 拆成独立归档 libsharpyuv.a，这里合并回单个 libwebp.a：
        // 保留两个归档时 K/N 的 ELF 链接按归档逐个解析，顺序被重排后 SharpYuv* 会解析不到。
        val webpBuild = workspace.buildDir(target, "libwebp")
        cmake(
            "libwebp", target, webpTree, webpBuild, listOf(
                "-DWEBP_BUILD_ANIM_UTILS=OFF",
                "-DWEBP_BUILD_CWEBP=OFF",
                "-DWEBP_BUILD_DWEBP=OFF",
                "-DWEBP_BUILD_GIF2WEBP=OFF",
                "-DWEBP_BUILD_IMG2WEBP=OFF",
                "-DWEBP_BUILD_VWEBP=OFF",
                "-DWEBP_BUILD_WEBPINFO=OFF",
                "-DWEBP_BUILD_WEBPMUX=OFF",
                "-DWEBP_BUILD_LIBWEBPMUX=OFF",
                "-DWEBP_BUILD_EXTRAS=OFF",
            ), listOf("webp")
        )
        val webpLib = findNamed(webpBuild, "libwebp.a")
            ?: throw GradleException("libwebp.a 未生成 [${target.name}]")
        val sharpLib = findNamed(webpBuild, "libsharpyuv.a")
            ?: throw GradleException("libsharpyuv.a 未生成（libwebp 版本变化？）[${target.name}]")
        mergeWebp(target, outDir, webpLib, sharpLib)

        // ---- libjpeg-turbo（关闭 SIMD 免装 nasm）----
        val jpegBuild = workspace.buildDir(target, "libjpeg-turbo")
        cmake(
            "libjpeg-turbo", target, jpegTree, jpegBuild, listOf(
                "-DENABLE_SHARED=0",
                "-DENABLE_STATIC=1",
                "-DWITH_SIMD=0",
                "-DWITH_TURBOJPEG=1",
                "-DWITH_JAVA=0",
                "-DWITH_12BIT=0",
            ), listOf("jpeg-static", "turbojpeg-static")
        )
        copyArtifact(
            findNamed(jpegBuild, "libjpeg.a") ?: throw GradleException("libjpeg.a 未生成 [${target.name}]"),
            outDir, "libjpeg.a"
        )
        copyArtifact(
            findNamed(jpegBuild, "libturbojpeg.a") ?: throw GradleException("libturbojpeg.a 未生成 [${target.name}]"),
            outDir, "libturbojpeg.a"
        )

        // ---- giflib（无 CMake，按上游 Makefile 的 SOURCES 编译）----
        val gifSources = listOf(
            "dgif_lib.c", "egif_lib.c", "gifalloc.c", "gif_err.c",
            "gif_font.c", "gif_hash.c", "openbsd-reallocarray.c",
        ).map { File(gifTree, it) }
        val gifObjects = compileC(
            "giflib", target, workspace.buildDir(target, "giflib"), gifSources, emptyList()
        )
        archive(outDir, "libgif.a", gifObjects)

        // ---- msgpack-c（无 CMake：clang 直接编译 src/*.c；配置头先由模板生成）----
        prepareMsgpackHeaders(msgpackTree)
        val msgpackInclude = File(msgpackTree, "include")
        val msgpackSources = listOf("objectc.c", "unpack.c", "version.c", "vrefbuffer.c", "zone.c")
            .map { File(msgpackTree, "src/$it") }
        val msgpackObjects = compileC(
            "msgpack-c", target, workspace.buildDir(target, "msgpack-c"), msgpackSources, listOf(msgpackInclude)
        )
        archive(outDir, "libmsgpackc.a", msgpackObjects)
    }

    /** 汇总公共头文件；[target] 只决定从哪个目标的 CMake 构建目录取生成的 zconf.h / pnglibconf.h。 */
    fun copyHeaders(target: CodecTarget, sources: List<CodecSource>) {
        val zlibTree = tree(sources, "zlib")
        val pngTree = tree(sources, "png")
        val webpTree = tree(sources, "webp")
        val jpegTree = tree(sources, "jpeg")
        val gifTree = tree(sources, "gif")
        val msgpackInclude = File(tree(sources, "msgpack"), "include")

        logger.lifecycle("==> 复制公共头文件到 ${workspace.includeDir.absolutePath}")
        val headers = listOf(
            File(zlibTree, "zlib.h") to "zlib/zlib.h",
            File(workspace.buildDir(target, "zlib"), "zconf.h") to "zlib/zconf.h",
            File(pngTree, "png.h") to "libpng/png.h",
            File(pngTree, "pngconf.h") to "libpng/pngconf.h",
            File(workspace.buildDir(target, "libpng"), "pnglibconf.h") to "libpng/pnglibconf.h",
            File(webpTree, "src/webp/encode.h") to "libwebp/webp/encode.h",
            File(webpTree, "src/webp/decode.h") to "libwebp/webp/decode.h",
            File(webpTree, "src/webp/types.h") to "libwebp/webp/types.h",
            File(jpegTree, "src/turbojpeg.h") to "libjpeg/turbojpeg.h",
            File(gifTree, "gif_lib.h") to "giflib/gif_lib.h",
        )
        headers.forEach { (from, to) ->
            copy(from, File(workspace.includeDir, to))
            logger.info("    -> $to")
        }

        // msgpack-c 头文件较多（msgpack.h + msgpack/ 子树，含模板生成的 sysdep.h / pack_template.h），整目录拷贝
        val msgpackDest = File(workspace.includeDir, "msgpack")
        msgpackDest.deleteRecursively()
        copy(File(msgpackInclude, "msgpack.h"), File(workspace.includeDir, "msgpack.h"))
        if (!File(msgpackInclude, "msgpack").copyRecursively(msgpackDest, overwrite = true)) {
            throw GradleException("复制 msgpack 头文件失败：$msgpackInclude")
        }
        logger.info("    -> msgpack.h + msgpack/*")
    }

    private fun tree(sources: List<CodecSource>, library: String): File {
        val source = sources.firstOrNull { it.library == library }
            ?: throw GradleException("manifest 里缺少 $library")
        val dir = workspace.tree(source)
        if (!dir.isDirectory) throw GradleException("源码目录不存在：${dir.absolutePath}")
        return dir
    }

    private fun cmake(
        label: String,
        target: CodecTarget,
        sourceDir: File,
        buildDir: File,
        options: List<String>,
        buildTargets: List<String>,
    ) {
        logger.lifecycle("==> cmake configure  $label [${target.name}]")
        val configure = mutableListOf("cmake", "-S", sourceDir.path, "-B", buildDir.path)
        if (CodecToolchain.isWindowsHost) configure += listOf("-G", "MinGW Makefiles")
        configure += listOf(
            "-DCMAKE_SYSTEM_NAME=${target.systemName}",
            "-DCMAKE_SYSTEM_PROCESSOR=${target.processor}",
            // 交叉编译不做 try_run，试编译产物直接当静态库，省掉链接器依赖
            "-DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY",
            "-DCMAKE_C_COMPILER=${toolchain.clang.path.forCMake()}",
            "-DCMAKE_C_COMPILER_TARGET=${target.triple}",
            "-DCMAKE_SYSROOT=${toolchain.sysroot(target).path.forCMake()}",
            "-DCMAKE_BUILD_TYPE=Release",
            "-DBUILD_SHARED_LIBS=OFF",
        )
        configure += options
        run(configure)

        logger.lifecycle("==> cmake build      $label [${target.name}]")
        val build = mutableListOf("cmake", "--build", buildDir.path, "--parallel")
        if (buildTargets.isNotEmpty()) {
            build += "--target"
            build += buildTargets
        }
        run(build)
    }

    private fun compileC(
        label: String,
        target: CodecTarget,
        outDir: File,
        sources: List<File>,
        includeDirs: List<File>,
    ): List<File> {
        logger.lifecycle("==> clang 编译        $label [${target.name}]")
        outDir.mkdirs()
        return sources.map { source ->
            val obj = File(outDir, source.name.replace(Regex("""\.c$"""), ".o"))
            val command = mutableListOf(
                toolchain.clang.path,
                "--target=${target.triple}",
                "--sysroot=${toolchain.sysroot(target).path}",
                "-O2", "-fPIC", "-fno-strict-aliasing", "-std=gnu99", "-Wall",
            )
            includeDirs.forEach { command += "-I${it.path}" }
            command += listOf("-c", source.path, "-o", obj.path)
            run(command)
            obj
        }
    }

    private fun mergeWebp(target: CodecTarget, outDir: File, webpLib: File, sharpLib: File) {
        val mergeDir = workspace.buildDir(target, "webp-merge")
        mergeDir.deleteRecursively()
        mergeDir.mkdirs()
        // 成员后缀随目标而异（mingw 是 .obj，linux 是 .o），解出来直接全部归档
        // llvm-ar 一次只接受一个归档，第二个路径会被当成员名，所以分两次解包
        run(listOf(toolchain.llvmAr.path, "x", webpLib.path), mergeDir)
        run(listOf(toolchain.llvmAr.path, "x", sharpLib.path), mergeDir)
        val members = mergeDir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()
        if (members.isEmpty()) throw GradleException("libwebp 合并失败：没有解出成员 [${target.name}]")

        val outLib = File(outDir, "libwebp.a")
        outLib.delete()
        run(listOf(toolchain.llvmAr.path, "rcs", outLib.path) + members, mergeDir)
        logger.lifecycle("    -> libwebp.a (${outLib.length() / 1024} KB，含 SharpYuv)")
    }

    private fun archive(outDir: File, name: String, objects: List<File>) {
        val lib = File(outDir, name)
        lib.delete()
        run(listOf(toolchain.llvmAr.path, "rcs", lib.path) + objects.map { it.path })
        logger.lifecycle("    -> $name (${lib.length() / 1024} KB)")
    }

    /** sysdep.h / pack_template.h 由 cmake 的 configure_file 生成，只有字节序宏要替换；三个目标都是小端。 */
    private fun prepareMsgpackHeaders(tree: File) {
        val includeDir = File(tree, "include/msgpack").also { it.mkdirs() }
        listOf("sysdep.h.in" to "sysdep.h", "pack_template.h.in" to "pack_template.h").forEach { (template, output) ->
            val content = File(tree, "cmake/$template").readText()
                .replace("@MSGPACK_ENDIAN_BIG_BYTE@", "0")
                .replace("@MSGPACK_ENDIAN_LITTLE_BYTE@", "1")
            File(includeDir, output).writeText(content)
        }
    }

    private fun pickArchive(root: File, predicate: (File) -> Boolean): File =
        root.walkTopDown()
            .filter { it.isFile && it.extension == "a" && predicate(it) }
            .maxByOrNull { it.length() }
            ?: throw GradleException("没有在 ${root.absolutePath} 找到静态库")

    private fun findNamed(root: File, name: String): File? =
        root.walkTopDown().firstOrNull { it.isFile && it.name == name }

    private fun copyArtifact(from: File, toDir: File, name: String) {
        val dest = File(toDir, name)
        copy(from, dest)
        logger.lifecycle("    -> $name (${dest.length() / 1024} KB)")
    }

    private fun copy(from: File, to: File) {
        if (!from.isFile) throw GradleException("缺少文件：${from.absolutePath}")
        to.parentFile?.mkdirs()
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun run(command: List<String>, workingDir: File? = null) {
        logger.info("执行：${command.joinToString(" ")}")
        val process = try {
            ProcessBuilder(command)
                .apply { if (workingDir != null) directory(workingDir) }
                .redirectErrorStream(true)
                .start()
        } catch (e: java.io.IOException) {
            throw GradleException("无法执行 ${command.first()}：${e.message}", e)
        }
        process.inputStream.use { it.copyTo(System.out) }
        val exitCode = process.waitFor()
        if (exitCode != 0) throw GradleException("命令失败（exit $exitCode）：${command.joinToString(" ")}")
    }

    private fun String.forCMake(): String = replace('\\', '/')
}

/** 注册生成侧的全部任务：下载 / 解压源码、按目标交叉编译、汇总公共头文件。 */
private class CodecBuild(private val project: Project) {

    fun register(manifestFile: File) {
        val sources = readManifest(manifestFile)
        val workspace = CodecWorkspace(project.layout.buildDirectory.dir("thirdparty").get().asFile)
        val tarballs = sources.map { File(workspace.sourcesDir, it.fileName) }
        val repository = CodecRepository.resolve(project)

        val download = project.tasks.register("downloadCodecSources") {
            group = "thirdparty"
            description = "下载 manifest 里各 codec 的源码包（先自建镜像、再上游，校验 sha256）"
            // 不做增量：每次核对 sha256，只有缺失或损坏才联网
            doLast { downloadSources(workspace, sources, repository) }
        }

        val extract = project.tasks.register("extractCodecSources") {
            group = "thirdparty"
            description = "把源码包解到 build/thirdparty/tree"
            dependsOn(download)
            inputs.files(tarballs)
            doLast { extractSources(workspace, sources) }
        }

        val compiled = CodecTarget.entries.map { target ->
            project.tasks.register("compileCodecs${target.name.capitalized()}") {
                group = "thirdparty"
                description = "交叉编译 6 个 codec 到 build/thirdparty/out/${target.name}（${target.triple}）"
                dependsOn(extract)
                inputs.files(tarballs)
                outputs.dir(workspace.targetOut(target))
                doLast {
                    CodecCompiler(workspace, CodecToolchain.detect(), logger).compile(target, sources)
                }
            }
        }

        val headers = project.tasks.register("codecHeaders") {
            group = "thirdparty"
            description = "汇总公共头文件到 build/thirdparty/out/include"
            dependsOn(compiled)
            outputs.dir(workspace.includeDir)
            doLast {
                CodecCompiler(workspace, CodecToolchain.detect(), logger)
                    .copyHeaders(CodecTarget.entries.first(), sources)
            }
        }

        project.tasks.register("compileCodecs") {
            group = "build"
            description = "从上游源码编译全部 codec（mingwX64 / linuxArm64 / linuxX64）"
            dependsOn(headers)
        }
    }

    private fun readManifest(manifestFile: File): List<CodecSource> {
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(manifestFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val libraries = root["libraries"] as? Map<String, Map<String, Any?>>
            ?: throw GradleException("${manifestFile.name} 缺少 libraries")

        return libraries.map { (library, spec) ->
            @Suppress("UNCHECKED_CAST")
            val source = spec["source"] as? Map<String, Any?>
                ?: throw GradleException("${manifestFile.name}：libraries.$library.source 不能为空")
            CodecSource(
                library = library,
                name = source["name"]?.toString()
                    ?: throw GradleException("${manifestFile.name}：libraries.$library.source.name 不能为空"),
                version = spec["version"]?.toString()
                    ?: throw GradleException("${manifestFile.name}：libraries.$library.version 不能为空"),
                url = source["url"]?.toString().orEmpty(),
                sha256 = source["sha256"]?.toString().orEmpty(),
            )
        }
    }

    private fun downloadSources(
        workspace: CodecWorkspace,
        sources: List<CodecSource>,
        repository: CodecRepository?,
    ) {
        val logger = project.logger
        workspace.sourcesDir.mkdirs()
        sources.forEach { source ->
            val tarball = File(workspace.sourcesDir, source.fileName)
            if (tarball.isFile && tarball.sha256() == source.sha256.lowercase()) {
                logger.info("已存在 ${source.fileName}")
                return@forEach
            }
            tarball.delete()

            val urls = mutableListOf<String>()
            repository?.let { urls += "${it.sourcesUrl}/${source.name}/${source.version}/${source.fileName}" }
            urls += source.url

            var lastError: Exception? = null
            for (url in urls) {
                try {
                    logger.lifecycle("==> 下载 ${source.name} ${source.version} <- $url")
                    fetch(url, tarball, repository?.authorization(url))
                    val actual = tarball.sha256()
                    if (actual != source.sha256.lowercase()) {
                        throw GradleException("sha256 不匹配（期望 ${source.sha256}，实际 $actual）")
                    }
                    return@forEach
                } catch (e: Exception) {
                    lastError = e
                    logger.warn("!!  ${source.name}：${e.message}")
                    tarball.delete()
                }
            }
            throw GradleException("${source.name}：源码下载失败（已尝试：${urls.joinToString(" | ")}）", lastError)
        }
    }

    private fun extractSources(workspace: CodecWorkspace, sources: List<CodecSource>) {
        val logger = project.logger
        workspace.treeDir.mkdirs()
        sources.forEach { source ->
            val tree = workspace.tree(source)
            if (tree.isDirectory && tree.listFiles().orEmpty().isNotEmpty()) return@forEach

            val tarball = File(workspace.sourcesDir, source.fileName)
            logger.lifecycle("==> 解压 ${source.fileName}")
            tree.deleteRecursively()
            // 用系统 tar（Windows 10+ 自带 bsdtar，Linux / macOS 都有），与原来的 PS1 行为一致
            val process = ProcessBuilder(
                "tar", "-xzf", tarball.path, "-C", workspace.treeDir.path
            ).redirectErrorStream(true).start()
            process.inputStream.use { it.copyTo(System.out) }
            val exitCode = process.waitFor()
            if (exitCode != 0) throw GradleException("解压失败（exit $exitCode）：${tarball.absolutePath}")
            if (tree.listFiles().orEmpty().isEmpty()) {
                throw GradleException("解压后目录为空：${tree.absolutePath}")
            }
        }
    }

    private fun fetch(url: String, target: File, authorization: String?) {
        target.parentFile.mkdirs()
        if (url.startsWith("file:")) {
            Files.copy(File(URI(url)).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            return
        }

        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000
        connection.instanceFollowRedirects = true
        if (authorization != null) connection.setRequestProperty("Authorization", authorization)
        try {
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
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

    private fun String.capitalized(): String = replaceFirstChar { it.uppercaseChar() }
}

CodecBuild(project).register(file("manifest.json"))
