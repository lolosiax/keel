package moe.lolosia.keel.gradle.database

/**
 * `keel { database { ... } }` 的配置。
 *
 * 只做接线：把 `keel-orm-ksp` 挂到每个 native 目标的 `ksp<target>` 配置，
 * 给 `nativeMain` 加 `keel-orm` / `keel-database-api`，并把方言作为 KSP 参数传下去。
 * 驱动本身（SQLite / MariaDB 的 `.keel`）是运行期工件，不在这里编译。
 */
open class KeelDatabaseExtension {

    /** 关闭后不挂处理器、不加依赖；默认开启。 */
    var enabled: Boolean = true

    /** 构建期方言 id：`sqlite` / `mariadb`（同时用于生成的 SQL 渲染）。 */
    var dialect: String = "sqlite"

    /**
     * ORM 生成物落点；相对工程目录。
     *
     * 多 native 目标下 `nativeMain` 会参与 metadata 编译，而 KSP 只把生成物放进
     * 各目标的编译输出；所以默认写进共享源码目录（V1 提交制），生成物需要提交。
     */
    var outputDir: String = "src/nativeMain/kotlin"

    /** 自动添加的工件版本；默认取 project.version。 */
    var keelVersion: String? = null
}
