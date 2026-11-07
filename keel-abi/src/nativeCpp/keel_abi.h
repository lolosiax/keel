#ifndef KEEL_ABI_H
#define KEEL_ABI_H

/*
 * Keel 跨运行时 ABI。
 *
 * 规则：
 * 1. 只允许 blittable 类型：定宽整数、指针、C 结构体、C 函数指针。
 * 2. 结构体通过指针传递；布局以本头文件为唯一来源，Kotlin 侧由 cinterop 生成。
 * 3. 本头文件里的函数由 C 实现（每个二进制各带一份，hidden visibility），
 *    调用时不得进入任何 Kotlin/Native 运行时。
 * 4. 插件 dylib 唯一对外导出的符号是 keel_plugin_bootstrap / keel_plugin_shutdown。
 * 5. 尚未发布任何版本，字段可以随时调整；发布后再改必须提升 KEEL_ABI_VERSION。
 */

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define KEEL_ABI_VERSION 1u

/* 插件 dylib 里需要对外可见的符号；其余一律 hidden。 */
#if defined(_WIN32)
#define KEEL_EXPORT __declspec(dllexport)
#else
#define KEEL_EXPORT __attribute__((visibility("default")))
#endif

typedef enum KeelStatus {
    KEEL_OK = 0,
    KEEL_ERR_INVALID = 1,
    KEEL_ERR_FULL = 2,
    KEEL_ERR_TIMEOUT = 3,
    KEEL_ERR_CLOSED = 4,
    KEEL_ERR_UNSUPPORTED = 5,
    KEEL_ERR_FAILED = 6
} KeelStatus;

typedef enum KeelEventKind {
    KEEL_EVENT_COMMAND = 1,
    KEEL_EVENT_COMPLETE = 2,
    KEEL_EVENT_CANCEL = 3,
    KEEL_EVENT_BUS = 4,
    KEEL_EVENT_DISPOSE = 5,
    KEEL_EVENT_SHUTDOWN = 6,
    KEEL_EVENT_LOG = 7
} KeelEventKind;

typedef enum KeelLogLevel {
    KEEL_LOG_TRACE = 0,
    KEEL_LOG_DEBUG = 1,
    KEEL_LOG_INFO = 2,
    KEEL_LOG_WARN = 3,
    KEEL_LOG_ERROR = 4,
    KEEL_LOG_FATAL = 5
} KeelLogLevel;

/*
 * 保留 subject 值。业务 subject 从 0x1000 开始，避免与协议事件冲突。
 *
 * KEEL_SUBJECT_START 是插件收到的第一条命令：事件泵线程收到它时才第一次进入
 * 插件 Kotlin 运行时并执行 worker_main，结果以 COMPLETE + status 回投 Host。
 */
#define KEEL_SUBJECT_START 1u

/*
 * 健康检查：Host 投递 COMMAND + KEEL_SUBJECT_PING，插件必须回一条
 * status = KEEL_OK、correlation 相同的 COMPLETE。
 *
 * 这是最小请求 / 应答回路，用于验证事件收发、转发与 correlation 配对。
 */
#define KEEL_SUBJECT_PING 2u

/*
 * 不透明队列句柄。
 *
 * C 实现侧只看到前向声明，真正的布局在 keel_queue.c 里。
 *
 * cinterop 侧给一个完整的占位定义：cinterop 只会把完整结构体生成到配置的包
 * （`moe.lolosia.keel.abi.c`），不完整声明会被放进合成包 `cnames.structs`，
 * 而合成包在 IDE 的中间源集分析里不可见。
 *
 * 占位字段与实际布局无关：Kotlin 侧只持有指针，不得解引用、分配、按值传递或读写该类型。
 */
#ifdef KEEL_CINTEROP
typedef struct KeelQueueTag {
    void *reserved;
} KeelQueue;
#else
typedef struct KeelQueueTag KeelQueue;
#endif

/* 不透明句柄：C 层引用计数表的槽位地址，任何语言都不得解引用。 */
typedef void *KeelHandle;

struct KeelEvent;

/*
 * 事件载荷的内存归还函数。
 *
 * 由载荷的分配方提供，必须是纯 C 函数；接收方复制完载荷后调用。
 * 每个二进制自带一份 keel_event_release_default，避免跨 CRT 释放。
 */
typedef void (*KeelEventRelease)(void *user, const struct KeelEvent *event);

/*
 * 事件。
 *
 * 事件是自包含的：载荷地址、长度与归还函数都在结构体里，接收方读完载荷后
 * 调用 release(release_user, &event)，不需要额外记账。
 *
 * 队列内部只做结构体拷贝，不接管载荷所有权；载荷由发送方分配、发送方归还。
 *
 * @field status  KeelStatus；COMMAND 由发送方填 KEEL_OK，COMPLETE 用来回传结果
 */
typedef struct KeelEvent {
    uint32_t size;          /* sizeof(KeelEvent)，用于版本兼容 */
    uint16_t kind;          /* KeelEventKind */
    uint16_t flags;
    uint64_t seq;           /* 发送方单调递增 */
    uint64_t source;        /* 模块 id；0 表示 Host */
    uint64_t target;        /* 模块 id；0 表示 Host */
    uint64_t correlation;   /* 请求 / 应答配对 */
    uint64_t subject;       /* 事件主体：句柄、服务 id、回调 id、KEEL_SUBJECT_* */
    uint32_t payload_size;
    int32_t status;         /* KeelStatus；接收方只读 */
    void *payload;          /* 发送方分配的载荷；可为 NULL */
    KeelEventRelease release; /* 载荷归还可为 NULL（无载荷时） */
    void *release_user;     /* 透传给 release 的上下文 */
} KeelEvent;

