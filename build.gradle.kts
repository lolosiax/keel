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
    "keel-database-api",
    "keel-database-mariadb",
    "keel-plugin-rbac-api",
    "keel-plugin-basic-biz-api",
)

tasks.register("publishAll") {
    group = "publishing"
    description = "把所有可发布工件发布到 keel.repo（在 keel/local.properties 配置）"
    dependsOn(publishableBuilds.map { gradle.includedBuild(it).task(":publish") })
}

tasks.register("publishAllToMavenLocal") {
    group = "publishing"
    description = "把所有可发布工件发布到本机 ~/.m2（独立仓库用 mavenLocal() 消费）"
    dependsOn(publishableBuilds.map { gradle.includedBuild(it).task(":publishToMavenLocal") })
}
