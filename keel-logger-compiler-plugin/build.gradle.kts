import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

group = "moe.lolosia.keel.toolchain"
version = libs.versions.keel.get()

kotlin {
    jvmToolchain(21)
}

dependencies {
    // 编译器在加载插件 jar 时提供这些类型；不能打进插件，否则与 K/N 编译器冲突
    compileOnly(libs.kotlin.compiler.embeddable)
}

apply(from = "../gradle/publishing.gradle.kts")

// 编译器插件由 moe.lolosia.keel.logger 按 Maven 坐标解析，必须单独发布 jar
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
