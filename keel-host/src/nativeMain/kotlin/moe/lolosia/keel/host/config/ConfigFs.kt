package moe.lolosia.keel.host.config

import platform.posix.remove
import platform.posix.rename
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.host.plugin.PluginFs
import moe.lolosia.keel.host.plugin.fileSizeOrNull
import moe.lolosia.keel.host.plugin.mkdirs

/**
 * 配置文件的读写与落盘。
 *
 * 与 `PluginFs` 分开：配置要保留 `.bak`、先写临时文件再替换，失败时不破坏原文件。
 * 所有调用都在 Host 事件泵线程上，因此不需要额外加锁。
 */
internal object ConfigFs {

    fun exists(path: String): Boolean = fileSizeOrNull(path) != null

    /** 读取整份文件；不存在或读不出来时返回 null（首次运行没有配置文件是正常情况）。 */
    fun readTextOrNull(path: String): String? =
        if (!exists(path)) null else runCatching { PluginFs.readBytes(path).decodeToString() }.getOrNull()

    /**
     * 原子写入：`<path>.tmp` 写完，旧文件备份成 `.bak`，再替换目标文件。
     *
     * Windows 的 `rename` 不能覆盖已存在的文件，所以先删除旧文件；
     * 删除后替换失败会尽量把备份写回去，避免留下半个文件。
     */
    fun writeText(path: String, text: String) {
        val bytes = (if (text.isEmpty()) "\n" else text).encodeToByteArray()
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) mkdirs(parent)

        val tmp = "$path.tmp"
        PluginFs.writeBytes(tmp, bytes)

        if (exists(path)) {
            runCatching { PluginFs.writeBytes("$path.bak", PluginFs.readBytes(path)) }
                .onFailure { Log.warn("配置备份失败：$path.bak: ${it.message}", tag = "Config") }
            remove(path)
        }
        if (rename(tmp, path) != 0) {
            // 少数平台不允许替换式 rename：退化为直接覆盖，至少保证内容正确
            PluginFs.writeBytes(path, bytes)
            remove(tmp)
        }
    }
}
