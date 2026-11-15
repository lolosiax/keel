package moe.lolosia.keel.host

/**
 * Host 的两类根目录。
 *
 * - [dataRoot] 跨重启稳定：`application.yaml`、`config/plugins/`、插件持久化数据
 *   （`data/<id>/`）与日志（`logs/`）都在其下；
 * - [cacheRoot] 随时可以整体删除：物化出来的动态库与每次加载独占的资源目录。
 *
 * 默认都落在程序目录（Web 部署的常规做法：`--plugins` 模式取启动工作目录，
 * 单文件模式取可执行文件所在目录），用 `KEEL_DATA_HOME` / `KEEL_CACHE_HOME`
 * 可以分别重定向；程序目录不可写时回退到用户目录 `<userData>/<app-id>` 与
 * `<userCache>/<app-id>`。
 */
internal class HostDirs(
    val dataRoot: String,
    val cacheRoot: String,
)
