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
 * 4. 唯一对外导出的符号是 keel_plugin_bootstrap / keel_plugin_shutdown。
 */

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define KEEL_ABI_VERSION 1u

typedef enum KeelStatus {
    KEEL_OK = 0,
    KEEL_ERR_INVALID = 1,
    KEEL_ERR_FULL = 2,
    KEEL_ERR_TIMEOUT = 3,
    KEEL_ERR_CLOSED = 4,
    KEEL_ERR_UNSUPPORTED = 5
} KeelStatus;

typedef enum KeelEventKind {
    KEEL_EVENT_COMMAND = 1,
    KEEL_EVENT_COMPLETE = 2,
    KEEL_EVENT_CANCEL = 3,
    KEEL_EVENT_BUS = 4,
    KEEL_EVENT_DISPOSE = 5,
    KEEL_EVENT_SHUTDOWN = 6
} KeelEventKind;

typedef struct KeelQueue KeelQueue;

/* 不透明句柄：C 层引用计数表的槽位地址，任何语言都不得解引用。 */
typedef void *KeelHandle;

typedef struct KeelEvent {
    uint32_t size;          /* sizeof(KeelEvent)，用于版本兼容 */
    uint16_t kind;          /* KeelEventKind */
    uint16_t flags;
    uint64_t seq;           /* 发送方单调递增 */
    uint64_t source;        /* 模块 id；0 表示 Host */
    uint64_t target;        /* 模块 id；0 表示 Host */
    uint64_t correlation;   /* 请求 / 应答配对 */
    uint64_t subject;       /* 事件主体：句柄、服务 id、回调 id 等 */
    uint32_t payload_size;
    uint32_t reserved;
} KeelEvent;

/*
 * 事件载荷的内存归还函数。
 * 由分配方提供，必须是纯 C 函数；接收方复制完载荷后调用。
 */
typedef void (*KeelEventRelease)(void *user, const KeelEvent *event);

/* Host 侧能力表：插件通过它创建 / 归还句柄、取 Host 收件箱。 */
typedef struct KeelHostApi {
    uint32_t abi_version;
    uint32_t struct_size;

    uint64_t host_id;

    KeelQueue *(*host_inbox)(void);

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

    KeelQueue *inbox;
} KeelPluginApi;

/*
 * 插件 dylib 的唯一导出入口。
 *
 * 这个函数由 C 实现：
 * - 只保存 host 表、创建 inbox、创建 Worker 线程，然后在**新线程**上首次进入 Kotlin；
 * - 调用方（Host）线程不会因此进入插件运行时。
 */
KeelStatus keel_plugin_bootstrap(const KeelHostApi *host, KeelPluginApi *out);

/* 通知 Worker 退出并 join；不会 dlclose。 */
void keel_plugin_shutdown(void);

/* 以下函数在每个二进制里各有一份（hidden），用于跨运行时队列操作。 */
KeelQueue *keel_queue_create(uint32_t capacity);
void keel_queue_destroy(KeelQueue *queue);

KeelStatus keel_queue_push(KeelQueue *queue, const KeelEvent *event,
                           KeelEventRelease release, void *user);

KeelStatus keel_queue_pop(KeelQueue *queue, KeelEvent *out, uint32_t timeout_ms,
                          KeelEventRelease *release, void **user);

void keel_queue_wake(KeelQueue *queue);

/*
 * 每个二进制自带的默认归还实现：用本二进制的 allocator 释放载荷。
 * 发送方把它作为 KeelEventRelease 交给接收方，避免跨 CRT 释放。
 */
void keel_event_release_default(void *user, const KeelEvent *event);

#ifdef __cplusplus
}
#endif

#endif /* KEEL_ABI_H */
