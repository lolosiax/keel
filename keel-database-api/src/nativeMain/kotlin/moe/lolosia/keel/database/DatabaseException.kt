package moe.lolosia.keel.database

/**
 * 数据库调用失败。
 *
 * @property status `KeelStatus` 的整数值；-1 表示宿主侧校验失败（如未安装能力包）
 */
class DatabaseException(
    message: String,
    val status: Int = -1,
) : RuntimeException(message)
