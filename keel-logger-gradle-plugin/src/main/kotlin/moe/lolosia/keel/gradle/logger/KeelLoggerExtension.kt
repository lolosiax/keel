package moe.lolosia.keel.gradle.logger

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
}
