/*
 * 进程内互斥锁。
 *
 * 与队列一样每个二进制各带一份实现；只给 Kotlin 侧提供「创建 / 加锁 / 解锁 / 销毁」，
 * 内部布局（CRITICAL_SECTION / pthread_mutex_t）不对外暴露。
 */

#include "keel_abi.h"
#include "keel_platform.h"

#include <stdlib.h>

struct KeelLockTag {
    keel_mutex_t mutex;
};

KeelLock *keel_lock_create(void) {
    KeelLock *lock = (KeelLock *)calloc(1, sizeof(KeelLock));
    if (!lock) return NULL;
    keel_mutex_init(&lock->mutex);
    return lock;
}

void keel_lock_destroy(KeelLock *lock) {
    if (!lock) return;
    keel_mutex_destroy(&lock->mutex);
    free(lock);
}

void keel_lock_acquire(KeelLock *lock) {
    if (!lock) return;
    keel_mutex_lock(&lock->mutex);
}

void keel_lock_release(KeelLock *lock) {
    if (!lock) return;
    keel_mutex_unlock(&lock->mutex);
}
