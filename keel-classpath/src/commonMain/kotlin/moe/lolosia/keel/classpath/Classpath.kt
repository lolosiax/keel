package moe.lolosia.keel.classpath

/**
 * 只读资源命名空间。
 *
 * 这不是 JVM 的类加载器：Kotlin/Native 的代码在链接期就固定了，
 * 这里只负责模板、静态文件、默认配置等**资源**的读取。
 *
 * 路径约定：
 * - 以 classpath 根为基准，如 `static/home/index.html`；
 * - 允许前导 `/`，允许 `\` 分隔；
 * - 不支持 `..` 上跳。
 */
interface Classpath {

    /** 全部条目路径（`/` 分隔、无前导斜杠），已排序。 */
    val entries: List<String>

    /** 读取条目；不存在返回 `null`。 */
    fun read(path: String): ByteArray?

    /**
     * 打开流式读取；不存在返回 `null`。
     *
     * 默认实现把 [read] 的字节包一层；文件型实现可以覆盖成真正的分段读取。
     */
    fun open(path: String): ClasspathResource? {
        val bytes = read(path) ?: return null
        return ByteArrayClasspathResource(path, bytes)
    }

    /** 列表查询：返回以 [prefix] 开头的条目路径。 */
    fun list(prefix: String = ""): List<String>

    /** 是否存在该条目。 */
    fun exists(path: String): Boolean = read(path) != null

    /**
     * 条目的最后修改时间（epoch 毫秒）；实现给不出时返回 `null`。
     *
     * 内嵌条目没有各自的时间戳，通常用「安装/加载时间」兜底；
     * 磁盘与 `.keel` 文件可以使用真实 mtime。
     */
    fun lastModified(path: String): Long? = null

    /** 来源标识：`file` / `classpath` / `keel` / `empty`。 */
    val scheme: String

    /** 供日志展示的来源描述；跨模块代理实现不得暴露真实路径。 */
    val origin: String get() = "$scheme://"

    /**
     * 访问另一个插件命名空间。
     *
     * 只有 `keel-classpath-full` 提供的组合实现支持；普通本地实现会抛
     * [ClasspathNotFoundException]，避免误以为可以跨模块读取。
     */
    fun of(pluginId: String): Classpath =
        throw ClasspathNotFoundException(pluginId)
}
