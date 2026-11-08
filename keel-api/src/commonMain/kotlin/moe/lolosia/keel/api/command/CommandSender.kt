package moe.lolosia.keel.api.command

/**
 * 命令的发出方（Bukkit `CommandSender` 的裁剪版）。
 *
 * 目前只有控制台一种实现：`sendMessage` 的内容由 Host 原样打印，
 * 同时写进当日日志文件；控制台之外的发送方以后再补。
 */
interface CommandSender {

    /** 发送方名称，控制台固定为 `CONSOLE`。 */
    val name: String

    /** 输出一行；换行由发送方负责拆，不要在一行里塞多行文本。 */
    fun sendMessage(message: String)
}
