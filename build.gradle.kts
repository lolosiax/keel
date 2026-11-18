// 根构建只做聚合：真正的任务都在各独立构建里（见 settings.gradle.kts）。
// 依赖方向：keel-api ← keel-host ← keel-launcher，keel-api ← keel-plugin-*，
// keel-*-gradle-plugin / keel-router-ksp 是构建期工具，独立于运行时。

tasks.register("buildAll") {
    group = "build"
    description = "构建仓库内所有独立 Gradle 构建（IDEA 插件除外）"
    dependsOn(
        gradle.includedBuilds
            .filter { it.name != "keel-idea-plugin" }
            .map { it.task(":build") }
    )
}

/**
 * 有 `maven-publish`、需要发布给外部插件仓库消费的构建。
 *
 * 发布顺序由 Gradle 的坐标依赖决定，这里不手写顺序；
 * `keel-abi` 发布的是 zip（静态库 + 头文件），其余是 klib / jar / Gradle 插件 marker。
 */
val publishableBuilds = listOf(
    "keel-classpath-gradle-plugin",
    "keel-abi-gradle-plugin",
    "keel-logger-gradle-plugin",
    "keel-logger-compiler-plugin",
    "keel-plugin-gradle-plugin",
    "keel-publish-gradle-plugin",
    "keel-router-ksp",
    "keel-plugin-ksp",
    "keel-classpath",
    "keel-abi",
    "keel-classpath-full",
    "keel-api",
    "keel-host",
    "keel-launcher",
    "keel-database-api",
    "keel-database-mariadb",
)

/**
 * 插件构建里单独发布 api 工件的子工程（构建名 → 子工程路径）。
 *
 * 这些子工程所在构建的根工程没有 `maven-publish`，不能按构建名整体发布；
 * 但外部业务插件靠这些坐标消费契约，必须随发布一起推。
 */
val publishableSubprojects = listOf(
    "keel-plugin-basic-biz" to ":keel-plugin-basic-biz-api",
    "keel-plugin-basic-rbac" to ":keel-plugin-rbac-api",
)

private fun publishTasks(action: String): List<Any> =
    publishableBuilds.map { gradle.includedBuild(it).task(":$action") } +
        publishableSubprojects.map { (build, project) ->
            gradle.includedBuild(build).task("$project:$action")
        }

tasks.register("publishAll") {
    group = "publishing"
    description = "把所有可发布工件发布到 keel.repo（在 keel/local.properties 配置）"
    dependsOn(publishTasks("publish"))
}

tasks.register("publishAllToMavenLocal") {
    group = "publishing"
    description = "把所有可发布工件发布到本机 ~/.m2（独立仓库用 mavenLocal() 消费）"
    dependsOn(publishTasks("publishToMavenLocal"))
}
