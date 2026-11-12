package moe.lolosia.keel.host.plugin

/** 递归创建目录；已存在视为成功。 */
internal expect fun mkdirs(path: String)

/** 列出目录下的条目名（不含 `.` / `..`）；目录不存在或不可读返回 null。 */
internal expect fun listNames(dir: String): List<String>?

/** 删除空目录；失败（不存在、被占用、非空）返回 false。 */
internal expect fun removeDirectory(path: String): Boolean

/** 把内部使用的 `/` 分隔路径转成当前平台的本地分隔符。 */
internal expect fun nativePath(path: String): String

/** 用户级可写缓存目录；给只读安装目录兜底，取不到时返回 null。 */
internal expect fun userCacheRoot(): String?
