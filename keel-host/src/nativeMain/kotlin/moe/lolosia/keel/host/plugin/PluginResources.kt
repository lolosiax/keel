package moe.lolosia.keel.host.plugin

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.classpath.Classpath

/**
 * 插件每次加载独占的资源目录与资源物化。
 *
 * 布局 `<cacheRoot>/<manifest-id>/<version>/<loadSeq>/`：每次加载分配新目录，
 * 已加载的 DLL 不会被覆盖（Windows 上被占用的文件无法覆盖，改名又找不到依赖），
 * 旧目录由下次 Host 启动时回收。
 *
 * 所有方法都在事件泵线程调用，不需要加锁。
 */
internal object PluginResources {

    /** 一次加载独占的目录，以及读取其 `.keel` 资源的视图。 */
    private class Load(
        val dir: String,
        val classpath: Classpath,
    )

    /** 缓存根；`init` 时可能回退到用户缓存目录。 */
    private var root: String = "."

    /** `"<id>/<version>"` → 本次运行已分配到的序号。 */
    private val seqs = mutableMapOf<String, Int>()

    /** 数字 plugin id → 本次加载的目录。 */
    private val loaded = mutableMapOf<ULong, Load>()

    /** `<cacheRoot>` 下不属于插件资源目录的保留子目录。 */
    private val reserved = setOf("plugins", "config", "capabilities")

    /**
     * 确定资源根并回收上次运行的加载目录。
     *
     * [cacheRoot] 不可写（安装目录只读）时回退到用户缓存目录；两者都不可写时
     * 只记错误，后续物化会以明确的 IO 错误失败。
     */
    fun init(cacheRoot: String) {
        root = cacheRoot.trimEnd('/', '\\').ifEmpty { "." }
        if (!writable(root)) {
            val fallback = userCacheRoot()?.trimEnd('/', '\\')
            root = if (fallback != null && writable(fallback)) {
                Log.warn(
                    "cache root 不可写（$cacheRoot），插件资源改用 ${nativePath(fallback)}",
                    tag = "Loader",
                )
                fallback
            } else {
                Log.error("cache root 不可写：$cacheRoot", tag = "Loader")
                root
            }
        }
        cleanup()
    }

    /**
     * 为一次加载分配目录，并登记它的资源视图。
     *
     * @return 目录绝对路径（当前平台分隔符）
     */
    fun beginLoad(pluginId: ULong, plugin: LoadedPlugin): String {
        val id = plugin.manifest.id
        require(isSegment(id) && id !in reserved) { "插件 id 不能用作目录名：$id" }
        val version = safeName(plugin.manifest.version)
        val key = "$id/$version"
        val base = "$root/$key"
        var seq = (seqs[key] ?: lastSeq(base)) + 1
        var dir = "$base/$seq"
        // 上次运行没删掉的目录（文件仍被占用）：跳到下一个序号，绝不复用
        while (listNames(dir) != null) {
            seq++
            dir = "$base/$seq"
        }
        mkdirs(dir)
        require(listNames(dir) != null) { "无法创建插件资源目录：${nativePath(dir)}" }
        seqs[key] = seq
        loaded[pluginId] = Load(dir, plugin.classpathRef)
        return nativePath(dir)
    }

    /** 停用 / 启动失败：注销映射，目录留在磁盘上等下次启动回收。 */
    fun endLoad(pluginId: ULong) {
        loaded.remove(pluginId)
    }

    /** 当前加载的缓存目录；插件未加载时返回 null。 */
    fun cacheDir(pluginId: ULong): String? = loaded[pluginId]?.dir?.let(::nativePath)

    /**
     * 把 `.keel` 里的资源写到本次加载目录，返回绝对路径。
     *
     * @throws IllegalArgumentException 路径非法、条目不存在或写入失败
     * @throws IllegalStateException 插件没有在加载状态
     */
    fun materialize(pluginId: ULong, path: String): String {
        val load = loaded[pluginId]
            ?: throw IllegalStateException("插件 $pluginId 尚未加载，无法物化资源")
        val rel = normalizeResourcePath(path)
        val target = "${load.dir}/$rel"

        if (load.classpath.exists(rel)) {
            writeEntry(load, rel, target)
            return nativePath(target)
        }

        val prefix = "$rel/"
        val entries = load.classpath.list(prefix)
        require(entries.isNotEmpty()) { "包内没有资源：$path" }
        entries.forEach { entry ->
            writeEntry(load, entry, "$target/${entry.removePrefix(prefix)}")
        }
        return nativePath(target)
    }

