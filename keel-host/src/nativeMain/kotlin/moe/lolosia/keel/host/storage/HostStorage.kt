package moe.lolosia.keel.host.storage

import moe.lolosia.keel.host.plugin.HostPluginIds
import moe.lolosia.keel.host.plugin.PluginFs
import moe.lolosia.keel.host.plugin.fileSizeOrNull
import moe.lolosia.keel.host.plugin.mkdirs

/**
 * 按插件命名空间隔离的文件存储。
 *
 * 根目录 `<workDir>/plugins/<manifest-id>/`；插件只提供相对路径，Host 负责
 * 拒绝 `..`、绝对路径与带盘符的段。所有调用都在事件泵线程上串行完成。
 */
internal object HostStorage {

    /** 单对象上限；超过直接报错，不截断。 */
    private const val MAX_BYTES = 8 * 1024 * 1024

    private var root: String = "."

    fun init(dir: String) {
        root = dir.trimEnd('/', '\\').ifEmpty { "." }
    }

    fun read(pluginId: ULong, path: String): ByteArray? {
        val file = fileOf(pluginId, path)
        if (fileSizeOrNull(file) == null) return null
        return PluginFs.readBytes(file)
    }

    fun write(pluginId: ULong, path: String, bytes: ByteArray) {
        require(bytes.size <= MAX_BYTES) { "存储对象超过 8MB：$path" }
        val file = fileOf(pluginId, path)
        mkdirs(file.substringBeforeLast('/', "."))
        PluginFs.writeBytes(file, bytes)
    }

    fun delete(pluginId: ULong, path: String): Boolean = PluginFs.delete(fileOf(pluginId, path))

    private fun fileOf(pluginId: ULong, path: String): String {
        val name = HostPluginIds.nameOf(pluginId)
            ?: error("插件 $pluginId 没有登记存储命名空间")
        val clean = path.replace('\\', '/').trim('/')
        val segments = clean.split('/')
        require(
            segments.isNotEmpty() &&
                segments.none { it.isEmpty() || it == "." || it == ".." || it.contains(':') }
        ) { "非法存储路径：$path" }
        return "$root/plugins/$name/$clean"
    }
}
