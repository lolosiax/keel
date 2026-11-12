package moe.lolosia.keel.host

/** 本地时间，格式 `HH:mm:ss`。 */
internal expect fun formatLocalTimestamp(): String

/** 当前墙钟（epoch 毫秒）；日志排序与 `time` 的日期归属都用它。 */
internal expect fun currentTimeMillis(): Long

/** 当前工作目录。 */
internal expect fun currentWorkingDirectory(): String

/** 当前可执行文件的绝对路径。 */
internal expect fun executablePath(): String
