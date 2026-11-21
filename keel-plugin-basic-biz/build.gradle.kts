import com.google.devtools.ksp.gradle.KspExtension
import groovy.json.JsonSlurper
import moe.lolosia.keel.gradle.common.*
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("moe.lolosia.keel.plugin")
}

group = "moe.lolosia.keel.plugins"
version = libs.versions.keel.get()

keel {
    plugin {
        // 与 hello 一致：当前开发机先打 mingwX64；Linux 目标在对应平台或 CI 上打
        target("mingwX64")
        // 插件版本与 keel 版本不是一回事，必须显式指定
        keelVersion = libs.versions.keel.get()
        // plugin.json 声明 requires: ["database"]，本地联调时把能力包一起放进运行目录
        runPlugin("moe.lolosia.keel:keel-database-mariadb:${libs.versions.keel.get()}@keel")
        // 联调本仓库的 Host 源码：直接用本地 debug Host（mavenLocal 里的发布版可能落后于 share 协议）
        runHostExecutable = "../keel-host/build/bin/mingwX64/debugExecutable/keel-host.exe"
        runHostDebugDir = "../keel-host/build/keel-debug"
    }
}

// api 工件由 impl 侧 KSP 生成到 api/src/commonMain/kotlin（V1 提交制，产物随仓库提交）
extensions.configure<KspExtension> {
    arg("keel.api.dir", file("api/src/commonMain/kotlin").absolutePath)
}

// api 子工程的编译入口依赖这个任务：三个 target 产物相同，跑 host 目标即可
tasks.register("keelApiGenerate") {
    group = "keel"
    description = "把 @PublicApi 的镜像 / 代理 / Key / 访问器写入 api/src/commonMain/kotlin"
    dependsOn("kspKotlinMingwX64")
}

// V1 提交制：生成器直接改写 api/src/commonMain，产物必须随仓库提交；过期时这里失败
tasks.register("keelApiCheck") {
    group = "keel"
    description = "确认 api 生成物与 @PublicApi 声明一致（V1 提交制）"
    dependsOn("keelApiGenerate")
    doLast {
        val status = providers.exec {
            commandLine("git", "status", "--porcelain", "--", "api/src/commonMain")
        }.standardOutput.asText.get()
        check(status.isBlank()) {
            "api 生成物已过期，请提交 api/src/commonMain 的改动：\n$status"
        }
    }
}

kotlin {
    mingwX64 { binaries.sharedLib { baseName = "keel-plugin-basic-biz" } }
    linuxArm64 { binaries.sharedLib { baseName = "keel-plugin-basic-biz" } }
    linuxX64 { binaries.sharedLib { baseName = "keel-plugin-basic-biz" } }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
            languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
            languageSettings.optIn("kotlin.time.ExperimentalTime")
            languageSettings.optIn("kotlin.io.encoding.ExperimentalEncodingApi")
        }
        commonMain.dependencies {
            api("moe.lolosia.keel:keel-api:${libs.versions.keel.get()}")
            implementation(libs.kotlinx.datetime)
        }
        nativeMain.dependencies {
            // 通过 C ABI 查库：驱动由 database 能力包提供，插件只依赖这份门面
            api("moe.lolosia.keel:keel-database-api:${libs.versions.keel.get()}")
        }
    }
}

// ---------------------------------------------------------------- bcrypt（crypt_blowfish）

/**
 * bcrypt 用 `keel-thirdparty` 的 C 实现（crypt_blowfish，固定 -O2 静态编译），
 * Kotlin 侧只保留随机盐与常量时间比较。
 *
 * 仓库内开发：直接读 `../keel-thirdparty/build/dist`，并把生成侧的 stage 任务接进
 * 同一张任务图（`-Pkeel.thirdparty.dist=<dir>` 可覆盖）；单独 clone 本目录时按
 * Maven 坐标解析已发布的 zip。
 */
val thirdpartyDir = file("../keel-thirdparty")
val bcryptVersion: String = file("$thirdpartyDir/manifest.json").takeIf { it.isFile }?.let { manifest ->
    @Suppress("UNCHECKED_CAST")
    val spec = ((JsonSlurper().parse(manifest) as Map<String, Any?>)["libraries"]
        as Map<String, Map<String, Any?>>).getValue("bcrypt")
    "${spec["version"]}-keel.${(spec["revision"] as Number).toInt()}"
} ?: "1.3-keel.1"

val bcryptDist: File? = (findProperty("keel.thirdparty.dist") as String?)?.let(::file)
    ?: File(thirdpartyDir, "build/dist").takeIf { thirdpartyDir.isDirectory }

val bcryptTargets = listOf("mingwX64", "linuxArm64", "linuxX64")

bcryptTargets.forEach { targetName ->
    val cap = targetName.replaceFirstChar { it.uppercaseChar() }
    val unpackDir = layout.buildDirectory.dir("thirdparty/bcrypt/$targetName").get().asFile
    val libDir = File(unpackDir, "lib")

    val unpack = tasks.register("keelBcryptUnpack$cap") {
        group = "keel"
        description = "解出 crypt_blowfish 的头文件与 libbcrypt.a（$targetName）"
        bcryptDist?.let { inputs.dir(File(it, "bcrypt/$targetName")) }
        outputs.dir(unpackDir)
        if (gradle.includedBuilds.any { it.name == "keel-thirdparty" }) {
            dependsOn(gradle.includedBuild("keel-thirdparty").task(":stageBcrypt$cap"))
        }
        doLast {
            unpackDir.deleteRecursively()
            unpackDir.mkdirs()
            val dist = bcryptDist
            if (dist != null) {
                val source = File(dist, "bcrypt/$targetName")
                check(source.isDirectory) {
                    "keel.thirdparty.dist 里没有 bcrypt/$targetName：${source.absolutePath}"
                }
                copy { from(File(source, "include")); into(File(unpackDir, "include")) }
                copy { from(File(source, "lib")); into(libDir) }
            } else {
                // 发布 zip 的布局是 lib/<target>/libbcrypt.a，规整成 include/ + lib/
                val configuration = configurations.create("keelBcrypt$cap") {
                    isCanBeConsumed = false
                    isCanBeResolved = true
                    isTransitive = false
                }
                dependencies.add(
                    configuration.name,
                    "moe.lolosia.keel.thirdparty:bcrypt-$targetName:$bcryptVersion@zip",
                )
                copy { from(zipTree(configuration.singleFile)); into(unpackDir) }
                val nested = File(unpackDir, "lib/$targetName")
                if (nested.isDirectory) {
                    nested.listFiles().orEmpty().forEach { it.renameTo(File(libDir, it.name)) }
                    nested.deleteRecursively()
                }
            }
        }
    }

    val target = kotlin.targets.getByName(targetName) as KotlinNativeTarget
    val cinterop = target.compilations.getByName("main").cinterops.create("bcrypt")
    cinterop.definitionFile.set(file("src/nativeInterop/cinterop/bcrypt.def"))
    cinterop.includeDirs(File(unpackDir, "include"))
    // def 里用 staticLibraries = libbcrypt.a，cinterop 也要能找到库目录
    cinterop.extraOpts("-libraryPath", libDir.absolutePath)

    // cinterop 产出 klib 后链接任务才拿得到库目录；cinterop 依赖 unpack 即保证顺序
    tasks.matching { it.name == "cinteropBcrypt$cap" }.configureEach { dependsOn(unpack) }
    target.binaries.all { binary ->
        binary.linkerOpts("-L${libDir.absolutePath}")
        // `all` 在 Kotlin DSL 里按 Spec 解析，lambda 必须返回 Boolean
        true
    }
}
