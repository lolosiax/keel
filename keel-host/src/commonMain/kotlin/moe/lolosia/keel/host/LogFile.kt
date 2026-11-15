package moe.lolosia.keel.host

/**
 * 打开按日切割的文件日志（`<dataRoot>/logs/keel-YYYY-MM-DD.log`）。
 *
 * @param dataRoot Host 的持久化根目录；不可写时返回 null（此时只有控制台输出）
 * @return 实际日志目录
 */
internal expect fun startLogFile(dataRoot: String): String?

/** 把一行已格式化的日志追加到当日文件；未初始化时忽略。 */
internal expect fun appendLogFile(line: String)