    /** 目录不可写时返回 false；顺带把目录建出来。 */
    private fun writable(dir: String): Boolean {
        mkdirs(dir)
        val probe = "$dir/.keel-write-test"
        return try {
            PluginFs.writeBytes(probe, ByteArray(0))
            PluginFs.delete(probe)
        } catch (_: Throwable) {
            false
        }
    }

    /** 删除上次运行留下的 `<loadSeq>` 目录；除保留目录外都不碰。 */
    private fun cleanup() {
        val ids = listNames(root) ?: return
        ids.forEach { id ->
            if (id.startsWith(".") || id in reserved) return@forEach
            val idDir = "$root/$id"
            listNames(idDir)?.forEach { version ->
                val versionDir = "$idDir/$version"
                listNames(versionDir)?.forEach seqLoop@{ seq ->
                    if (!isSeq(seq)) return@seqLoop
                    val dir = "$versionDir/$seq"
                    if (!deleteTree(dir)) {
                        Log.warn("回收加载目录失败（文件仍被占用）：$dir", tag = "Loader")
                    }
                }
            }
        }
    }

    /** 递归删除；返回 false 表示有文件或目录没删掉。 */
    private fun deleteTree(path: String): Boolean {
        val names = listNames(path)
        if (names == null) return PluginFs.delete(path)
        var deleted = true
        names.forEach { name ->
            if (!deleteTree("$path/$name")) deleted = false
        }
        if (!removeDirectory(path)) deleted = false
        return deleted
    }

    /** 本次运行已分配到的最大序号；目录不存在时返回 0。 */
    private fun lastSeq(base: String): Int =
        listNames(base)
            ?.filter { isSeq(it) }
            ?.mapNotNull { it.toIntOrNull() }
            ?.maxOrNull() ?: 0

    private fun isSeq(name: String): Boolean = name.isNotEmpty() && name.all { it.isDigit() }

    /**
     * 校验包内相对路径：拒绝 `..`、前导分隔符、盘符与空段。
     *
     * @throws IllegalArgumentException 路径非法
     */
    private fun normalizeResourcePath(path: String): String {
        require(path.isNotEmpty() && !path.startsWith('/') && !path.startsWith('\\')) {
            "资源路径必须是包内相对路径：$path"
        }
        val clean = path.replace('\\', '/').trimEnd('/')
        val segments = clean.split('/')
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." && !it.contains(':') }) {
            "非法资源路径：$path"
        }
        return clean
    }

    /**
     * 写出一个条目；同名同大小视为已物化，不重写。
     *
     * 先写 `.part` 再改名，避免中途失败留下半个文件。
     */
    private fun writeEntry(load: Load, entry: String, target: String) {
        val bytes = load.classpath.read(entry)
            ?: throw IllegalArgumentException("包内缺少资源：$entry")
        if (fileSizeOrNull(target) == bytes.size.toLong()) return

        mkdirs(target.substringBeforeLast('/'))
        if (fileSizeOrNull(target) != null && !PluginFs.delete(target)) {
            throw IllegalArgumentException("资源文件被占用，无法覆盖：$target")
        }
        val part = "$target.part"
        PluginFs.writeBytes(part, bytes)
        if (!PluginFs.rename(part, target)) {
            PluginFs.delete(part)
            throw IllegalArgumentException("无法写入资源：$target")
        }
    }

    private fun isSegment(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." &&
            value.none { it == '/' || it == '\\' || it == ':' }

    /** 版本号只用来拼目录名，非法字符统一换成 `_`。 */
    private fun safeName(value: String): String {
        val cleaned = value.map {
            if (it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') it else '_'
        }.joinToString("").trim('.')
        return cleaned.ifEmpty { "unknown" }
    }
}
