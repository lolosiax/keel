package moe.lolosia.keel.api.error

import moe.lolosia.keel.api.log.RemoteThrowable
import moe.lolosia.keel.api.log.StackTraceFormat

/**
 * 在 Host / 另一个插件里重建的「远端异常」。
 *
 * 异常的类无法跨 Kotlin/Native 运行时重建（没有反射式类加载），所以这里保留原始
 * 类型名与结构化帧；[toString] 输出**原始名字**而不是 `KeelRemoteException`，
 * 保证日志第一行与抛出方看到的一致。
 *
 * 远端帧之后是 `--- rethrown at host ---` 和调用方帧；本类实现 [RemoteThrowable]，
 * 日志渲染时不会再追加它自己的构造栈。
 */
class KeelRemoteException(
    val plugin: String,
    val chain: List<KeelErrorEntry>,
    /** 消息泵之前的调用方栈：出了问题要知道是谁发起的，而不是调度器唤醒点。 */
    val callerFrames: List<KeelErrorFrame> = emptyList(),
) : RuntimeException(chain.firstOrNull()?.message ?: ""), RemoteThrowable {

    override fun toString(): String = buildString {
        chain.forEachIndexed { index, entry ->
            if (index > 0) append("Caused by: ")
            append(entry.type)
            if (entry.message.isNotEmpty()) append(": ").append(entry.message)
            if (index == 0 && plugin.isNotEmpty()) append(" [plugin ").append(plugin).append(']')
            append('\n')
            StackTraceFormat.appendTrace(this, entry.createdAt, entry.throwSites)
            append(renderFrames(entry.frames))
        }
        append("    --- rethrown at host ---")
        if (callerFrames.isNotEmpty()) {
            append('\n')
            append(renderFrames(callerFrames))
        }
    }
}
