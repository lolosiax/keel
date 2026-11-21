// 由 keel-orm-ksp 生成，不要手改。
package moe.lolosia.keel.orm.generated

import moe.lolosia.keel.orm.*

/** 插件 Worker 启动时登记方言与全部 @Table 实体；重复调用是幂等的。 */
object OrmMetaBootstrap {
    fun install() {
        OrmRuntime.install(
            SqliteDialect,
            moe.lolosia.keel.plugins.hello.HellUserMeta
        )
    }
}
