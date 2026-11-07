package moe.lolosia.keel.host

/** 当前 stdout 是否支持 ANSI 颜色；重定向或 `NO_COLOR` 时为 false。 */
internal expect fun ansiSupported(): Boolean
