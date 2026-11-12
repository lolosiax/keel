package moe.lolosia.keel.api.log

/**
 * 日志视图。
 *
 * [PLAIN] / [ANSI] 是 Host 渲染好的文本，插件拿到的就是控制台上那一行；
 * [RAW] 不做任何渲染，返回 [LogRecord] 本身。
 */
enum class LogFormat {
    PLAIN,
    ANSI,
    RAW,
}
