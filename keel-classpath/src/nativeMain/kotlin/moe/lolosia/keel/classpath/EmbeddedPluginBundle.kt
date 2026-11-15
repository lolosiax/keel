package moe.lolosia.keel.classpath

/** 是否存在内嵌的插件 bundle；只检查资源 / footer，不读取整包。 */
expect fun hasEmbeddedPluginBundle(executablePath: String): Boolean

/**
 * 单 exe 构建时写入的默认目录模式是否为 USERDATA。
 *
 * Windows 读 PE 资源 `IDR_KEEL_LAYOUT` / `KEELAYOUT`，Linux 读文件尾 footer 的高 16 位；
 * 没有标记（旧产物）时返回 false，按 `PROGRAM` 模式处理。
 */
expect fun embeddedUserDataDefault(executablePath: String): Boolean

/**
 * 单 exe 构建时写入的应用 id，用作 `%LOCALAPPDATA%\<appId>` 的目录名。
 *
 * 与产物文件名解耦：改名 / 多语言产物不会带着数据目录跑。没有标记时返回 null，
 * 由调用方退回可执行文件名。
 */
expect fun embeddedAppId(executablePath: String): String?

/** 读取可执行文件内嵌的 `keel-bundle.zip`；没有时返回 null。 */
internal expect fun readEmbeddedPluginBundle(executablePath: String): ByteArray?

/**
 * 读取内嵌 bundle 的原始字节，不做容器解析。
 *
 * bundle 可能是 zip，也可能是 `KELB`（zstd）容器；格式判断在 Host 侧完成，
 * 这里只负责把 PE 资源 / 文件尾 trailer 的载荷取出来。
 */
fun readEmbeddedPluginBundleBytes(executablePath: String): ByteArray? =
    readEmbeddedPluginBundle(executablePath)

/**
 * 打开内嵌的插件 bundle。
 *
 * Windows 从 PE 资源 `IDR_KEEL_BUNDLE` 读取；Linux 从自身文件尾部的 trailer 读取。
 * 返回的 [Classpath] 位于内存中，不落盘。
 */
fun openEmbeddedPluginBundle(executablePath: String): Classpath? =
    readEmbeddedPluginBundle(executablePath)?.let { ZipClasspath.open(it) }
