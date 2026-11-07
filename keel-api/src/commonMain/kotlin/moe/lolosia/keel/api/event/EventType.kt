package moe.lolosia.keel.api.event

/**
 * 事件类型标识。
 *
 * `id` 是跨模块的唯一契约（如 `system.user.login`），本地 `shared` 只用于类型安全，
 * 不参与跨运行时判断 —— Native 上没有可移植的类身份。
 */
class EventType<T : Event>(val id: String)
