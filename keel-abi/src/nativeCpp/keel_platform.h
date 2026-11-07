#ifndef KEEL_PLATFORM_H
#define KEEL_PLATFORM_H

/*
 * 线程与同步原语的薄封装，只给 keel-abi 的 C 实现用。
 *
 * 不导出、不进 Kotlin：同名头文件在每个二进制里各自编译一份。
 */

#include <stdint.h>

#define KEEL_WAIT_FOREVER 0xFFFFFFFFu
#define KEEL_WAIT_SLICE_MS 1000u

#ifdef _WIN32

#include <windows.h>

typedef CRITICAL_SECTION keel_mutex_t;
typedef CONDITION_VARIABLE keel_cond_t;

static inline void keel_mutex_init(keel_mutex_t *m) { InitializeCriticalSection(m); }
static inline void keel_mutex_destroy(keel_mutex_t *m) { DeleteCriticalSection(m); }
static inline void keel_mutex_lock(keel_mutex_t *m) { EnterCriticalSection(m); }
static inline void keel_mutex_unlock(keel_mutex_t *m) { LeaveCriticalSection(m); }

static inline void keel_cond_init(keel_cond_t *c) { InitializeConditionVariable(c); }
static inline void keel_cond_destroy(keel_cond_t *c) { (void)c; }
static inline void keel_cond_broadcast(keel_cond_t *c) { WakeAllConditionVariable(c); }

/* @return 0 被唤醒 / 1 超时 / -1 错误 */
static inline int keel_cond_wait(keel_cond_t *c, keel_mutex_t *m, uint32_t timeout_ms) {
    DWORD ms = (timeout_ms == KEEL_WAIT_FOREVER) ? INFINITE : (DWORD)timeout_ms;
    if (SleepConditionVariableCS(c, m, ms)) return 0;
    return (GetLastError() == ERROR_TIMEOUT) ? 1 : -1;
}

typedef HANDLE keel_thread_t;

/* 线程入口签名在两个平台上不同，用宏抹平，避免函数指针强转告警。 */
#define KEEL_THREAD_RETURN DWORD WINAPI
#define KEEL_THREAD_ARG LPVOID
#define KEEL_THREAD_RETURN_VALUE 0u
typedef DWORD(WINAPI *keel_thread_entry)(LPVOID);

static inline int keel_thread_start(keel_thread_t *thread, keel_thread_entry entry, KEEL_THREAD_ARG arg) {
    HANDLE handle = CreateThread(NULL, 0, entry, arg, 0, NULL);
    if (!handle) return -1;
    *thread = handle;
    return 0;
}

static inline void keel_thread_join(keel_thread_t *thread) {
    if (!thread || !*thread) return;
    WaitForSingleObject(*thread, INFINITE);
    CloseHandle(*thread);
    *thread = NULL;
}

#else

#include <errno.h>
#include <pthread.h>
#include <time.h>

typedef pthread_mutex_t keel_mutex_t;
typedef pthread_cond_t keel_cond_t;

static inline void keel_mutex_init(keel_mutex_t *m) { pthread_mutex_init(m, NULL); }
static inline void keel_mutex_destroy(keel_mutex_t *m) { pthread_mutex_destroy(m); }
static inline void keel_mutex_lock(keel_mutex_t *m) { pthread_mutex_lock(m); }
static inline void keel_mutex_unlock(keel_mutex_t *m) { pthread_mutex_unlock(m); }

static inline void keel_cond_init(keel_cond_t *c) { pthread_cond_init(c, NULL); }
static inline void keel_cond_destroy(keel_cond_t *c) { pthread_cond_destroy(c); }
static inline void keel_cond_broadcast(keel_cond_t *c) { pthread_cond_broadcast(c); }

/* @return 0 被唤醒 / 1 超时 / -1 错误 */
static inline int keel_cond_wait(keel_cond_t *c, keel_mutex_t *m, uint32_t timeout_ms) {
    if (timeout_ms == KEEL_WAIT_FOREVER) {
        return pthread_cond_wait(c, m) == 0 ? 0 : -1;
    }
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    uint64_t nsec = (uint64_t)deadline.tv_nsec + (uint64_t)(timeout_ms % 1000u) * 1000000ull;
    deadline.tv_sec += (time_t)(timeout_ms / 1000u) + (time_t)(nsec / 1000000000ull);
    deadline.tv_nsec = (long)(nsec % 1000000000ull);

    int rc = pthread_cond_timedwait(c, m, &deadline);
    if (rc == 0) return 0;
    return (rc == ETIMEDOUT) ? 1 : -1;
}

typedef pthread_t keel_thread_t;

#define KEEL_THREAD_RETURN void *
#define KEEL_THREAD_ARG void *
#define KEEL_THREAD_RETURN_VALUE NULL
typedef void *(*keel_thread_entry)(void *);

static inline int keel_thread_start(keel_thread_t *thread, keel_thread_entry entry, KEEL_THREAD_ARG arg) {
    return pthread_create(thread, NULL, entry, arg) == 0 ? 0 : -1;
}

static inline void keel_thread_join(keel_thread_t *thread) {
    if (!thread) return;
    pthread_join(*thread, NULL);
}

#endif

#endif /* KEEL_PLATFORM_H */
