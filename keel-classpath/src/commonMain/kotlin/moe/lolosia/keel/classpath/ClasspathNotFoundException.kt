package moe.lolosia.keel.classpath

/**
 * 命名空间不存在或已卸载。
 *
 * 语义等同 FileNotFound：调用方不应再使用旧引用；
 * 消息里只带插件 id 和请求路径，不带真实文件路径。
 */
class ClasspathNotFoundException(
    val pluginId: String,
    val path: String? = null,
) : NoSuchElementException(
    if (path.isNullOrEmpty()) {
        "classpath 已卸载：$pluginId"
    } else {
        "classpath 已卸载：$pluginId/$path"
    }
)
