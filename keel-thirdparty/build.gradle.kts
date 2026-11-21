import groovy.json.JsonSlurper
import moe.lolosia.keel.gradle.common.*
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Sync

// 第三方原生库的生成侧：下载/编译上游源码 → 按 manifest 拆成 dist/<lib>/<target> →
// 由 keel-thirdparty-gradle-plugin 打标准 zip 并发布。
//
// 日常命令：
//   gradlew -p keel-thirdparty compileAll
//   gradlew -p keel-thirdparty stageAll
//   gradlew -p keel-thirdparty publishAll
//   gradlew -p keel-thirdparty rebuildAndPublish

plugins {
    id("moe.lolosia.keel.thirdparty")
    `maven-publish`
}

group = "moe.lolosia.keel.thirdparty"
version = libs.versions.keel.get()

apply(from = "../gradle/publishing.gradle.kts")

keel {
    thirdparty {
        manifest = "manifest.json"
        publishFrom = "build/dist"
    }
}

val manifestRoot = JsonSlurper().parse(file("manifest.json")) as Map<*, *>

@Suppress("UNCHECKED_CAST")
val libraries = manifestRoot["libraries"] as Map<String, Map<String, Any?>>

val groupPath = manifestRoot["groupId"].toString().replace('.', '/')

fun cap(value: String): String = value.replaceFirstChar { it.uppercaseChar() }

fun artifactVersion(spec: Map<String, Any?>): String {
    val version = spec["version"]?.toString().orEmpty()
    val revision = (spec["revision"] as? Number)?.toInt() ?: 1
    return "$version-keel.$revision"
}

// ---------------------------------------------------------------- 源码编译

// 实现在 gradle/codecs.gradle.kts（Kotlin 脚本插件，Windows / Linux 共用一份）：
// 下载 manifest 里的源码包 → 解压 → 用 Kotlin/Native 自带的 clang + 三个 sysroot 交叉编译，
// 产物落在 build/thirdparty/out/<target>/*.a 与 out/include。
apply(from = "gradle/codecs.gradle.kts")
// SQLite amalgamation：源码是 zip、无 CMake，单独一份脚本，产物布局与 codec 一致
apply(from = "gradle/sqlite.gradle.kts")
// MariaDB Connector/C：CMake 工程，构建共享库 libmariadb（当前仅 mingwX64）
apply(from = "gradle/mariadb.gradle.kts")

val compileAll = tasks.register("compileAll") {
    group = "build"
    description = "从源码编译全部库（6 个 codec + sqlite + mariadb，按 manifest 的目标）"
    dependsOn("compileCodecs")
    dependsOn("compileSqliteAll")
    dependsOn("compileMariadbAll")
}

// ---------------------------------------------------------------- 拆分到 dist

// 默认用本构建的编译产物；-Pkeel.thirdparty.out=... 可指向另一台机器/另一份 out/
val outOverride: Provider<File> =
    providers.gradleProperty("keel.thirdparty.out").map { file(it) }
val outRoot: Provider<Directory> = layout.dir(outOverride)
    .orElse(layout.buildDirectory.dir("thirdparty/out"))
val stageTasks = mutableListOf<TaskProvider<Sync>>()

libraries.forEach { (library, spec) ->
    val headers = (spec["headers"] as List<*>).map { it.toString() }
    val libs = spec["libs"] as Map<*, *>

    libs.forEach { (targetKey, archivesNode) ->
        val target = targetKey as String
        val archives = (archivesNode as List<*>).map { it.toString() }
        val stage = tasks.register<Sync>("stage${cap(library)}${cap(target)}") {
            group = "thirdparty"
            description = "把 $library / $target 的头文件与库文件拆到 dist/"
            into(layout.buildDirectory.dir("dist/$library/$target"))
            doFirst {
                val out = outRoot.get().asFile
                if (!out.isDirectory) {
                    throw GradleException(
                        "缺少编译产物：${out.absolutePath}；先执行 compileAll，" +
                            "或把已编译的 out/ 放到该目录。"
                    )
                }
            }
            from(outRoot.map { it.dir("include") }) {
                headers.forEach { header ->
                    if (header.endsWith(".h")) include(header) else include("$header/**")
                }
                into("include")
            }
            from(outRoot.map { it.dir(target) }) {
                archives.forEach { include(it) }
                into("lib")
            }
        }
        stageTasks += stage
    }
}

val stageAll = tasks.register("stageAll") {
    group = "thirdparty"
    description = "把 build/thirdparty/out 拆成每个库 × 目标的 dist 目录"
    dependsOn(stageTasks)
}

// 消费侧（驱动构建）直接依赖 stage 任务时，也应当自动编出 sqlite；
// codec 的 stage 仍只吃已经编译好的 out/，不在这里触发 CMake。
tasks.matching { it.name.startsWith("stageSqlite") }.configureEach {
    dependsOn(tasks.matching { it.name.startsWith("compileSqlite") })
}
tasks.matching { it.name.startsWith("stageMariadb") }.configureEach {
    dependsOn(tasks.matching { it.name.startsWith("compileMariadb") })
}

// 所有 stage 任务都读 out/include 与 out/<target>；MariaDB 也写这两个目录的
// libmariadb 子项，显式声明顺序避免 Gradle 的隐式依赖校验报错。
tasks.matching { it.name.startsWith("stage") }.configureEach {
    mustRunAfter(tasks.matching { it.name.startsWith("compileMariadb") })
}

// keelThirdpartyPublish 在 afterEvaluate 注册，这里按名字补依赖与输入来源
tasks.matching { it.name == "keelThirdpartyPublish" }.configureEach {
    dependsOn(stageAll)
}

// ---------------------------------------------------------------- 发布

publishing {
    publications {
        libraries.forEach { (library, spec) ->
            val version = artifactVersion(spec)
            (spec["libs"] as Map<*, *>).keys.forEach { targetKey ->
                val target = targetKey as String
                val artifactId = "$library-$target"
                create<MavenPublication>("tp${cap(library)}${cap(target)}") {
                    groupId = "moe.lolosia.keel.thirdparty"
                    this.artifactId = artifactId
                    this.version = version
                    artifact(
                        layout.buildDirectory.file(
                            "thirdparty/repo/$groupPath/$artifactId/$version/$artifactId-$version.zip"
                        )
                    ) {
                        extension = "zip"
                    }
                }
            }
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn("keelThirdpartyPublish")
}

tasks.withType<PublishToMavenLocal>().configureEach {
    dependsOn("keelThirdpartyPublish")
}

val publishAll = tasks.register("publishAll") {
    group = "publishing"
    description = "打包并发布全部第三方库到工件仓库"
    dependsOn(stageAll, "publish")
}

tasks.register("rebuildAndPublish") {
    group = "publishing"
    description = "先编译，再拆包并发布"
    dependsOn(compileAll, stageAll, publishAll)
}

tasks.register("listLibraries") {
    group = "help"
    description = "列出 manifest 里的库"
    doLast {
        libraries.keys.sorted().forEach { println(it) }
    }
}
