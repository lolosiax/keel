// 根构建只做聚合：真正的任务都在各独立构建里（见 settings.gradle.kts）。
// 依赖方向：keel-api ← keel-host ← keel-launcher，keel-api ← keel-plugin-*，
// keel-*-gradle-plugin / keel-router-ksp 是构建期工具，独立于运行时。

tasks.register("buildAll") {
    group = "build"
    description = "构建仓库内所有独立 Gradle 构建"
    dependsOn(gradle.includedBuilds.map { it.task(":build") })
}
