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
 * 4. native 插件 dylib 对外导出的符号是 keel_plugin_bootstrap / keel_plugin_shutdown；
 *    capability 包只导出 keel_capability_entry。
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
 * 定时唤醒：事件泵在 timeout 后没有收到事件时，向插件分发表投一条
 * COMMAND + KEEL_SUBJECT_TIMER（无载荷）的事件；调度器据此在 Worker 线程上跑到期任务。
 */
#define KEEL_SUBJECT_TIMER 3u

/* 插件启用时把路由描述表（JSON）注册给 Host。 */
#define KEEL_SUBJECT_ROUTE_REGISTER 4u

/* Host 把一次 HTTP 请求交给插件处理，载荷是 HttpRouteRequest。 */
#define KEEL_SUBJECT_HTTP 5u

/*
 * Host 内部唤醒：外部线程（Ktor）提交请求后投一条，让泵线程立刻结束阻塞式 pop，
 * 不必等 200ms 超时。只在 Host 收件箱里流转，插件不会收到。
 */
#define KEEL_SUBJECT_WAKE 6u

/*
 * 控制台命令：Host 把终端输入的一行拆成 label + args（JSON，ConsoleCommandRequest）
 * 投给插件；插件用 Plugin.onCommand 决定是否接管，回 ConsoleCommandResult。
 *
 * 与 HTTP 一样是请求 / 应答：插件必须回 COMPLETE，handled = false 表示交给下一个插件。
 */
#define KEEL_SUBJECT_CONSOLE 7u

/*
 * 插件启用时把自己提供的服务 id 表（CBOR，ServiceRegistration）注册给 Host，
 * Host 据此把 SERVICE_CALL 转发到正确的插件。
 */
#define KEEL_SUBJECT_SERVICE_REGISTER 8u

/*
 * 跨模块服务调用：载荷是 CBOR 编码的 ServiceCall（service / method / args）。
 *
 * 调用方投给 Host；Host 按 service id 找到提供者并转发，回包原样带回。
 * 调用方与提供者都不直接持有对方的 inbox。
 */
#define KEEL_SUBJECT_SERVICE_CALL 9u

/*
 * Host 通知插件：有新日志。
 *
 * 无载荷、可合并，只表示「按游标来拉取」；日志正文永远不放进事件泵。
 */
#define KEEL_SUBJECT_LOG_WAKE 10u

/*
 * Host 把一条插件间消息交给目标插件。
 *
 * 载荷是 CBOR 的 Message（keel-api 的 `moe.lolosia.keel.api.messaging`）。
 * correlation 为 0 表示单向通知，插件不需要回包；非 0 时插件必须回一条同
 * correlation 的 COMPLETE，载荷就是接收方返回的原始字节。
 */
#define KEEL_SUBJECT_MESSAGE 11u

/*
 * 认证 SPI：Host 向声明了 `provides: ["authn"]` 的插件询问 token 对应的用户。
 *
 * 请求载荷是 CBOR 的 AuthnRequest（token）；应答是 CBOR 的 AuthnReply，
 * 其中 user 为空表示 token 无效 / 已过期。
 */
#define KEEL_SUBJECT_AUTHN 0x800u

/*
 * 授权 SPI：Host 向声明了 `provides: ["authz"]` 的插件取用户的授权快照。
 *
 * 请求载荷是 CBOR 的 AuthzRequest（userId）；应答是 CBOR 的 AuthzSnapshot。
 */
#define KEEL_SUBJECT_AUTHZ 0x801u

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

/*
 * 不透明互斥锁句柄。
 *
 * 与 KeelQueueTag 同理：cinterop 侧给完整占位定义，C 实现侧只看到前向声明。
 * Kotlin 侧只持有指针，不得解引用、分配或按值传递。
 */
