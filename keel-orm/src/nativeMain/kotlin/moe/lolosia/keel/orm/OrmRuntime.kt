package moe.lolosia.keel.orm

/**
 * ORM 运行时的进程内配置。
 *
 * 方言由构建期决定（`keel { database { dialect } }`），生成的
 * `moe.lolosia.keel.orm.generated.OrmMetaBootstrap.install()` 在 Worker 启动时调用 [install]。
 * 不安装 ORM 的插件不会走到这里。
 */
object OrmRuntime {

    var dialect: SqlDialect? = null
        private set

    private var installed = false

    /** 登记方言与全部实体；重复调用是幂等的。 */
    fun install(dialect: SqlDialect, vararg metas: EntityMeta<*>) {
        if (installed) return
        this.dialect = dialect
        metas.forEach(EntityMetas::register)
        installed = true
    }

    fun dialectOrThrow(): SqlDialect = dialect ?: error(
        "ORM 运行时未安装：请应用 moe.lolosia.keel.database 插件，并在 nativeMain 定义 @Table 实体"
    )
}
