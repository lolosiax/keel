package moe.lolosia.keel.api

import kotlinx.coroutines.*
import moe.lolosia.keel.api.callback.*
import moe.lolosia.keel.api.config.*
import moe.lolosia.keel.api.host.*
import moe.lolosia.keel.api.share.*
import moe.lolosia.keel.api.web.*
import moe.lolosia.keel.classpath.*

/**
 * 宿主注入给插件的运行时上下文。
 *
 * 这里的对象都活在**插件自己的运行时**里，由插件的 Worker 线程构造；
 * 不要把它（或其字段）传出当前运行时，传给别的插件一律走事件 / 消息。
 */
interface PluginContext {

    /**
     * 本插件的元信息，来自 `plugin.json`，由宿主读取后注入。
     */
    val metadata: PluginMetadata

    /**
     * 插件 → Host 的通道：单向通知与带 correlation 的反向请求。
     *
     * 反向请求只能在 Worker 线程调用；等待期间会继续分发其它事件。
     */
    val host: HostLink

    val server: KeelServer

    /**
     * 本地回调表；生成的客户端用它把函数类型参数换成数字 id。
     */
    val callbacks: CallbackRegistry

    /**
     * `@PublicApi` 共享对象的入口：登记本地实例、查询单例、包装远端句柄。
     *
     * 只能在 Worker 线程调用；跨插件调用经 Host 路由，不跨运行时传递 Kotlin 对象。
     */
    val publicApi: PublicApiDirectory

    /**
     * 本插件的配置（`config/plugins/<id>.yaml`）；读取走本地快照，写入经 Host 落盘。
     */
    val config: Config

    /**
     * 本插件的资源视图：自己的 `.keel` 优先，缺失时回落 Host。
     *
     * 跨插件访问用 `ctx.classpath.of("other")`，它只返回不透明引用，不暴露
     * 对方的 `.keel` 路径；插件卸载后旧引用读取会立即失效。
     *
     * 读取经事件泵反向请求 Host，**只能在 Worker 线程调用**。
     */
    val classpath: Classpath

    /**
     * 本插件独占的可写缓存目录，绝对路径。
     *
     * 每次加载 / 启用都分配新目录（`<cacheRoot>/<id>/<version>/<loadSeq>/`），
     * 目录由 Host 创建；日志与临时文件可以直接写在这里。旧目录不会在运行期删除，
     * 由 Host 下次启动时尽力回收，因此不要依赖跨加载复用。
     */
    val cacheDir: String

    /**
     * 本插件独占的持久化数据目录，绝对路径。
     *
     * 位置是 `<dataRoot>/data/<id>/`：跨重启、跨版本保持同一目录，用于数据库文件、
     * 上传内容、需要长期保留的索引等。目录由 Host 创建；Host 的 `dataRoot` 默认落在
     * 程序目录（Web 部署的常规做法），也可以用 `KEEL_DATA_HOME` 重定向到用户目录。
     *
     * 与 [cacheDir] 的区别只在于生命周期：这里的内容不会被 Host 回收，
     * 需要清理时由插件自己删除。
     */
    val dataDir: String

    /**
     * 把本插件 `.keel` 里的资源物化到 [cacheDir]，返回绝对路径。
     *
     * - [path] 是包内相对路径（如 `native/x.dll`、`web`），不接受 `..`、前导分隔符或盘符；
     * - 文件：写出该文件并返回文件路径；
     * - 目录 / 前缀：写出该前缀下的全部条目、保留相对路径，返回目录路径；
     * - 幂等：同一路径重复调用返回同一位置，已存在且大小相同的文件不重写；
     * - 条目不存在时抛 [IllegalArgumentException]。
     *
     * 用于 `LoadLibrary` / WebView 这类需要真实文件的场景；小文本资源优先用
     * [classpath]。实现是反向请求，**只能在 Worker 线程调用**；拿到的路径可以
     * 交给其它线程使用，但下次加载后旧路径会指向旧目录。
     */
    fun materialize(path: String): String

    /**
     * 本插件的 HTTP 路由表：注册后由 Worker 在启用时统一发给 Host。
     */
    val routes: Routes

    /** 兼容日志入口；新代码用 [Log]，才能拿到调用点来源。 */
    val logger: PluginLogger

    val scheduler: Scheduler

    /**
     * 绑定在插件 Worker 调度器上的作用域：协程只在本线程执行，
     * 不要在别的线程恢复，也不要跨运行时传递 continuation。
     */
    val scope: CoroutineScope
}
