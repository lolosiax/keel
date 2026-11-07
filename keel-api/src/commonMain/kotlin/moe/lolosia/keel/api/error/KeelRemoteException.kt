package moe.lolosia.keel.api.error

/**
 * 在 Host / 另一个插件里重建的「远端异常」。
 *
 * 异常的类无法跨 Kotlin/Native 运行时重建（没有反射式类加载），所以这里保留原始
 * 类型名与帧文本；[toString] 输出**原始名字**而不是 `KeelRemoteException`，
 * 保证日志第一行与抛出方看到的一致。
 *
 * 远端帧排在本地重抛栈之前，最后一行 `--- rethrown at host ---` 之后是
 * `stackTraceToString()` 追加的本地帧。
 */
class KeelRemoteException(
    val plugin: String,
    val remoteType: String,
    val remoteMessage: String,
    val remoteTrace: String,
    val truncated: Boolean = false,
) : RuntimeException(remoteMessage) {

    override fun toString(): String = buildString {
        append(remoteType)
        if (remoteMessage.isNotEmpty()) append(": ").append(remoteMessage)
        if (plugin.isNotEmpty()) append(" [plugin ").append(plugin).append(']')
        append('\n')
        for (line in remoteTrace.lines()) {
            if (line.isNotEmpty()) append("    ").append(line).append('\n')
        }
        if (truncated) append("    (错误栈已截断)\n")
        append("    --- rethrown at host ---")
    }
}
