package moe.lolosia.keel.plugins.console

/**
 * 控制通道字面量。
 *
 * 其他插件给 `target = "console"`、`channel = "console"` 发消息；载荷是 UTF-8 文本，
 * 第一个词是命令，`title` 后面整段都是标题。应答同样是 UTF-8 文本。
 */
internal object ConsoleProtocol {

    const val CHANNEL: String = "console"

    /** 包内 helper 路径，与 Gradle 的 `resources("build/generated/console")` 对应。 */
    const val HELPER: String = "native/keel-console-host.exe"

    const val REPLY_OPEN: String = "open"
    const val REPLY_CLOSED: String = "closed"

    fun failed(reason: String): String = "failed:$reason"
}
