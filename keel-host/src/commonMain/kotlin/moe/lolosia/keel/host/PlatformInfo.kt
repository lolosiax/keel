package moe.lolosia.keel.host

/** 本地时间，格式 `HH:mm:ss`。 */
internal expect fun formatLocalTimestamp(): String

/** 当前工作目录。 */
internal expect fun currentWorkingDirectory(): String

/** 当前可执行文件的绝对路径。 */
internal expect fun executablePath(): String
