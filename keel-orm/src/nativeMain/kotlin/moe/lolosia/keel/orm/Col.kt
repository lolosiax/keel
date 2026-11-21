package moe.lolosia.keel.orm

/**
 * 一个类型安全的列。
 *
 * 只由生成的 `QueryScope<T>.列名` 扩展与 `QueryScope.col(...)` 构造；
 * 业务不要用字符串拼列名，也不要把用户输入当列名。
 */
class Col<T>(val name: String, val type: OrmType<T>)
