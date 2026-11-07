package moe.lolosia.keel.host.plugin

/** 递归创建目录；已存在视为成功。 */
internal expect fun mkdirs(path: String)

/** 列出目录下的条目名（不含 `.` / `..`）；目录不存在或不可读返回 null。 */
internal expect fun listNames(dir: String): List<String>?
