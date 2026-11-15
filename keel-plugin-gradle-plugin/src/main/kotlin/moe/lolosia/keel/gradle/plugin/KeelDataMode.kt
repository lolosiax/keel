package moe.lolosia.keel.gradle.plugin

/**
 * 单 exe 启动时的默认目录模式。
 *
 * 只影响单 exe 的内嵌运行期默认值；运行期仍可用 `KEEL_DATA_HOME` / `KEEL_CACHE_HOME` 覆盖。
 */
enum class KeelDataMode {

    /** 数据写在可执行文件所在目录，适合 Web 式部署。 */
    PROGRAM,

    /** 数据写在按 app id 隔离的用户目录，适合桌面应用（Program Files 不可写）。 */
    USERDATA,
}
