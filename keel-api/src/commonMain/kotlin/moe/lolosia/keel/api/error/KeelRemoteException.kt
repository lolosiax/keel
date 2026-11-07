package moe.lolosia.keel.api.error

/**
 * 在 Host / 另一个插件里重建的「远端异常」。
 *
 * 异常的类无法跨 Kotlin/Native 运行时重建（没有反射式类加载），所以这里保留原始
 * 类型名与结构化帧；[toString] 输出**原始名字**而不是 `KeelRemoteException`，
 * 保证日志第一行与抛出方看到的一致。
 *
 * 远端帧排在本地重抛栈之前，最后一行 `--- rethrown at host ---` 之后是
 * `stackTraceToString()` 追加的本地帧。
 */
class KeelRemoteException(
    val plugin: String,
    val chain: List<KeelErrorEntry>,
    /** 消息泵之前的调用方栈：出了问题要知道是谁发起的，而不是调度器唤醒点。 */
    val callerFrames: List<KeelErrorFrame> = emptyList(),
) : RuntimeException(chain.firstOrNull()?.message ?: "") {

    override fun toString(): String = buildString {
        chain.forEachIndexed { index, entry ->
            if (index > 0) append("Caused by: ")
            append(entry.type)
            if (entry.message.isNotEmpty()) append(": ").append(entry.message)
            if (index == 0 && plugin.isNotEmpty()) append(" [plugin ").append(plugin).append(']')
            append('\n')
            append(renderFrames(entry.frames))
        }
        append("    --- rethrown at host ---")
        if (callerFrames.isNotEmpty()) {
            append('\n')
            append(renderFrames(callerFrames))
        }
    }
}