/* Host 侧能力表：插件通过它创建 / 归还句柄、取 Host 收件箱。 */
typedef struct KeelHostApi {
    uint32_t abi_version;
    uint32_t struct_size;

    uint64_t host_id;

    /*
     * Host 收件箱：bootstrap 前由 Host 填好，之后只读。
     *
     * 必须是指针字段而不是函数指针：插件在自己的 Worker 线程上调用函数指针会进入
     * Host 的 Kotlin 运行时，违反「一个线程只能进入一个运行时」。
     */
    KeelQueue *host_inbox;

    /*
     * 把宿主侧（或插件侧）钉住的 StableRef 注册为跨运行时句柄。
     * stable_ref 是不透明指针，C 层只保存、不访问。
     * owner_module / owner_inbox 决定引用归零时 DISPOSE 事件投递到哪里。
     */
    KeelHandle (*handle_new)(void *stable_ref, uint64_t owner_module, KeelQueue *owner_inbox);
    void (*handle_retain)(KeelHandle handle);
    void (*handle_release)(KeelHandle handle);
} KeelHostApi;

/* 插件能力表：Host 在 bootstrap 后读取，之后只通过 inbox 投递。 */
typedef struct KeelPluginApi {
    uint32_t abi_version;
    uint32_t struct_size;

    uint64_t plugin_id;

    const KeelHostApi *host;

    KeelQueue *inbox;
} KeelPluginApi;

/* 事件分发（Kotlin 侧 @CName）；只在事件泵线程上调用。 */
typedef void (*KeelPluginWorkerEvent)(const KeelEvent *event);

/* worker_main 回填给 C 的分发表；C 用它把后续事件交给 Kotlin。 */
typedef struct KeelPluginHandlers {
    uint32_t struct_size;
    uint32_t reserved;
    KeelPluginWorkerEvent on_event;
} KeelPluginHandlers;

/*
 * 插件 Worker 入口（Kotlin 侧 @CName，由 Host 用 plugin.json.entry 解析）。
 *
 * 只在 bootstrap 创建的事件泵线程上被调用一次，且是**该线程第一次进入 Kotlin**。
 * 启动时把事件分发表回填到 out；之后 C 把所有事件交给 out->on_event。
 */
typedef KeelStatus (*KeelPluginWorkerMain)(const KeelPluginApi *api, KeelPluginHandlers *out);

/*
 * 插件 dylib 的唯一导出入口。
 *
 * 这个函数**完全在 C 里执行，不触碰 Kotlin**：
 * - 保存 host 表与 worker_main 指针；
 * - 创建 inbox 队列（填好 plugin_id，插件后续事件的 source 用它）；
 * - 创建事件泵线程并立即返回。
 *
 * 事件泵线程循环 pop inbox：
 * - KEEL_EVENT_COMMAND + subject == KEEL_SUBJECT_START：调用 worker_main，
 *   拿到 on_event 后向 Host inbox 投递 COMPLETE（status 为启动结果）；
 * - 其余事件：转交 on_event（启动前收到则丢弃并计数）；
 * - KEEL_EVENT_SHUTDOWN：转交 on_event（若已启动），关闭队列并结束线程。
 *
 * 因此 Host 的调用线程自始至终没有进入插件运行时；启动成功与失败都表现为
 * 一条 COMPLETE 事件，Host 按 status 决定是否继续启用。
 *
 * @param plugin_id Host 分配的模块 id；写进 KeelPluginApi，插件发事件时作为 source
 */
KEEL_EXPORT KeelStatus keel_plugin_bootstrap(const KeelHostApi *host,
                                            uint64_t plugin_id,
                                            KeelPluginWorkerMain worker_main,
                                            KeelPluginApi *out);

/*
 * 向插件 inbox 投递 SHUTDOWN 并等待事件泵线程结束；不会 dlclose。
 *
 * 可以在任意线程调用：它只做入队 + join，不进入插件运行时。
 */
KEEL_EXPORT void keel_plugin_shutdown(void);

/* 以下函数在每个二进制里各有一份（hidden），用于跨运行时队列操作。 */

/* 创建有界队列；节点内存一次性预分配，运行期不再 malloc / free。 */
KeelQueue *keel_queue_create(uint32_t capacity);
void keel_queue_destroy(KeelQueue *queue);

/*
 * 入队。event 被按值拷贝，载荷指针与 release 原样搬运。
 *
 * @param timeout_ms 队列满时最多等待多久；0 表示不等待，直接返回 KEEL_ERR_FULL
 */
KeelStatus keel_queue_push(KeelQueue *queue, const KeelEvent *event, uint32_t timeout_ms);

/*
 * 出队。
 *
 * @param timeout_ms 队列空时最多等待多久；0 表示不等待
 * @return KEEL_OK / KEEL_ERR_TIMEOUT / KEEL_ERR_CLOSED
 */
KeelStatus keel_queue_pop(KeelQueue *queue, KeelEvent *out, uint32_t timeout_ms);

/* 唤醒所有等待者（用于关闭时让 pop 返回 KEEL_ERR_CLOSED）。 */
void keel_queue_wake(KeelQueue *queue);

/*
 * 每个二进制自带的默认归还实现：用本二进制的 allocator 释放载荷。
 * 发送方把它作为 KeelEvent.release 交给接收方，避免跨 CRT 释放。
 */
void keel_event_release_default(void *user, const KeelEvent *event);

/* 载荷分配 / 拷贝：分配方用自己的 CRT，接收方复制后调用 release 归还。 */
void *keel_payload_alloc(uint32_t size);
void keel_payload_copy(void *dst, const void *src, uint32_t size);

/* 把 release 字段指向本二进制的 keel_event_release_default。 */
void keel_event_set_default_release(KeelEvent *event);

#ifdef __cplusplus
}
#endif

#endif /* KEEL_ABI_H */
