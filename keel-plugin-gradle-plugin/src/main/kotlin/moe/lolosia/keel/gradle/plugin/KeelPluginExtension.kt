package moe.lolosia.keel.gradle.plugin

import org.gradle.api.Action
import org.gradle.api.Project

/**
 * `.keel` 打包扩展。
 *
 * 默认把 `src/commonMain/resources` 作为资源，`plugin.json` 放在包根；
 * `targets` 为空时按当前宿主选择（Windows → mingwX64，Linux ARM → linuxArm64）。
 * 显式开启 [executable] 时，同一个工程额外产出一个内嵌 bundle 的单文件 exe。
 */
open class KeelPluginExtension(private val project: Project) {

    var enabled: Boolean = true

    /** 包根 manifest 的源文件。 */
    var manifest: String = "src/commonMain/resources/plugin.json"

    /** 取哪个构建类型的 sharedLib：`DEBUG` / `RELEASE`。 */
    var buildType: String = "DEBUG"

    /** 输出文件名；默认 `<project>-<version>.keel`。 */
    var packageName: String? = null

    /** 是否自动给 `commonMainImplementation` 加 `keel-classpath-full`。 */
    var autoAddClasspathFull: Boolean = true

    /** 自动添加的版本；默认取 project.version。 */
    var keelVersion: String? = null

    /** 参与打包的目标；为空则取宿主目标。 */
    val targets: MutableList<String> = mutableListOf()

    /** 额外资源目录；统一放进包内 `resources/`。 */
    val resources: MutableList<String> = mutableListOf()

    /** 单 exe 产出配置；默认关闭。 */
    val executable: KeelExecutableExtension = KeelExecutableExtension(project)

    /**
     * `keelRun` 用的 Host 可执行文件；为空时按 `keelVersion` 从 Maven 取
     * `moe.lolosia.keel:keel-host-<target>:<version>@zip`。
     */
    var runHostExecutable: String? = null

    /** `keelRun` 的 HTTP 端口；命令行 `-Pkeel.run.port=` 优先。 */
    var runPort: Int = 7191

    /** `keelRun` 的 `--serve-ms`；为空表示常驻，命令行 `-Pkeel.run.serveMs=` 优先。 */
    var runServeMillis: Long? = null

    /** `keelRun` 的工作目录；默认 `build/keel-run`。 */
    var runWorkDir: String = "build/keel-run"

    /** 额外塞进运行目录的 `.keel` 工件坐标（能力包 / 依赖插件）。 */
    val runPlugins: MutableList<String> = mutableListOf()

    /** `keelRun` 启动 Host 时注入的环境变量。 */
    val runEnvironment: MutableMap<String, String> = mutableMapOf()

    /** 追加给 Host 的命令行参数。 */
    val runArguments: MutableList<String> = mutableListOf()

    fun target(name: String) {
        targets += name
    }

    fun targets(vararg names: String) {
        targets += names
    }

    fun resources(vararg paths: String) {
        resources += paths
    }

    fun executable(action: Action<KeelExecutableExtension>) {
        action.execute(executable)
    }

    /** 追加一个运行期 `.keel` 工件，例如 `moe.lolosia.keel:keel-database-mariadb:0.1.0-SNAPSHOT@keel`。 */
    fun runPlugin(coordinate: String) {
        runPlugins += coordinate
    }

    fun runEnv(name: String, value: String) {
        runEnvironment[name] = value
    }

    fun runArg(value: String) {
        runArguments += value
    }
}
