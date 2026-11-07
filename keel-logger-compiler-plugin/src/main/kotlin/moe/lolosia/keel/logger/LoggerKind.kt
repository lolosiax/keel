package moe.lolosia.keel.logger

/** 与 `moe.lolosia.keel.api.log.LogSiteKind` 一一对应的名称。 */
internal enum class LoggerKind {
    TOP_LEVEL_FUNCTION,
    TOP_LEVEL_ANONYMOUS_FUNCTION,
    CLASS_FUNCTION,
    CLASS_MEMBER_STATEMENT,
    NAMED_FUNCTION,
    ANONYMOUS_FUNCTION,
}
