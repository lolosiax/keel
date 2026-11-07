plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

group = "moe.lolosia.keel"
version = libs.versions.keel.get()

val abiDir = file("src/nativeInterop/cinterop")
val abiDef = abiDir.resolve("keelabi.def")

kotlin {
    mingwX64 {
        compilations.getByName("main").cinterops.create("keelabi") {
            defFile = abiDef
            includeDirs(abiDir)
        }
    }
    linuxArm64 {
        compilations.getByName("main").cinterops.create("keelabi") {
            defFile = abiDef
            includeDirs(abiDir)
        }
    }
    linuxX64 {
        compilations.getByName("main").cinterops.create("keelabi") {
            defFile = abiDef
            includeDirs(abiDir)
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
