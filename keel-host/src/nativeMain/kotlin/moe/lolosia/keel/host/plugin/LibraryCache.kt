package moe.lolosia.keel.host.plugin

/**
 * 把 `.keel` 包里的动态库落到缓存目录。
 *
 * 每次都重写：只比大小挡不住「同尺寸重构」（改字符串常量很容易让 dll 大小不变），
 * 曾经因此加载到上一版插件。目标文件被正在运行的进程占用时（Windows 共享冲突、
 * Linux ETXTBSY），退到 `name.1.dll`、`name.2.dll` …… 换名加载。
 *
 * 插件与能力包共用这份逻辑；两者只是后续的加载方式不同。
 */
internal fun installLibrary(
    cacheRoot: String,
    manifest: KeelPackageManifest,
    libPath: String,
    bytes: ByteArray,
): String {
    val dir = "$cacheRoot/${manifest.id}/${manifest.version}"
    mkdirs(dir)
    val fileName = libPath.substringAfterLast('/')
    val stem = fileName.substringBeforeLast('.', fileName)
    val suffix = fileName.removePrefix(stem)
    var target = "$dir/$fileName"
    var attempt = 0
    while (true) {
        try {
            PluginFs.writeBytes(target, bytes)
            return target
        } catch (e: Throwable) {
            attempt++
            // 文件被占用就换名，最多试几个，避免无限增长
            if (attempt > 8) throw e
            target = "$dir/$stem.$attempt$suffix"
        }
    }
}

/**
 * 把能力包一个 `lib/<target>/` 目录整体落到缓存目录。
 *
 * 能力包可能带同目录依赖（例如 `libmariadb.dll`），加载器必须保留相对布局；
 * 返回入口库的绝对路径。任一文件被占用时整组换到 `<version>.1` 等代次目录，
 * 不能只给入口库换名 —— 依赖文件名与导入表都写死了。
 */
internal fun installLibrarySet(
    cacheRoot: String,
    manifest: KeelPackageManifest,
    targetDir: String,
    entryPath: String,
    files: List<Pair<String, ByteArray>>,
): String {
    val base = "$cacheRoot/${manifest.id}/${manifest.version}"
    val prefix = "$targetDir/"
    val entryName = entryPath.removePrefix(prefix)
    require(entryName != entryPath) { "入口库 $entryPath 不在 $targetDir 下" }

    var attempt = 0
    while (true) {
        val dir = if (attempt == 0) base else "$base.$attempt"
        try {
            files.forEach { (path, bytes) ->
                val relative = path.removePrefix(prefix)
                val target = "$dir/$targetDir/$relative"
                mkdirs(target.substringBeforeLast('/'))
                PluginFs.writeBytes(target, bytes)
            }
            return "$dir/$targetDir/$entryName"
        } catch (e: Throwable) {
            attempt++
            if (attempt > 8) throw e
        }
    }
}
