package moe.lolosia.keel.gradle.classpath

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.bundling.ZipEntryCompression
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import java.io.File

/**
 * 普通 Kotlin/Native 产物的 `classpath.zip` 嵌入流水线。
 *
 * - 资源合并顺序沿用 backend：先入者优先；
 * - Windows：windres 编译 PE 资源；
 * - Linux：objcopy 生成目标文件，经 `_binary_classpath_zip_start/_end` 暴露；
 * - 插件（`.keel`）不走这条链路，资源在包内 `resources/`。
 */
class KeelClasspathPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("keelClasspath", KeelClasspathExtension::class.java)

        project.plugins.withId("org.jetbrains.kotlin.multiplatform") {
            project.afterEvaluate {
                if (!extension.enabled) return@afterEvaluate
                val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)
                val targets = kotlin.targets.withType(KotlinNativeTarget::class.java)
                    .filter { extension.targets.isEmpty() || it.name in extension.targets }
                targets.forEach { configureTarget(project, it, extension) }
            }
        }
    }

    private fun configureTarget(
        project: Project,
        target: KotlinNativeTarget,
        extension: KeelClasspathExtension,
    ) {
        val suffix = target.name.replaceFirstChar { it.uppercaseChar() }
        val embedDir = project.layout.buildDirectory
            .dir("generated/keel/classpath/${target.name}")
            .get().asFile

        val zipTask = project.tasks.register("keelClasspathZip$suffix", Zip::class.java) { task ->
            task.group = "keel"
            task.description = "把 ${target.name} 的资源合并成 classpath.zip"
            task.archiveFileName.set(CLASSPATH_ZIP_NAME)
            task.destinationDirectory.set(embedDir)
            task.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            task.entryCompression =
                if (extension.compressed) ZipEntryCompression.DEFLATED else ZipEntryCompression.STORED
            task.isPreserveFileTimestamps = false
            task.isReproducibleFileOrder = true

            resourceSourceSetsOf(target).forEach { sourceSet ->
                task.from(sourceSet.resources, Action<CopySpec> { it.into("") })
            }
            task.from(extension.resources.map { project.file(it) }, Action<CopySpec> { it.into("") })
        }

        project.gradle.projectsEvaluated {
            dependencyClasspathResources(project, target.name).forEach { resource ->
                zipTask.configure { task ->
                    task.from(resource, Action<CopySpec> { it.into("") })
                }
            }
        }

        val embed = when {
            target.name.startsWith("mingw") ->
                embedAsWindowsResource(project, suffix, zipTask, embedDir)

            target.name.startsWith("linux") ->
                embedAsLinuxObject(project, target, suffix, zipTask, embedDir, extension)

            else -> {
                project.logger.warn(
                    "classpath 嵌入暂不支持目标 ${target.name}：资源不会进入可执行文件"
                )
                ClasspathEmbed(zipTask, embedDir.resolve(CLASSPATH_ZIP_NAME), emptyList())
            }
        }

        target.binaries.withType(Executable::class.java).configureEach { binary ->
            binary.linkerOpts.addAll(embed.linkerArgs)
            binary.linkTaskProvider.configure { linkTask ->
                linkTask.dependsOn(embed.steps)
                linkTask.inputs.files(embed.artifact)
            }
        }
    }

    private fun embedAsWindowsResource(
        project: Project,
        suffix: String,
        zipTask: TaskProvider<Zip>,
        embedDir: File,
    ): ClasspathEmbed {
        val rcTask = project.tasks.register("keelClasspathRc$suffix") { task ->
            task.group = "keel"
            task.dependsOn(zipTask)
            val rc = embedDir.resolve(RC_FILE)
            task.outputs.file(rc)
            task.doLast {
                embedDir.mkdirs()
                rc.writeText(
                    "IDR_CLASSPATH  CLASSPATH  \"$CLASSPATH_ZIP_NAME\"\n",
                    Charsets.UTF_8,
                )
            }
        }

        val windresTask = project.tasks.register("keelClasspathWindres$suffix", Exec::class.java) { task ->
            task.group = "keel"
            task.dependsOn(zipTask, rcTask)
            task.workingDir = embedDir
            task.inputs.file(embedDir.resolve(CLASSPATH_ZIP_NAME))
            task.inputs.file(embedDir.resolve(RC_FILE))
            task.outputs.file(embedDir.resolve(CLASSPATH_RES))

            val binDir = konanMingwBinDir()
            task.commandLine(
                File(binDir, "windres").absolutePath,
                RC_FILE, "-O", "coff", "-o", CLASSPATH_RES,
            )
            task.environment(
                "PATH",
                "${binDir.absolutePath}${File.pathSeparator}${System.getenv("PATH")}",
            )
        }

        val resource = embedDir.resolve(CLASSPATH_RES)
        return ClasspathEmbed(windresTask, resource, listOf(resource.absolutePath))
    }

    private fun embedAsLinuxObject(
        project: Project,
        target: KotlinNativeTarget,
        suffix: String,
        zipTask: TaskProvider<Zip>,
        embedDir: File,
        extension: KeelClasspathExtension,
    ): ClasspathEmbed {
        val config = objcopyConfigFor(target.name)
        if (config == null) {
            project.logger.warn(
                "未配置 ${target.name} 的 objcopy：资源不会进入可执行文件"
            )
            return ClasspathEmbed(zipTask, embedDir.resolve(CLASSPATH_ZIP_NAME), emptyList())
        }

        val useWsl = Os.isFamily(Os.FAMILY_WINDOWS)
        val objcopyTask = project.tasks.register("keelClasspathObjcopy$suffix", Exec::class.java) { task ->
            task.group = "keel"
            task.dependsOn(zipTask)
            task.inputs.file(embedDir.resolve(CLASSPATH_ZIP_NAME))
            task.outputs.file(embedDir.resolve(CLASSPATH_OBJECT))

            val args = listOf(
                config.tool,
                "-I", "binary", "-O", config.outputFormat, "-B", config.architecture,
                CLASSPATH_ZIP_NAME, CLASSPATH_OBJECT,
            )
            if (useWsl) {
                task.commandLine(
                    listOf("wsl") + wslOptions(extension) +
                        listOf("--", "bash", "-c", "cd ${toWslPath(embedDir)} && ${args.joinToString(" ")}")
                )
            } else {
                task.workingDir = embedDir
                task.commandLine(args)
            }
        }

        val obj = embedDir.resolve(CLASSPATH_OBJECT)
        return ClasspathEmbed(
            objcopyTask,
            obj,
            listOf("-Wl,--export-dynamic", obj.absolutePath),
        )
    }

    private fun resourceSourceSetsOf(target: KotlinNativeTarget): List<KotlinSourceSet> {
        val ordered = mutableListOf<KotlinSourceSet>()

        fun visit(sourceSet: KotlinSourceSet) {
            if (ordered.any { it.name == sourceSet.name }) return
            ordered += sourceSet
            sourceSet.dependsOn.forEach { visit(it) }
        }

        visit(target.compilations.getByName("main").defaultSourceSet)
        return ordered
    }

    private fun dependencyClasspathResources(project: Project, targetName: String): List<Any> {
        val result = mutableListOf<Any>()
        val visitedProjects = mutableSetOf<String>()
        val visitedSourceSets = mutableSetOf<String>()

        fun visit(current: Project) {
            if (current == project || !visitedProjects.add(current.path)) return

            val kotlin = current.extensions.findByType(KotlinMultiplatformExtension::class.java)
            val target = kotlin?.targets?.findByName(targetName) as? KotlinNativeTarget
            if (target != null) {
                resourceSourceSetsOf(target).forEach { sourceSet ->
                    if (visitedSourceSets.add("${current.path}:${sourceSet.name}")) {
                        result.add(sourceSet.resources)
                    }
                }
            }

            projectDependenciesOf(current).forEach { visit(it) }
        }

        projectDependenciesOf(project).forEach { visit(it) }
        return result
    }

    private fun projectDependenciesOf(project: Project): List<Project> =
        project.configurations
            .flatMap { configuration ->
                configuration.dependencies.withType(ProjectDependency::class.java)
            }
            .mapNotNull { dependency -> project.findProject(dependency.path) }
            .distinctBy { it.path }

    private fun wslOptions(extension: KeelClasspathExtension): List<String> = buildList {
        extension.wslDistro?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-d", it)) }
        extension.wslUser?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-u", it)) }
    }

    private fun toWslPath(file: File): String {
        val path = file.absolutePath
        val drive = path.substring(0, 1).lowercase()
        val rest = path.substring(2).replace('\\', '/')
        return "/mnt/$drive$rest"
    }

    private fun konanMingwBinDir(): File {
        val konanDataDir = System.getenv("KONAN_DATA_DIR")
            ?: "${System.getProperty("user.home")}/.konan"
        val binDir = File(konanDataDir, "dependencies/msys2-mingw-w64-x86_64-2/bin")
        check(binDir.isDirectory) {
            "未找到 Kotlin/Native 自带的 mingw 工具链：$binDir\n" +
                "请先执行一次 mingwX64 链接（会自动下载工具链），或检查 KONAN_DATA_DIR。"
        }
        return binDir
    }

    private data class ObjcopyConfig(
        val tool: String,
        val outputFormat: String,
        val architecture: String,
    )

    private fun objcopyConfigFor(targetName: String): ObjcopyConfig? = when (targetName) {
        "linuxArm64" -> ObjcopyConfig("aarch64-linux-gnu-objcopy", "elf64-littleaarch64", "aarch64")
        "linuxX64" -> ObjcopyConfig("x86_64-linux-gnu-objcopy", "elf64-x86-64", "i386:x86-64")
        else -> null
    }

    private class ClasspathEmbed(
        val steps: TaskProvider<*>,
        val artifact: File,
        val linkerArgs: List<String>,
    )

    private companion object {
        const val CLASSPATH_ZIP_NAME = "classpath.zip"
        const val CLASSPATH_RES = "classpath.res"
        const val CLASSPATH_OBJECT = "classpath.o"
        const val RC_FILE = "classpath.rc"
    }
}
