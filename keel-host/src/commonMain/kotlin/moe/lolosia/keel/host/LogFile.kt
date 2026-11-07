package moe.lolosia.keel.host

/**
 * 打开按日切割的文件日志（`<工作目录>/work/log/keel-YYYY-MM-DD.log`）。
 *
 * @return 实际日志目录；失败返回 null（此时只有控制台输出）
 */
internal expect fun startLogFile(): String?

/** 把一行已格式化的日志追加到当日文件；未初始化时忽略。 */
internal expect fun appendLogFile(line: String)
