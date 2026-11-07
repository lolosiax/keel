/*
 * 有界事件队列。
 *
 * 节点内存一次性预分配成环形缓冲，运行期只做结构体拷贝，不 malloc / free；
 * 这样 Host 与插件即使来自不同 CRT，也不会出现「谁分配的谁释放不了」的问题。
 * 载荷是唯一动态内存，由发送方分配、发送方通过 KeelEvent.release 归还。
 */

#include "keel_abi.h"
#include "keel_platform.h"

#include <stdlib.h>

struct KeelQueueTag {
    KeelEvent *slots;
    uint32_t capacity;
    uint32_t head;
    uint32_t count;
    int closed;
    keel_mutex_t mutex;
    keel_cond_t cond;
};

KeelQueue *keel_queue_create(uint32_t capacity) {
    if (capacity == 0) return NULL;

    KeelQueue *queue = (KeelQueue *)calloc(1, sizeof(KeelQueue));
    if (!queue) return NULL;

    queue->slots = (KeelEvent *)calloc(capacity, sizeof(KeelEvent));
    if (!queue->slots) {
        free(queue);
        return NULL;
    }

    queue->capacity = capacity;
    keel_mutex_init(&queue->mutex);
    keel_cond_init(&queue->cond);
    return queue;
}

void keel_queue_destroy(KeelQueue *queue) {
    if (!queue) return;

    keel_mutex_lock(&queue->mutex);
    queue->closed = 1;
    keel_cond_broadcast(&queue->cond);
    keel_mutex_unlock(&queue->mutex);

    keel_cond_destroy(&queue->cond);
    keel_mutex_destroy(&queue->mutex);
    free(queue->slots);
    free(queue);
}

void keel_queue_wake(KeelQueue *queue) {
    if (!queue) return;

    keel_mutex_lock(&queue->mutex);
    queue->closed = 1;
    keel_cond_broadcast(&queue->cond);
    keel_mutex_unlock(&queue->mutex);
}

KeelStatus keel_queue_push(KeelQueue *queue, const KeelEvent *event, uint32_t timeout_ms) {
    if (!queue || !event) return KEEL_ERR_INVALID;

    keel_mutex_lock(&queue->mutex);
    uint64_t waited = 0;

    while (!queue->closed && queue->count == queue->capacity) {
        if (timeout_ms == 0) {
            keel_mutex_unlock(&queue->mutex);
            return KEEL_ERR_FULL;
        }

        uint32_t slice = KEEL_WAIT_SLICE_MS;
        if (timeout_ms != KEEL_WAIT_FOREVER) {
            uint64_t remaining = (uint64_t)timeout_ms - waited;
            if (remaining < slice) slice = (uint32_t)remaining;
        }

        int rc = keel_cond_wait(&queue->cond, &queue->mutex, slice);
        if (rc < 0) {
            keel_mutex_unlock(&queue->mutex);
            return KEEL_ERR_FAILED;
        }
        if (timeout_ms != KEEL_WAIT_FOREVER) {
            waited += slice;
            if (rc == 1 && waited >= timeout_ms) {
                keel_mutex_unlock(&queue->mutex);
                return KEEL_ERR_TIMEOUT;
            }
        }
    }

    if (queue->closed) {
        keel_mutex_unlock(&queue->mutex);
        return KEEL_ERR_CLOSED;
    }

    uint32_t index = (queue->head + queue->count) % queue->capacity;
    queue->slots[index] = *event;
    queue->count++;
    keel_cond_broadcast(&queue->cond);
    keel_mutex_unlock(&queue->mutex);
    return KEEL_OK;
}

KeelStatus keel_queue_pop(KeelQueue *queue, KeelEvent *out, uint32_t timeout_ms) {
    if (!queue || !out) return KEEL_ERR_INVALID;

    keel_mutex_lock(&queue->mutex);
    uint64_t waited = 0;

    while (queue->count == 0 && !queue->closed) {
        if (timeout_ms == 0) {
            keel_mutex_unlock(&queue->mutex);
            return KEEL_ERR_TIMEOUT;
        }

        uint32_t slice = KEEL_WAIT_SLICE_MS;
        if (timeout_ms != KEEL_WAIT_FOREVER) {
            uint64_t remaining = (uint64_t)timeout_ms - waited;
            if (remaining < slice) slice = (uint32_t)remaining;
        }

        int rc = keel_cond_wait(&queue->cond, &queue->mutex, slice);
        if (rc < 0) {
            keel_mutex_unlock(&queue->mutex);
            return KEEL_ERR_FAILED;
        }
        if (timeout_ms != KEEL_WAIT_FOREVER) {
            waited += slice;
            if (rc == 1 && waited >= timeout_ms) {
                keel_mutex_unlock(&queue->mutex);
                return KEEL_ERR_TIMEOUT;
            }
        }
    }

    if (queue->count == 0) {
        keel_mutex_unlock(&queue->mutex);
        return KEEL_ERR_CLOSED;
    }

    *out = queue->slots[queue->head];
    queue->head = (queue->head + 1) % queue->capacity;
    queue->count--;
    keel_cond_broadcast(&queue->cond);
    keel_mutex_unlock(&queue->mutex);
    return KEEL_OK;
}
