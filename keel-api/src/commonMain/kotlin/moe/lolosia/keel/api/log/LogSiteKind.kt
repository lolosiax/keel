package moe.lolosia.keel.api.log

/**
 * 调用点所在的声明类型。
 *
 * 取“直接包裹语句的作用域”，而不是整条祖先链：lambda 里的日志属于
 * [ANONYMOUS_FUNCTION]，即使它外层是类成员函数。
 */
enum class LogSiteKind {

    /** 顶级具名函数。 */
    TOP_LEVEL_FUNCTION,

    /** 顶级属性初始化器 / 顶级 lambda。 */
    TOP_LEVEL_ANONYMOUS_FUNCTION,

    /** 类、对象、伴生对象里的具名函数。 */
    CLASS_FUNCTION,

    /** 类体里的属性初始化器、init 块或显式访问器。 */
    CLASS_MEMBER_STATEMENT,

    /** 函数或 lambda 内部的局部具名函数。 */
    NAMED_FUNCTION,

    /** 局部 lambda；外层存在具名函数或类。 */
    ANONYMOUS_FUNCTION,
}
