package moe.lolosia.keel.gradle.logger

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property

/** `keelLogger { ... }` 配置。 */
open class KeelLoggerExtension(objects: ObjectFactory) {

    /** 是否启用日志调用改写；默认开启。 */
    val enabled: Property<Boolean> = objects.property(Boolean::class.javaObjectType).convention(true)

    /** 是否给异常构造 / `throw` 插桩，记录创建与抛出点；默认开启。 */
    val exceptionTrace: Property<Boolean> = objects.property(Boolean::class.javaObjectType).convention(true)

    /** 相对路径基准；默认取 Gradle 调用目录。 */
    val workspaceRoot: Property<String> = objects.property(String::class.java)

    /** 是否插入调试检查点并写出 `.keel-debug` 位置表；默认开启。 */
    val debug: Property<Boolean> = objects.property(Boolean::class.javaObjectType).convention(true)

    /** 位置表输出目录；默认 `build/keel-debug`。 */
    val debugOutput: DirectoryProperty = objects.directoryProperty()

    /** 位置表首行的模块 id；默认取工程名。插件工程由打包插件改成 `plugin.json` 的 `id`。 */
    val debugModule: Property<String> = objects.property(String::class.java)
}
