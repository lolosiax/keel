package moe.lolosia.keel.api.annotation

/**
 * 声明一个跨模块服务接口。
 *
 * 标注后由 Keel 的 KSP 处理器在**实现方模块**生成：
 * 服务 Key、客户端代理、服务端分发器、`ctx.modules.<name>` 访问器、
 * 参数 / 返回值的序列化包装。
 *
 * 接口方法参数与返回值必须是可序列化类型（标量 / String / 枚举 / `@Serializable` 类），
 * 函数类型参数会被生成为回调。
 *
 * @property id 全局唯一的服务 id，建议与模块 id 一致（如 `queue`）
 * @property version 服务契约版本；破坏性变更时递增
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ModuleService(val id: String, val version: String = "1")
