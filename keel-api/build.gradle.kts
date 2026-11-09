plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

apply(from = "../gradle/publishing.gradle.kts")

// ABI 头文件归 keel-abi 所有（契约与实现同属一个构建）；def 仍留在本构建。
// 当前直接读兄弟目录的头文件；等 keel-abi 发布 include 产物后改为构建期解压。
val abiIncludeDir = file("../keel-abi/src/nativeCpp")
val abiDef = file("src/nativeInterop/cinterop/keelabi.def")

kotlin {
    mingwX64 {
        compilations.getByName("main").cinterops.create("keelabi") {
            defFile = abiDef
            includeDirs(abiIncludeDir)
        }
    }
    linuxArm64 {
        compilations.getByName("main").cinterops.create("keelabi") {
            defFile = abiDef
            includeDirs(abiIncludeDir)
        }
    }
    linuxX64 {
        compilations.getByName("main").cinterops.create("keelabi") {
            defFile = abiDef
            includeDirs(abiIncludeDir)
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.core)
            // 生成的服务客户端用 CBOR 编解码参数 / 返回值
            api(libs.kotlinx.serialization.cbor)
            // 控制器返回值 / 请求体按 JSON 编解码
            api(libs.kotlinx.serialization.json)
            // ctx.classpath / server.classpath 的类型来自独立 classpath 库
            api("moe.lolosia.keel:keel-classpath:${libs.versions.keel.get()}")
        }
    }
}
