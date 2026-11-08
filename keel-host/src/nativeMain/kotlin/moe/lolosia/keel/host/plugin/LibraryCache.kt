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
