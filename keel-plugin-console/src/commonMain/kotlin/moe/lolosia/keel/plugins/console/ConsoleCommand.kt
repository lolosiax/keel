package moe.lolosia.keel.plugins.console

/**
 * 控制命令。
 *
 * 协议与 `Plugin.onCommand` 共用同一套解析：`title` 取后面的整段文本，
 * 其余命令不接受参数；无法识别时返回 null，由调用方回 `failed:unknown-command`。
 */
internal sealed class ConsoleCommand {

    data object Open : ConsoleCommand()

    data object Close : ConsoleCommand()

    data object Reopen : ConsoleCommand()

    data object Status : ConsoleCommand()

    data object Clear : ConsoleCommand()

    class Title(val text: String) : ConsoleCommand()

    companion object {

        fun parse(raw: String): ConsoleCommand? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val head = text.substringBefore(' ').lowercase()
            val rest = text.substringAfter(' ', "").trim()
            return when (head) {
                "open" -> Open
                "close" -> Close
                "reopen" -> Reopen
                "status" -> Status
                "clear" -> Clear
                "title" -> Title(rest)
                else -> null
            }
        }
    }
}
