import groovy.json.JsonSlurper
import moe.lolosia.keel.gradle.common.*
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Exec
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

val compileCodecs = tasks.register<Exec>("compileCodecs") {
    group = "build"
    description = "从上游源码编译 zlib / libpng / libwebp / libjpeg-turbo / giflib / msgpack-c"
    workingDir = projectDir
    doFirst {
        if (!Os.isFamily(Os.FAMILY_WINDOWS)) {
            throw GradleException(
                "源码编译目前只提供 Windows 脚本（gradle/build-codecs.ps1）。" +
                    "Linux 可先在有产物的机器上执行 stageAll / publishAll，或补一份 gradle/build-codecs.sh。"
            )
        }
    }
    commandLine("powershell", "-ExecutionPolicy", "Bypass", "-File", "gradle/build-codecs.ps1")
}

val compileAll = tasks.register("compileAll") {
    group = "build"
    description = "从源码编译全部库（当前为 6 个 codec）"
    dependsOn(compileCodecs)
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
            description = "把 $library / $target 的头文件与静态库拆到 dist/"
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
