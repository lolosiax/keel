package moe.lolosia.keel.plugins.hello

/** 数据库能力自检：装了 database 能力包时执行一次 `select 1`。 */
internal expect fun checkDatabase()
