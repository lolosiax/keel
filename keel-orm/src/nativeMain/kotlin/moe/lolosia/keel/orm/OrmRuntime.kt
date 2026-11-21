package moe.lolosia.keel.orm

import moe.lolosia.keel.database.currentDialect

/**
 * ORM 运行时的进程内配置。
 *
 * 生成的 `moe.lolosia.keel.orm.generated.OrmMetaBootstrap.install()` 在 Worker 启动时
 * 登记实体与**构建期兜底方言**；真正使用哪个方言优先看 Host 下发的能力包
 * `dialect`（ABI v2），这样同一个插件可以跑 SQLite 或 MariaDB。
 */
object OrmRuntime {

    var dialect: SqlDialect? = null
        private set

    private var installed = false

    /** 登记兜底方言与全部实体；重复调用是幂等的。 */
    fun install(dialect: SqlDialect, vararg metas: EntityMeta<*>) {
        if (installed) return
        this.dialect = dialect
        metas.forEach(EntityMetas::register)
        installed = true
    }

    /**
     * 当前方言：能力包声明的优先，没有能力包时回落到构建期方言。
     *
     * @throws IllegalStateException 能力包声明的方言未知，或 ORM 根本没有安装
     */
    fun dialectOrThrow(): SqlDialect {
        val runtime = currentDialect()
        if (runtime != null) {
            return Dialects.of(runtime)
                ?: error("未知数据库方言：$runtime（已支持：${Dialects.all.joinToString { it.id }}）")
        }
        return dialect ?: error(
            "ORM 运行时未安装：请应用 moe.lolosia.keel.database 插件，并在 nativeMain 定义 @Table 实体"
        )
    }
}
