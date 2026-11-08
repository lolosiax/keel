package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.database.currentDatabase

/**
 * 通过 C ABI 查询 `select 1`，验证「Host 下发函数表 → 插件同步调用」这条链路。
 *
 * 没装 database 能力包时只打印一行提示，不影响插件启用。
 */
internal actual fun checkDatabase() {
    val database = currentDatabase()
    if (database == null) {
        Log.info("database: 未安装 database 能力包")
        return
    }
    val result = database.query("select 1")
    try {
        if (result.next()) {
            Log.info("database: select 1 -> ${result.row?.long(0)}")
        } else {
            Log.warn("database: select 1 没有返回行")
        }
    } catch (e: Exception) {
        Log.warn("database: select 1 失败：${e.message}")
    } finally {
        result.close()
    }
}
