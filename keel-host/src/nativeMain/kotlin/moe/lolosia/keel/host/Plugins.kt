package moe.lolosia.keel.host

import kotlinx.cinterop.toKString
import moe.lolosia.keel.abi.c.KEEL_SUBJECT_PING
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.debug.Debug
import moe.lolosia.keel.classpath.EmptyClasspath
import moe.lolosia.keel.host.build.BuildInfo
import moe.lolosia.keel.host.classpath.HostClasspath
import moe.lolosia.keel.host.config.HostConfigs
import moe.lolosia.keel.host.plugin.HostPlugins
import moe.lolosia.keel.host.plugin.HostPump
import moe.lolosia.keel.host.plugin.PluginBundle
import moe.lolosia.keel.host.plugin.mkdirs
import platform.posix.getenv

/**
 * `--plugins <dir> [cacheDir] [--http=<port>] [--serve | --serve-ms=<n>]`：
 * 扫描目录、校验 `.keel`、解出动态库并启动插件。
 *
 * 插件清单与启停状态由 [HostPlugins] 持有，控制台命令与应用生命周期共用同一份；
 * 这里只负责启动阶段的日志、体检与把运行期交给 `HostRuntime`。
 */
internal actual fun loadPluginsFromCli(
    pluginsDir: String,
    cacheDir: String?,
    serveMillis: Long?,
    httpPort: Int?,
) {
    startHost(
        pluginsDir = pluginsDir,
        dirs = resolveHostDirs(pluginsDir, cacheDir),
        bundle = null,
        serveMillis = serveMillis,
        httpPort = httpPort,
    )
}

/**
 * 当前可执行文件是否内嵌了插件 bundle。
 */
internal actual fun hasEmbeddedPluginBundle(): Boolean =
    moe.lolosia.keel.classpath.hasEmbeddedPluginBundle(executablePath())

/**
 * 加载可执行文件内嵌的 bundle 并交给运行期。
 *
 * `dataRoot` 默认是可执行文件所在目录（`KEEL_DATA_HOME` / `KEEL_HOME` 可重定向），
 * 不可写时回退到用户目录；外部 `plugins/` 始终参与扫描，可覆盖同 id 的内嵌包。
 */
internal actual fun runEmbeddedPlugins(serveMillis: Long?, httpPort: Int?) {
    val executable = executablePath()
    val bundle = PluginBundle.openEmbedded(executable)
    if (bundle == null) {
        Log.warn("可执行文件里没有可用的内嵌 bundle：$executable", tag = "Loader")
        return
    }
    val dirs = resolveHostDirs(pluginsDir = null, cacheDir = null)
    val pluginsDir = "${dirs.dataRoot}/plugins"
    // 外部覆盖目录可能还不存在：先建出来，免得扫描出一条「目录不存在」的假失败
    mkdirs(pluginsDir)
    startHost(
        pluginsDir = pluginsDir,
        dirs = dirs,
        bundle = bundle,
        serveMillis = serveMillis,
        httpPort = httpPort,
    )
}

/**
 * 启动 Host：初始化注册表、加载插件、体检，然后把事件循环交给 `HostRuntime`。
 *
 * @param bundle 内嵌 bundle；为 null 时只扫描磁盘插件目录
 * @param serveMillis null 表示不常驻（`--once`）；0 表示一直服务；其它值表示服务时长
 */
private fun startHost(
    pluginsDir: String,
    dirs: HostDirs,
    bundle: PluginBundle?,
    serveMillis: Long?,
    httpPort: Int?,
) {
    val dir = pluginsDir.trimEnd('/', '\\')

    // 调试会话：IDE 通过 KEEL_DEBUG_PORT 告诉我们要连的端口。
    // 只做 bind + listen，accept 放在事件循环里非阻塞地做。
    attachDebugAgent()

    // 尽早接管 Ctrl+C，启动阶段的中断也走优雅停机
    HostBootstrap.installShutdownHook()

    val host = HostClasspath(EmptyClasspath)
    HostPlugins.init(host, dirs.dataRoot, dirs.cacheRoot, dir)
    val result = HostPlugins.loadAll(bundle)

    Log.info("data: ${dirs.dataRoot}", tag = "Loader")
    Log.info("cache: ${dirs.cacheRoot}", tag = "Loader")
    Log.info("plugins: $dir", tag = "Loader")
    Log.info("loaded ${result.loaded.size} plugin(s)", tag = "Loader")

    // 最小请求 / 应答回路：确认每个插件都能收到事件并按 correlation 回包
    HostPlugins.enabled().forEach { entry ->
        val ok = HostPump.requestPlugin(entry.pluginId, KEEL_SUBJECT_PING.toULong())
        if (ok) {
            Log.info("ping ${entry.id} ok", tag = "Loader")
        } else {
            Log.warn("ping ${entry.id} 未响应", tag = "Loader")
        }
    }

    HostPump.drain()

    val failureCount = result.failed.size + HostPlugins.startFailures
    if (failureCount > 0) {
        Log.warn("failed ($failureCount):", tag = "Loader")
        result.failed.forEach { failure ->
            val id = failure.pluginId?.let { "$it " }.orEmpty()
            Log.warn("  $id${failure.keelPath}: ${failure.reason}", tag = "Loader")
        }
    } else {
        Log.info("failed: 0", tag = "Loader")
    }

    // 运行期交给 HostRuntime：HTTP 入口、控制台、事件循环、停机
    HostRuntime.run(serveMillis, resolveHttpPort(httpPort, bundle != null))
}

/**
 * HTTP 端口优先级：`--http` > `KEEL_HTTP_PORT` > `application.yaml` 的 `server.port` > 默认 7191。
 *
 * `--plugins` 模式不带任何端口来源时不启动 HTTP；内嵌 bundle 模式默认监听 7191。
 */
private fun resolveHttpPort(explicit: Int?, bundled: Boolean): Int? {
    explicit?.let { return it }
    getenv("KEEL_HTTP_PORT")?.toKString()?.trim()?.toIntOrNull()?.let { return it }
    HostConfigs.serverPort()?.let { return it }
    return if (bundled) DEFAULT_HTTP_PORT else null
}

/**
 * `KEEL_DEBUG_PORT` 存在时把本 Host 接上 IDE 的调试通道。
 *
 * 模块 id 取 Gradle 的工程名（位置表首行也是它），buildId 取启动横幅里的同一份。
 */
private fun attachDebugAgent() {
    val port = Debug.requestedPort() ?: return
    Debug.attach(port, "keel-host", BuildInfo.BUILD_ID)
}

/** 内嵌 bundle 模式默认端口。 */
private const val DEFAULT_HTTP_PORT = 7191