#ifdef KEEL_CINTEROP
typedef struct KeelLockTag {
    void *reserved;
} KeelLock;
#else
typedef struct KeelLockTag KeelLock;
#endif

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

    /*
     * database 能力（`kind: capability`，`provides: ["database"]`）。
     *
     * database 指向 KeelDbApi 函数表（布局在 keel_database.h），database_handle 是能力包
     * open 返回的实例；两者都由 Host 在启动时填好，没有安装能力包时为 NULL。
     *
     * 用 void * 而不是具体结构体指针：两组 cinterop 对同一结构体的类型身份不稳定
     * （与 KeelQueueTag 同理），插件侧由 keel-database-api 自己 reinterpret。
     */
    void *database;
    void *database_handle;
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
 * 能力包入口描述（`kind: capability` 的 .keel）。
 *
 * 能力包是纯 C 动态库，不初始化 Kotlin 运行时，Host 可以 dlopen / dlclose。
 * 发现流程：读 plugin.json → 按 lib/<target> 解出库 → dlsym("keel_capability_entry")
 * → 校验 abi_version / struct_size → open(...)。
 *
 * config 的结构由 provides 决定（例如 "database" → keel_database.h 的 KeelDbConfig）；
 * config_size 由 Host 按自己的头文件计算，能力包发现不匹配时返回 KEEL_ERR_UNSUPPORTED。
 * open 成功后 instance 是能力实例，api 是该能力的函数表；两者由 close 释放。
 */
typedef struct KeelCapabilityDescriptor {
    uint32_t abi_version;   /* KEEL_ABI_VERSION */
    uint32_t struct_size;   /* sizeof(KeelCapabilityDescriptor) */
    const char *id;         /* 与 plugin.json 的 id 一致 */
    const char *provides;   /* 能力名，与 plugin.json 的 provides 一致 */
    const char *version;
    KeelStatus (*open)(const KeelHostApi *host, const void *config, uint32_t config_size,
                       void **instance, void **api);
    void (*close)(void *instance);
} KeelCapabilityDescriptor;

/* 能力包唯一导出的符号。 */
KEEL_EXPORT const KeelCapabilityDescriptor *keel_capability_entry(void);

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
 * 关闭插件：投递 SHUTDOWN 并等待事件泵线程结束；不会 dlclose。
 *
 * 可以在任意线程调用：它只做入队 + join，不进入插件运行时。
 * 等价于 `keel_plugin_request_shutdown()` + `keel_plugin_join()`；
 * 需要「等待期间继续服务插件反向请求」时改用那两个函数。
 */
KEEL_EXPORT void keel_plugin_shutdown(void);

/*
 * 只投递 SHUTDOWN，不等待泵线程退出。
 *
 * Host 在停用插件时可以在这之后继续消费事件（插件 `onDisable` 里的日志 / 配置 /
 * 存储 / 服务调用都需要 Host 应答），再用 [keel_plugin_pump_running] 轮询、最后 join。
 *
 * @return 1 表示 SHUTDOWN 已入队；0 表示没有 inbox 或队列满
 */
KEEL_EXPORT int keel_plugin_request_shutdown(void);

/* @return 1 表示事件泵线程仍在运行（给 Host 的轮询提示，不替代 join） */
KEEL_EXPORT int keel_plugin_pump_running(void);

/*
 * 等待事件泵线程结束并销毁 inbox。
 *
 * 只在 [keel_plugin_pump_running] 返回 0 后调用；线程仍在运行时 join 会阻塞。
 */
KEEL_EXPORT void keel_plugin_join(void);

/* 以下函数在每个二进制里各有一份（hidden），用于跨运行时队列操作。 */

/*
 * 设置事件泵的下一次唤醒时间（相对当前时刻的毫秒数）。
 *
 * 只能由拥有该 inbox 的 Worker 线程调用；delay_ms < 0 表示取消定时唤醒。
 * 到点且 inbox 为空时，泵线程投 KEEL_SUBJECT_TIMER 事件给插件分发表。
 */
void keel_plugin_set_wakeup(int64_t delay_ms);

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
 * 进程内互斥锁：跨线程共享可变状态时使用（如 Host 的日志环形缓冲）。
 *
 * 只保证互斥，不保证公平；调用方必须保证 acquire / release 成对。
 */
KeelLock *keel_lock_create(void);
void keel_lock_destroy(KeelLock *lock);
void keel_lock_acquire(KeelLock *lock);
void keel_lock_release(KeelLock *lock);

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

/*
 * Host 专用的退出钩子：安装 Ctrl+C（Windows 另有 Ctrl+Break / Close）/ SIGINT / SIGTERM。
 *
 * 处理函数只置一个原子标志，不做任何 Kotlin 调用；Host 的主循环轮询
 * [keel_shutdown_requested] 后走正常的停插件流程。
 */
void keel_install_shutdown_hook(void);

/* @return 1 表示已收到中断请求 */
int keel_shutdown_requested(void);

/*
 * 控制台输入：C 线程读 stdin，整行写进环形缓冲；Kotlin 主循环用 poll 非阻塞取行。
 *
 * 读线程只做 C 侧操作（fgets / ReadConsoleW），不进入 Kotlin 运行时，
 * 因此不需要把 stdin 交给协程线程池，也不会和事件泵争用线程。
 *
 * @return 1 表示 stdin 是交互式终端（Host 据此打印 `> ` 提示符）；
 *         0 表示管道 / 重定向，仍然会启动读线程，但不该往 stdout 混提示符
 */
int keel_console_start(void);

/*
 * 取一行（已去掉行尾 CR / LF）。
 *
 * @param buf      输出缓冲，UTF-8，自动补 '\0'
 * @param capacity 缓冲容量；超长行按容量截断
 * @return 复制到的字节数；0 表示当前没有输入（或读线程已结束）
 */
int keel_console_poll(char *buf, uint32_t capacity);

/*
 * 往 stdin 接收器的环形缓冲里注入一行（不含行尾换行）。
 *
 * 与 [keel_console_start] 启动的读线程共用同一个缓冲和互斥量，因此插件经
 * Host 转发过来的控制台输入与真实 stdin 完全同构。可以在任意线程调用；
 * 首次调用会自行初始化互斥量，不要求先调用 [keel_console_start]。
 *
 * @param text   UTF-8 字节，不要求以 '\0' 结尾
 * @param length 字节数；超过单行容量时截断
 * @return 1 表示已入队；0 表示缓冲满、参数非法或内存不足
 */
int keel_console_push(const char *text, uint32_t length);

/*
 * @return 1 表示当前进程已附着到控制台（GUI 子系统下双击启动时为 0）
 *
 * Host 据此决定是否往 stdout 写日志；日志归档与文件输出不受影响。
 */
int keel_console_available(void);

/*
 * 桌面应用需要控制台时调用：优先附着到父进程的控制台，失败再新建一个，
 * 并把 stdout / stderr / stdin 接上去（UTF-8 代码页）。
 *
 * @return 1 表示可用
 */
int keel_console_attach(void);

/*
 * 按日切割的文本日志。
 *
 * 文件名固定 `<dir>/keel-YYYY-MM-DD.log`，跨日自动切换；每次写入都刷盘。
 * 线程安全（内部互斥），可以在任意线程调用。不透明布局由 C 实现持有。
 */
#ifdef KEEL_CINTEROP
typedef struct KeelLogFileTag {
    void *reserved;
} KeelLogFile;
#else
typedef struct KeelLogFileTag KeelLogFile;
#endif

/* 打开日志目录（不存在时创建）；失败返回 NULL。 */
KeelLogFile *keel_logfile_open(const char *dir);

/* 追加一段文本；调用方负责换行。 */
void keel_logfile_write(KeelLogFile *file, const char *data, uint32_t size);

void keel_logfile_close(KeelLogFile *file);

/*
 * 看门狗与崩溃归因。
 *
 * Host 启动时调用 keel_watchdog_spawn 拉起同一可执行文件的 `--watchdog` 子进程，
 * 再调用 keel_crash_install 安装致命信号 / 异常处理器。处理器只把最小现场
 * （信号、故障地址、PC/SP/BP、归因模块、构建号）写进 beacon 文件并挂住自己，
 * 由看门狗读进程内存、生成报告、追加日志，最后终止 Host。
 */
uint64_t keel_watchdog_spawn(void);

/* @param log_dir 日志根目录（`work/log`）；beacon 落在其下的 `crash/`。 */
void keel_crash_install(const char *log_dir, const char *build_id);

/* 登记一个已加载模块，供崩溃归因；path 是动态库文件路径。 */
void keel_crash_register_module(const char *name, const char *library_path);

/*
 * 看门狗主循环：阻塞直到父进程正常退出（返回 0）或处理完一次崩溃（返回 1）。
 * @param parent_pid 被监控的 Host 进程 id
 */
int keel_watchdog_watch(uint64_t parent_pid);

/*
 * 符号与地址工具（日志 / 错误信封共用）。
 *
 * keel_symbol_demangle: 把 `_Z...` 还原成 C++ 声明；成功返回 1，失败返回 0。
 * keel_locate_address:  运行时地址 → 模块名 + 模块内偏移；成功返回 1。
 */
int keel_symbol_demangle(const char *mangled, char *out, uint32_t capacity);

int keel_locate_address(uint64_t address, char *module, uint32_t capacity, uint64_t *rva);

#ifdef __cplusplus
}
#endif

#endif /* KEEL_ABI_H */
