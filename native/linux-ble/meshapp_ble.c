/**
 * meshapp-ble: Linux BLE for MeshApp
 *
 * Pure C implementation using sd-bus (libsystemd) for BlueZ D-Bus communication.
 * Uses AcquireNotify/AcquireWrite for fd-based GATT I/O — bypasses D-Bus for data transfer.
 *
 * CRITICAL: sd-bus is NOT thread-safe. ALL sd-bus calls MUST happen on the worker thread.
 * API functions dispatch work to the worker thread via run_on_worker() and block until done.
 * This mirrors the Windows WinRT pattern where all WinRT ops run on the MTA worker thread.
 */

#include "meshapp_ble.h"

#include <systemd/sd-bus.h>
#include <pthread.h>
#include <poll.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <time.h>

/* ==================== Constants ==================== */

#define BLUEZ_BUS          "org.bluez"
#define ADAPTER_IFACE      "org.bluez.Adapter1"
#define DEVICE_IFACE       "org.bluez.Device1"
#define CHAR_IFACE         "org.bluez.GattCharacteristic1"
#define PROPS_IFACE        "org.freedesktop.DBus.Properties"
#define OBJMGR_IFACE       "org.freedesktop.DBus.ObjectManager"

#define PROFILE_MESHTASTIC 0
#define PROFILE_MESHCORE   1

#define SERVICE_UUID       "6ba1b218-15a8-461f-9fa8-5dcae273eafd"
#define FROM_RADIO_UUID    "2c55e69e-4993-11ed-b878-0242ac120002"
#define TO_RADIO_UUID      "f75c76d2-129e-4dad-a1dd-7866124401e7"
#define FROM_NUM_UUID      "ed9da18c-a800-4f66-a670-aa7547e34453"

#define MESHCORE_SERVICE_UUID "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
#define MESHCORE_RX_UUID      "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
#define MESHCORE_TX_UUID      "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

#define MAX_PATH           1024
#define MAX_DRAIN          100
#define POLL_TIMEOUT_MS    100
#define PAIR_TIMEOUT_MS    60000
#define WRITE_VALUE_TIMEOUT_MS 60000

/* ==================== Logging ==================== */

static meshble_log_cb g_log_callback = NULL;

static void log_msg(const char* fmt, ...) {
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    meshble_log_cb cb = g_log_callback;
    if (cb) cb(buf);
    fprintf(stderr, "%s\n", buf);
    fflush(stderr);
}

/* ==================== Task Queue + Sync Dispatch ==================== */

typedef struct task_node {
    void (*func)(void* arg);
    void* arg;
    struct task_node* next;
} task_node_t;

typedef struct {
    task_node_t* head;
    task_node_t* tail;
    pthread_mutex_t mutex;
} task_queue_t;

static void tq_init(task_queue_t* q) {
    q->head = q->tail = NULL;
    pthread_mutex_init(&q->mutex, NULL);
}

static void tq_destroy(task_queue_t* q) {
    pthread_mutex_lock(&q->mutex);
    task_node_t* n = q->head;
    while (n) { task_node_t* next = n->next; free(n); n = next; }
    q->head = q->tail = NULL;
    pthread_mutex_unlock(&q->mutex);
    pthread_mutex_destroy(&q->mutex);
}

static void tq_push(task_queue_t* q, void (*func)(void*), void* arg) {
    task_node_t* n = (task_node_t*)malloc(sizeof(task_node_t));
    n->func = func;
    n->arg = arg;
    n->next = NULL;
    pthread_mutex_lock(&q->mutex);
    if (q->tail) { q->tail->next = n; q->tail = n; }
    else { q->head = q->tail = n; }
    pthread_mutex_unlock(&q->mutex);
}

static task_node_t* tq_pop(task_queue_t* q) {
    task_node_t* n = q->head;
    if (n) {
        q->head = n->next;
        if (!q->head) q->tail = NULL;
    }
    return n;
}

/** Synchronous cross-thread call context */
typedef struct {
    void (*func)(void* ctx);
    void* ctx;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    bool done;
} sync_call_t;

static void sync_call_wrapper(void* arg) {
    sync_call_t* sc = (sync_call_t*)arg;
    sc->func(sc->ctx);
    pthread_mutex_lock(&sc->mutex);
    sc->done = true;
    pthread_cond_signal(&sc->cond);
    pthread_mutex_unlock(&sc->mutex);
}

/* ==================== Global State ==================== */

static sd_bus* g_bus = NULL;
static pthread_t g_worker_thread;
static atomic_bool g_worker_running;
static atomic_bool g_initialized;
static atomic_bool g_connected;
static atomic_bool g_notifications_active;
static atomic_bool g_cancel_connect_requested;
static atomic_int g_profile;

static task_queue_t g_tasks;
static int g_wake_pipe[2] = {-1, -1};

static char g_adapter_path[MAX_PATH];
static char g_device_path[MAX_PATH];

static int g_to_radio_fd = -1;
static int g_from_radio_fd = -1;
static uint16_t g_to_radio_mtu = 0;
static uint16_t g_from_radio_mtu = 0;
static atomic_bool g_use_dbus_write;  /* true = WriteValue fallback, false = fd-based */
static atomic_bool g_use_dbus_read;   /* true = ReadValue fallback, false = fd-based */

static char g_from_radio_char_path[MAX_PATH];
static char g_to_radio_char_path[MAX_PATH];
static char g_from_num_char_path[MAX_PATH];

static meshble_device_cb g_device_callback = NULL;
static meshble_data_cb g_data_callback = NULL;
static meshble_state_cb g_state_callback = NULL;

static sd_bus_slot* g_iface_added_slot = NULL;
static sd_bus_slot* g_props_changed_slot = NULL;
static sd_bus_slot* g_from_radio_notify_slot = NULL;
static sd_bus_slot* g_from_num_notify_slot = NULL;

/* Pairing agent */
static meshble_passkey_request_cb g_passkey_callback = NULL;
static sd_bus_message* g_pending_passkey_msg = NULL;
static sd_bus_slot* g_agent_slot = NULL;

static int active_profile(void) {
    int profile = atomic_load(&g_profile);
    return profile == PROFILE_MESHCORE ? PROFILE_MESHCORE : PROFILE_MESHTASTIC;
}

static const char* active_service_uuid(void) {
    return active_profile() == PROFILE_MESHCORE ? MESHCORE_SERVICE_UUID : SERVICE_UUID;
}

static const char* active_inbound_uuid(void) {
    return active_profile() == PROFILE_MESHCORE ? MESHCORE_TX_UUID : FROM_RADIO_UUID;
}

static const char* active_outbound_uuid(void) {
    return active_profile() == PROFILE_MESHCORE ? MESHCORE_RX_UUID : TO_RADIO_UUID;
}

static const char* active_notify_trigger_uuid(void) {
    return active_profile() == PROFILE_MESHCORE ? NULL : FROM_NUM_UUID;
}

static void process_tasks(void);

/* ==================== Wake + Dispatch ==================== */

static void wake_worker(void) {
    uint8_t b = 1;
    if (write(g_wake_pipe[1], &b, 1) < 0) { /* ignore */ }
}

/** Post task to worker thread, block until done */
static void run_on_worker(void (*func)(void* ctx), void* ctx) {
    sync_call_t sc;
    sc.func = func;
    sc.ctx = ctx;
    sc.done = false;
    pthread_mutex_init(&sc.mutex, NULL);
    pthread_cond_init(&sc.cond, NULL);

    tq_push(&g_tasks, sync_call_wrapper, &sc);
    wake_worker();

    pthread_mutex_lock(&sc.mutex);
    while (!sc.done) pthread_cond_wait(&sc.cond, &sc.mutex);
    pthread_mutex_unlock(&sc.mutex);

    pthread_mutex_destroy(&sc.mutex);
    pthread_cond_destroy(&sc.cond);
}

/* ==================== Helpers ==================== */

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wformat-truncation"
static void make_device_path(const char* adapter, const char* address, char* out, size_t outsize) {
    char addr_underscored[18];
    strncpy(addr_underscored, address, sizeof(addr_underscored) - 1);
    addr_underscored[17] = '\0';
    for (int i = 0; addr_underscored[i]; i++)
        if (addr_underscored[i] == ':') addr_underscored[i] = '_';
    int n = snprintf(out, outsize, "%s/dev_%s", adapter, addr_underscored);
    if (n < 0 || (size_t)n >= outsize) out[0] = '\0';
}
#pragma GCC diagnostic pop

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static bool contains_text(const char* haystack, const char* needle) {
    return haystack && needle && strstr(haystack, needle) != NULL;
}

static int map_bluez_connect_error(const char* name, const char* message) {
    if (contains_text(name, "UnknownObject") || contains_text(name, "DoesNotExist")) {
        return -2;
    }
    if (contains_text(name, "AlreadyExists") || contains_text(name, "AlreadyPaired") ||
        contains_text(name, "AlreadyConnected") ||
        contains_text(message, "Already connected") ||
        contains_text(message, "already connected") ||
        contains_text(message, "Already paired") ||
        contains_text(message, "already paired")) {
        return 0;
    }
    if (contains_text(name, "Authentication") || contains_text(name, "NotAuthorized") ||
        contains_text(name, "NotPermitted") || contains_text(name, "Rejected") ||
        contains_text(name, "Canceled") || contains_text(name, "Cancelled") ||
        contains_text(message, "Authentication")) {
        return -4;
    }
    if (contains_text(name, "Timeout") || contains_text(message, "timed out")) {
        return -1;
    }
    return -3;
}

static int map_bluez_write_error(const char* name, const char* message) {
    if (contains_text(name, "Authentication") || contains_text(name, "NotAuthorized") ||
        contains_text(name, "NotPermitted") || contains_text(name, "AccessDenied") ||
        contains_text(name, "Rejected") || contains_text(name, "Canceled") ||
        contains_text(name, "Cancelled") || contains_text(name, "NotPaired") ||
        contains_text(message, "Insufficient Authentication") ||
        contains_text(message, "ATT error: 0x05") ||
        contains_text(message, "Authentication") ||
        contains_text(message, "authentication") ||
        contains_text(message, "Not paired") ||
        contains_text(message, "not paired") ||
        contains_text(message, "encrypt") ||
        contains_text(message, "bond")) {
        return -4;
    }
    if (contains_text(name, "Timeout") || contains_text(name, "NoReply") ||
        contains_text(message, "timed out") || contains_text(message, "timeout")) {
        return -1;
    }
    return -1;
}

/* ==================== BlueZ D-Bus Helpers (worker thread only) ==================== */

static int find_adapter(sd_bus* bus, char* out, int outsize) {
    sd_bus_message* reply = NULL;
    int r = sd_bus_call_method(bus, BLUEZ_BUS, "/",
                               OBJMGR_IFACE, "GetManagedObjects",
                               NULL, &reply, "");
    if (r < 0) return r;

    r = sd_bus_message_enter_container(reply, 'a', "{oa{sa{sv}}}");
    if (r < 0) goto done;

    while (sd_bus_message_enter_container(reply, 'e', "oa{sa{sv}}") > 0) {
        const char* path = NULL;
        sd_bus_message_read(reply, "o", &path);

        sd_bus_message_enter_container(reply, 'a', "{sa{sv}}");
        while (sd_bus_message_enter_container(reply, 'e', "sa{sv}") > 0) {
            const char* iface = NULL;
            sd_bus_message_read(reply, "s", &iface);
            sd_bus_message_skip(reply, "a{sv}");
            sd_bus_message_exit_container(reply);

            if (iface && strcmp(iface, ADAPTER_IFACE) == 0 && path) {
                strncpy(out, path, outsize - 1);
                out[outsize - 1] = '\0';
                sd_bus_message_unref(reply);
                return 0;
            }
        }
        sd_bus_message_exit_container(reply);
        sd_bus_message_exit_container(reply);
    }
    r = -ENODEV;
done:
    sd_bus_message_unref(reply);
    return r;
}

static int get_bool_prop(sd_bus* bus, const char* path, const char* iface,
                         const char* prop, int* out) {
    sd_bus_message* reply = NULL;
    int r = sd_bus_call_method(bus, BLUEZ_BUS, path,
                               PROPS_IFACE, "Get",
                               NULL, &reply, "ss", iface, prop);
    if (r < 0) return r;
    int val = 0;
    r = sd_bus_message_read(reply, "v", "b", &val);
    if (r >= 0 && out) *out = val;
    sd_bus_message_unref(reply);
    return r;
}

static int set_bool_prop(sd_bus* bus, const char* path, const char* iface,
                         const char* prop, int val) {
    return sd_bus_call_method(bus, BLUEZ_BUS, path,
                              PROPS_IFACE, "Set",
                              NULL, NULL, "ssv", iface, prop, "b", val);
}

/* ==================== GATT Characteristic Discovery ==================== */

static int find_gatt_characteristics(sd_bus* bus, const char* device_path) {
    g_from_radio_char_path[0] = '\0';
    g_to_radio_char_path[0] = '\0';
    g_from_num_char_path[0] = '\0';

    sd_bus_message* reply = NULL;
    int r = sd_bus_call_method(bus, BLUEZ_BUS, "/",
                               OBJMGR_IFACE, "GetManagedObjects",
                               NULL, &reply, "");
    if (r < 0) {
        log_msg("[meshble] GetManagedObjects failed: %s", strerror(-r));
        return r;
    }

    r = sd_bus_message_enter_container(reply, 'a', "{oa{sa{sv}}}");
    if (r < 0) goto done;

    int dev_objects = 0;
    while (sd_bus_message_enter_container(reply, 'e', "oa{sa{sv}}") > 0) {
        const char* path = NULL;
        sd_bus_message_read(reply, "o", &path);

        if (!path || strncmp(path, device_path, strlen(device_path)) != 0) {
            sd_bus_message_skip(reply, "a{sa{sv}}");
            sd_bus_message_exit_container(reply);
            continue;
        }

        dev_objects++;
        /* Log every object under device path for diagnostics */
        if (strcmp(path, device_path) != 0) {
            log_msg("[meshble] D-Bus object: %s", path);
        }

        sd_bus_message_enter_container(reply, 'a', "{sa{sv}}");
        while (sd_bus_message_enter_container(reply, 'e', "sa{sv}") > 0) {
            const char* iface = NULL;
            sd_bus_message_read(reply, "s", &iface);

            if (iface && strcmp(iface, CHAR_IFACE) == 0) {
                sd_bus_message_enter_container(reply, 'a', "{sv}");
                while (sd_bus_message_enter_container(reply, 'e', "sv") > 0) {
                    const char* pname = NULL;
                    sd_bus_message_read(reply, "s", &pname);
                    if (pname && strcmp(pname, "UUID") == 0) {
                        const char* uuid = NULL;
                        sd_bus_message_read(reply, "v", "s", &uuid);
                        if (uuid) {
                            const char* inbound_uuid = active_inbound_uuid();
                            const char* outbound_uuid = active_outbound_uuid();
                            const char* trigger_uuid = active_notify_trigger_uuid();
                            if (strcasecmp(uuid, inbound_uuid) == 0) {
                                strncpy(g_from_radio_char_path, path, MAX_PATH - 1);
                                log_msg("[meshble] inbound: %s", path);
                            } else if (strcasecmp(uuid, outbound_uuid) == 0) {
                                strncpy(g_to_radio_char_path, path, MAX_PATH - 1);
                                log_msg("[meshble] outbound: %s", path);
                            } else if (trigger_uuid && strcasecmp(uuid, trigger_uuid) == 0) {
                                strncpy(g_from_num_char_path, path, MAX_PATH - 1);
                                log_msg("[meshble] notify trigger: %s", path);
                            }
                        }
                    } else {
                        sd_bus_message_skip(reply, "v");
                    }
                    sd_bus_message_exit_container(reply);
                }
                sd_bus_message_exit_container(reply);
            } else {
                sd_bus_message_skip(reply, "a{sv}");
            }
            sd_bus_message_exit_container(reply);
        }
        sd_bus_message_exit_container(reply);
        sd_bus_message_exit_container(reply);
    }

done:
    sd_bus_message_unref(reply);
    if (dev_objects <= 1) {
        log_msg("[meshble] No GATT objects under %s (only device itself)", device_path);
    }
    return (g_from_radio_char_path[0] && g_to_radio_char_path[0]) ? 0 : -ENOENT;
}

/* ==================== AcquireWrite / AcquireNotify ==================== */

static int acquire_write(sd_bus* bus, const char* char_path, uint16_t* mtu_out) {
    sd_bus_message* reply = NULL;
    sd_bus_error error = SD_BUS_ERROR_NULL;
    int fd = -1;

    int r = sd_bus_call_method(bus, BLUEZ_BUS, char_path,
                               CHAR_IFACE, "AcquireWrite",
                               &error, &reply, "a{sv}", 0);
    if (r < 0) {
        log_msg("[meshble] AcquireWrite failed: %s (%s)", error.message, error.name);
        sd_bus_error_free(&error);
        return -1;
    }
    sd_bus_error_free(&error);

    uint16_t mtu = 0;
    r = sd_bus_message_read(reply, "hq", &fd, &mtu);
    if (r < 0) { sd_bus_message_unref(reply); return -1; }

    int real_fd = fcntl(fd, F_DUPFD_CLOEXEC, 3);
    sd_bus_message_unref(reply);
    if (real_fd < 0) return -1;
    if (mtu_out) *mtu_out = mtu;
    log_msg("[meshble] AcquireWrite fd=%d mtu=%d", real_fd, mtu);
    return real_fd;
}

static int acquire_notify(sd_bus* bus, const char* char_path, uint16_t* mtu_out) {
    sd_bus_message* reply = NULL;
    sd_bus_error error = SD_BUS_ERROR_NULL;
    int fd = -1;

    int r = sd_bus_call_method(bus, BLUEZ_BUS, char_path,
                               CHAR_IFACE, "AcquireNotify",
                               &error, &reply, "a{sv}", 0);
    if (r < 0) {
        log_msg("[meshble] AcquireNotify failed: %s (%s)", error.message, error.name);
        sd_bus_error_free(&error);
        return -1;
    }
    sd_bus_error_free(&error);

    uint16_t mtu = 0;
    r = sd_bus_message_read(reply, "hq", &fd, &mtu);
    if (r < 0) { sd_bus_message_unref(reply); return -1; }

    int real_fd = fcntl(fd, F_DUPFD_CLOEXEC, 3);
    sd_bus_message_unref(reply);
    if (real_fd < 0) return -1;
    if (mtu_out) *mtu_out = mtu;
    log_msg("[meshble] AcquireNotify fd=%d mtu=%d", real_fd, mtu);
    return real_fd;
}

static int start_notify(sd_bus* bus, const char* char_path) {
    sd_bus_error error = SD_BUS_ERROR_NULL;
    int r = sd_bus_call_method(bus, BLUEZ_BUS, char_path,
                               CHAR_IFACE, "StartNotify",
                               &error, NULL, "");
    if (r < 0) {
        log_msg("[meshble] StartNotify failed: %s (%s)", error.message, error.name);
        sd_bus_error_free(&error);
        return r;
    }
    sd_bus_error_free(&error);
    return 0;
}

/* ==================== WriteValue / ReadValue (D-Bus fallback) ==================== */

typedef struct {
    int done;
    int result;
    int length;
    char error_name[128];
    char error_msg[256];
} write_reply_ctx_t;

static int on_write_value_reply(sd_bus_message* msg, void* userdata, sd_bus_error* ret_error) {
    (void)ret_error;
    write_reply_ctx_t* ctx = (write_reply_ctx_t*)userdata;
    if (!ctx) return 0;

    if (!msg) {
        ctx->result = -1;
        ctx->done = 1;
        return 1;
    }

    if (sd_bus_message_is_method_error(msg, NULL) > 0) {
        const sd_bus_error* error = sd_bus_message_get_error(msg);
        const char* name = (error && error->name) ? error->name : "unknown";
        const char* message = (error && error->message) ? error->message : "";
        snprintf(ctx->error_name, sizeof(ctx->error_name), "%s", name);
        snprintf(ctx->error_msg, sizeof(ctx->error_msg), "%s", message);
        ctx->result = map_bluez_write_error(ctx->error_name, ctx->error_msg);
        log_msg("[meshble] WriteValue failed: %s (%s)",
                ctx->error_msg[0] ? ctx->error_msg : "?", ctx->error_name);
    } else {
        ctx->result = 0;
        log_msg("[meshble] WriteValue OK (%d bytes)", ctx->length);
    }

    ctx->done = 1;
    return 1;
}

/** Single WriteValue attempt via D-Bus */
static int dbus_write_value_once(sd_bus* bus, const char* char_path,
                                  const unsigned char* data, int length,
                                  int* out_in_progress) {
    *out_in_progress = 0;
    sd_bus_message* m = NULL;
    int r = sd_bus_message_new_method_call(bus, &m, BLUEZ_BUS, char_path,
                                            CHAR_IFACE, "WriteValue");
    if (r < 0) return r;

    r = sd_bus_message_append_array(m, 'y', data, length);
    if (r < 0) { sd_bus_message_unref(m); return r; }

    /* Options dict: {"type": "request"} for write-with-response */
    r = sd_bus_message_open_container(m, 'a', "{sv}");
    if (r < 0) { sd_bus_message_unref(m); return r; }
    sd_bus_message_open_container(m, 'e', "sv");
    sd_bus_message_append(m, "s", "type");
    sd_bus_message_open_container(m, 'v', "s");
    sd_bus_message_append(m, "s", "request");
    sd_bus_message_close_container(m);
    sd_bus_message_close_container(m);
    sd_bus_message_close_container(m);

    write_reply_ctx_t write_reply = { .done = 0, .result = -1, .length = length };
    sd_bus_slot* write_slot = NULL;
    r = sd_bus_call_async(bus, &write_slot, m,
                          on_write_value_reply, &write_reply,
                          (uint64_t)WRITE_VALUE_TIMEOUT_MS * 1000);
    sd_bus_message_unref(m);
    if (r < 0) {
        log_msg("[meshble] WriteValue async call failed: %s", strerror(-r));
        return -1;
    }

    int64_t deadline = now_ms() + WRITE_VALUE_TIMEOUT_MS;
    while (now_ms() < deadline) {
        for (;;) {
            r = sd_bus_process(bus, NULL);
            if (r <= 0) break;
        }
        process_tasks();

        if (write_reply.done) {
            if (write_slot) sd_bus_slot_unref(write_slot);
            if (contains_text(write_reply.error_name, "InProgress")) {
                *out_in_progress = 1;
            }
            return write_reply.result;
        }

        if (!atomic_load(&g_worker_running) || !atomic_load(&g_connected)) {
            log_msg("[meshble] WriteValue cancelled");
            if (write_slot) sd_bus_slot_unref(write_slot);
            return -1;
        }

        int64_t remaining = deadline - now_ms();
        int timeout = remaining > POLL_TIMEOUT_MS ? POLL_TIMEOUT_MS : (int)remaining;
        if (timeout < 1) timeout = 1;

        int fd = sd_bus_get_fd(bus);
        if (fd >= 0) {
            struct pollfd pfd = { .fd = fd, .events = sd_bus_get_events(bus) };
            poll(&pfd, 1, timeout);
        } else {
            struct timespec ts = { .tv_sec = 0, .tv_nsec = (long)timeout * 1000000 };
            nanosleep(&ts, NULL);
        }
    }

    if (write_slot) sd_bus_slot_unref(write_slot);
    log_msg("[meshble] WriteValue timed out waiting for BlueZ reply");
    return -1;
}

/** Write data via D-Bus WriteValue with retry on InProgress (Bleak-style) */
static int dbus_write_value(sd_bus* bus, const char* char_path,
                             const unsigned char* data, int length) {
    for (int attempt = 0; attempt < 10; attempt++) {
        int in_progress = 0;
        int r = dbus_write_value_once(bus, char_path, data, length, &in_progress);
        if (r >= 0) return 0;
        if (!in_progress) return r; /* real error, not InProgress */
        /* InProgress — retry after 50ms (like Bleak does with 10ms) */
        struct timespec ts = { .tv_sec = 0, .tv_nsec = 50000000 };
        nanosleep(&ts, NULL);
    }
    log_msg("[meshble] WriteValue gave up after 10 InProgress retries");
    return -1;
}

/** Single ReadValue attempt via D-Bus */
static int dbus_read_value_once(sd_bus* bus, const char* char_path,
                                 unsigned char* buffer, int buf_size, int* out_len) {
    *out_len = 0;

    sd_bus_message* m = NULL;
    int r = sd_bus_message_new_method_call(bus, &m, BLUEZ_BUS, char_path,
                                            CHAR_IFACE, "ReadValue");
    if (r < 0) return r;

    /* Empty options dict */
    sd_bus_message_open_container(m, 'a', "{sv}");
    sd_bus_message_close_container(m);

    sd_bus_message* reply = NULL;
    sd_bus_error error = SD_BUS_ERROR_NULL;
    r = sd_bus_call(bus, m, 1000000, &error, &reply); /* 1s timeout */
    sd_bus_message_unref(m);
    if (r < 0) {
        int is_in_progress = (error.name && strstr(error.name, "InProgress"));
        if (!is_in_progress) {
            log_msg("[meshble] ReadValue failed: %s (%s)", error.message, error.name);
        }
        sd_bus_error_free(&error);
        return is_in_progress ? -2 : -1;
    }
    sd_bus_error_free(&error);

    const void* data = NULL;
    size_t data_len = 0;
    r = sd_bus_message_read_array(reply, 'y', &data, &data_len);
    if (r >= 0 && data_len > 0) {
        int copy = (int)data_len < buf_size ? (int)data_len : buf_size;
        memcpy(buffer, data, copy);
        *out_len = copy;
        log_msg("[meshble] ReadValue OK: %d bytes", copy);
    }
    sd_bus_message_unref(reply);
    return 0;
}

/** Read data via D-Bus ReadValue with retry on InProgress */
static int dbus_read_value(sd_bus* bus, const char* char_path,
                            unsigned char* buffer, int buf_size, int* out_len) {
    *out_len = 0;
    int r = dbus_read_value_once(bus, char_path, buffer, buf_size, out_len);
    if (r != -2) return r; /* not InProgress — return immediately */

    /* InProgress — retry a few times */
    for (int attempt = 1; attempt < 5; attempt++) {
        struct timespec ts = { .tv_sec = 0, .tv_nsec = 50000000 };
        nanosleep(&ts, NULL);
        r = dbus_read_value_once(bus, char_path, buffer, buf_size, out_len);
        if (r != -2) return r;
    }
    log_msg("[meshble] ReadValue InProgress after retries");
    return -1;
}

static int drain_from_radio_via_dbus(sd_bus* bus) {
    if (!bus || !g_from_radio_char_path[0]) return -1;

    unsigned char data[512];
    int result = 0;
    for (int i = 0; i < MAX_DRAIN; i++) {
        int out_len = 0;
        int r = dbus_read_value(bus, g_from_radio_char_path,
                                data, sizeof(data), &out_len);
        if (r != 0) {
            result = r;
            break;
        }
        if (out_len <= 0) break;

        log_msg("[meshble] fromRadio ReadValue after trigger: %d bytes", out_len);
        meshble_data_cb cb = g_data_callback;
        if (cb) cb(data, out_len);
    }
    return result;
}

/* ==================== Signal Handlers (worker thread) ==================== */

static int on_interfaces_added(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    meshble_device_cb cb = g_device_callback;
    if (!cb) return 0;

    const char* path = NULL;
    if (sd_bus_message_read(msg, "o", &path) < 0) return 0;

    if (sd_bus_message_enter_container(msg, 'a', "{sa{sv}}") < 0) return 0;

    while (sd_bus_message_enter_container(msg, 'e', "sa{sv}") > 0) {
        const char* iface = NULL;
        sd_bus_message_read(msg, "s", &iface);

        if (iface && strcmp(iface, DEVICE_IFACE) == 0) {
            char address[32] = {0};
            char name[128] = {0};
            int16_t rssi = -100;

            sd_bus_message_enter_container(msg, 'a', "{sv}");
            while (sd_bus_message_enter_container(msg, 'e', "sv") > 0) {
                const char* pname = NULL;
                sd_bus_message_read(msg, "s", &pname);
                if (pname && strcmp(pname, "Address") == 0) {
                    const char* val = NULL;
                    sd_bus_message_read(msg, "v", "s", &val);
                    if (val) strncpy(address, val, sizeof(address) - 1);
                } else if (pname && strcmp(pname, "Name") == 0) {
                    const char* val = NULL;
                    sd_bus_message_read(msg, "v", "s", &val);
                    if (val) strncpy(name, val, sizeof(name) - 1);
                } else if (pname && strcmp(pname, "RSSI") == 0) {
                    sd_bus_message_read(msg, "v", "n", &rssi);
                } else {
                    sd_bus_message_skip(msg, "v");
                }
                sd_bus_message_exit_container(msg);
            }
            sd_bus_message_exit_container(msg);
            if (address[0]) cb(address, name[0] ? name : NULL, rssi);
        } else {
            sd_bus_message_skip(msg, "a{sv}");
        }
        sd_bus_message_exit_container(msg);
    }
    return 0;
}

typedef struct {
    int services_resolved;
    int disconnected_seen;
} device_props_ctx_t;

static int on_device_props_changed(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)err;
    const char* iface = NULL;
    sd_bus_message_read(msg, "s", &iface);
    if (!iface || strcmp(iface, DEVICE_IFACE) != 0) return 0;
    device_props_ctx_t* props = (device_props_ctx_t*)userdata;

    sd_bus_message_enter_container(msg, 'a', "{sv}");
    while (sd_bus_message_enter_container(msg, 'e', "sv") > 0) {
        const char* pname = NULL;
        sd_bus_message_read(msg, "s", &pname);

        if (pname && strcmp(pname, "Connected") == 0) {
            int val = 0;
            sd_bus_message_read(msg, "v", "b", &val);
            log_msg("[meshble] Device Connected=%d", val);
            if (!val && props) {
                props->disconnected_seen = 1;
            }
            if (!val && atomic_load(&g_connected)) {
                atomic_store(&g_connected, false);
                atomic_store(&g_notifications_active, false);
                if (g_from_radio_fd >= 0) { close(g_from_radio_fd); g_from_radio_fd = -1; }
                if (g_to_radio_fd >= 0) { close(g_to_radio_fd); g_to_radio_fd = -1; }
                log_msg("[meshble] Unexpected disconnect");
                if (g_state_callback) g_state_callback(1, NULL);
            }
        } else if (pname && strcmp(pname, "ServicesResolved") == 0) {
            int val = 0;
            sd_bus_message_read(msg, "v", "b", &val);
            log_msg("[meshble] ServicesResolved=%d", val);
            if (val && props) {
                props->services_resolved = 1;
            }
        } else {
            sd_bus_message_skip(msg, "v");
        }
        sd_bus_message_exit_container(msg);
    }
    return 0;
}

/** PropertiesChanged handler for fromRadio characteristic — receives GATT notification data */
static int on_from_radio_changed(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    const char* iface = NULL;
    sd_bus_message_read(msg, "s", &iface);
    if (!iface || strcmp(iface, CHAR_IFACE) != 0) return 0;

    sd_bus_message_enter_container(msg, 'a', "{sv}");
    while (sd_bus_message_enter_container(msg, 'e', "sv") > 0) {
        const char* pname = NULL;
        sd_bus_message_read(msg, "s", &pname);

        if (pname && strcmp(pname, "Value") == 0) {
            sd_bus_message_enter_container(msg, 'v', "ay");
            const void* data = NULL;
            size_t data_len = 0;
            if (sd_bus_message_read_array(msg, 'y', &data, &data_len) >= 0 && data_len > 0) {
                log_msg("[meshble] fromRadio notify: %d bytes", (int)data_len);
                meshble_data_cb cb = g_data_callback;
                if (cb) cb((const unsigned char*)data, (int)data_len);
            }
            sd_bus_message_exit_container(msg);
        } else {
            sd_bus_message_skip(msg, "v");
        }
        sd_bus_message_exit_container(msg);
    }
    return 0;
}

/** PropertiesChanged handler for Meshtastic fromNum characteristic.
 *  fromNum is a notification trigger; the actual protobuf is read from fromRadio. */
static int on_from_num_changed(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    const char* iface = NULL;
    sd_bus_message_read(msg, "s", &iface);
    if (!iface || strcmp(iface, CHAR_IFACE) != 0) return 0;

    int triggered = 0;
    sd_bus_message_enter_container(msg, 'a', "{sv}");
    while (sd_bus_message_enter_container(msg, 'e', "sv") > 0) {
        const char* pname = NULL;
        sd_bus_message_read(msg, "s", &pname);

        if (pname && strcmp(pname, "Value") == 0) {
            sd_bus_message_enter_container(msg, 'v', "ay");
            const void* data = NULL;
            size_t data_len = 0;
            if (sd_bus_message_read_array(msg, 'y', &data, &data_len) >= 0) {
                log_msg("[meshble] fromNum notify trigger: %d bytes", (int)data_len);
                triggered = 1;
            }
            sd_bus_message_exit_container(msg);
        } else {
            sd_bus_message_skip(msg, "v");
        }
        sd_bus_message_exit_container(msg);
    }

    if (triggered) {
        drain_from_radio_via_dbus(g_bus);
    }
    return 0;
}

/* ==================== Emit Cached Devices (worker thread) ==================== */

static void emit_cached_devices(sd_bus* bus) {
    meshble_device_cb cb = g_device_callback;
    if (!cb) return;

    sd_bus_message* reply = NULL;
    int r = sd_bus_call_method(bus, BLUEZ_BUS, "/",
                               OBJMGR_IFACE, "GetManagedObjects",
                               NULL, &reply, "");
    if (r < 0) return;

    r = sd_bus_message_enter_container(reply, 'a', "{oa{sa{sv}}}");
    if (r < 0) { sd_bus_message_unref(reply); return; }

    while (sd_bus_message_enter_container(reply, 'e', "oa{sa{sv}}") > 0) {
        const char* path = NULL;
        sd_bus_message_read(reply, "o", &path);

        sd_bus_message_enter_container(reply, 'a', "{sa{sv}}");
        while (sd_bus_message_enter_container(reply, 'e', "sa{sv}") > 0) {
            const char* iface = NULL;
            sd_bus_message_read(reply, "s", &iface);

            if (iface && strcmp(iface, DEVICE_IFACE) == 0) {
                char address[32] = {0};
                char name[128] = {0};
                int16_t rssi_val = -100;

                sd_bus_message_enter_container(reply, 'a', "{sv}");
                while (sd_bus_message_enter_container(reply, 'e', "sv") > 0) {
                    const char* pname = NULL;
                    sd_bus_message_read(reply, "s", &pname);
                    if (pname && strcmp(pname, "Address") == 0) {
                        const char* val = NULL;
                        sd_bus_message_read(reply, "v", "s", &val);
                        if (val) strncpy(address, val, sizeof(address) - 1);
                    } else if (pname && strcmp(pname, "Name") == 0) {
                        const char* val = NULL;
                        sd_bus_message_read(reply, "v", "s", &val);
                        if (val) strncpy(name, val, sizeof(name) - 1);
                    } else if (pname && strcmp(pname, "RSSI") == 0) {
                        sd_bus_message_read(reply, "v", "n", &rssi_val);
                    } else {
                        sd_bus_message_skip(reply, "v");
                    }
                    sd_bus_message_exit_container(reply);
                }
                sd_bus_message_exit_container(reply);
                if (address[0]) cb(address, name[0] ? name : NULL, rssi_val);
            } else {
                sd_bus_message_skip(reply, "a{sv}");
            }
            sd_bus_message_exit_container(reply);
        }
        sd_bus_message_exit_container(reply);
        sd_bus_message_exit_container(reply);
    }
    sd_bus_message_unref(reply);
}

/* ==================== Worker Thread ==================== */

static void process_tasks(void) {
    pthread_mutex_lock(&g_tasks.mutex);
    while (g_tasks.head) {
        task_node_t* n = tq_pop(&g_tasks);
        pthread_mutex_unlock(&g_tasks.mutex);
        if (n) {
            n->func(n->arg);
            free(n);
        }
        pthread_mutex_lock(&g_tasks.mutex);
    }
    pthread_mutex_unlock(&g_tasks.mutex);
}

static void* worker_loop(void* arg) {
    (void)arg;
    log_msg("[meshble] Worker thread started");

    while (atomic_load(&g_worker_running)) {
        struct pollfd fds[3];
        int nfds = 0;

        int bus_fd = sd_bus_get_fd(g_bus);
        if (bus_fd >= 0) {
            fds[nfds].fd = bus_fd;
            fds[nfds].events = sd_bus_get_events(g_bus);
            nfds++;
        }

        fds[nfds].fd = g_wake_pipe[0];
        fds[nfds].events = POLLIN;
        int wake_idx = nfds;
        nfds++;

        int fr_fd = g_from_radio_fd;
        int fr_idx = -1;
        if (fr_fd >= 0 && atomic_load(&g_connected)) {
            fds[nfds].fd = fr_fd;
            fds[nfds].events = POLLIN;
            fr_idx = nfds;
            nfds++;
        }

        int timeout = POLL_TIMEOUT_MS;
        uint64_t bus_timeout;
        if (sd_bus_get_timeout(g_bus, &bus_timeout) >= 0 && bus_timeout != UINT64_MAX) {
            int bt = (int)(bus_timeout / 1000);
            if (bt < timeout) timeout = bt > 0 ? bt : 1;
        }

        int ret = poll(fds, nfds, timeout);
        if (ret < 0) {
            if (errno == EINTR) continue;
            break;
        }

        /* Process sd-bus events */
        for (;;) {
            int r = sd_bus_process(g_bus, NULL);
            if (r <= 0) break;
        }

        /* Process wake pipe — drain and execute tasks */
        if (fds[wake_idx].revents & POLLIN) {
            uint8_t buf[64];
            while (read(g_wake_pipe[0], buf, sizeof(buf)) > 0) {}
            process_tasks();
        }

        /* Also process tasks on every iteration (timeout-based wakeup) */
        process_tasks();

        /* Process fromRadio fd */
        if (fr_idx >= 0 && (fds[fr_idx].revents & POLLIN)) {
            unsigned char data[512];
            for (int i = 0; i < MAX_DRAIN; i++) {
                ssize_t n = read(g_from_radio_fd, data, sizeof(data));
                if (n <= 0) break;
                meshble_data_cb cb = g_data_callback;
                if (cb) cb(data, (int)n);
            }
        }
    }

    log_msg("[meshble] Worker thread exiting");
    return NULL;
}

/* ==================== Internal Operations (worker thread only) ==================== */

static void do_disconnect(void) {
    atomic_store(&g_connected, false);
    atomic_store(&g_notifications_active, false);
    atomic_store(&g_use_dbus_write, false);
    atomic_store(&g_use_dbus_read, false);

    if (g_pending_passkey_msg) {
        sd_bus_reply_method_errorf(g_pending_passkey_msg,
                                   "org.bluez.Error.Rejected",
                                   "Disconnected");
        sd_bus_message_unref(g_pending_passkey_msg);
        g_pending_passkey_msg = NULL;
    }

    if (g_from_radio_fd >= 0) { close(g_from_radio_fd); g_from_radio_fd = -1; }
    if (g_to_radio_fd >= 0) { close(g_to_radio_fd); g_to_radio_fd = -1; }

    if (g_from_radio_notify_slot) { sd_bus_slot_unref(g_from_radio_notify_slot); g_from_radio_notify_slot = NULL; }
    if (g_from_num_notify_slot) { sd_bus_slot_unref(g_from_num_notify_slot); g_from_num_notify_slot = NULL; }
    if (g_props_changed_slot) { sd_bus_slot_unref(g_props_changed_slot); g_props_changed_slot = NULL; }

    if (g_device_path[0] && g_bus) {
        sd_bus_call_method(g_bus, BLUEZ_BUS, g_device_path,
                           DEVICE_IFACE, "Disconnect", NULL, NULL, "");
    }

    g_device_path[0] = '\0';
    g_from_radio_char_path[0] = '\0';
    g_to_radio_char_path[0] = '\0';
    g_from_num_char_path[0] = '\0';
    g_to_radio_mtu = 0;
    g_from_radio_mtu = 0;
    log_msg("[meshble] Disconnected");
}

static void do_stop_scan(void) {
    if (g_iface_added_slot) { sd_bus_slot_unref(g_iface_added_slot); g_iface_added_slot = NULL; }
    if (g_bus && g_adapter_path[0]) {
        sd_bus_call_method(g_bus, BLUEZ_BUS, g_adapter_path,
                           ADAPTER_IFACE, "StopDiscovery", NULL, NULL, "");
    }
    g_device_callback = NULL;
    log_msg("[meshble] Scan stopped");
}

/* ==================== API — scan (runs on worker thread) ==================== */

typedef struct {
    meshble_device_cb callback;
    int result;
} scan_ctx_t;

static void do_start_scan(void* arg) {
    scan_ctx_t* ctx = (scan_ctx_t*)arg;

    do_stop_scan();
    g_device_callback = ctx->callback;

    /* SetDiscoveryFilter */
    sd_bus_message* m = NULL;
    int r = sd_bus_message_new_method_call(g_bus, &m, BLUEZ_BUS, g_adapter_path,
                                            ADAPTER_IFACE, "SetDiscoveryFilter");
    if (r >= 0) {
        sd_bus_message_open_container(m, 'a', "{sv}");
        sd_bus_message_open_container(m, 'e', "sv");
        sd_bus_message_append(m, "s", "UUIDs");
        sd_bus_message_open_container(m, 'v', "as");
        sd_bus_message_open_container(m, 'a', "s");
        sd_bus_message_append(m, "s", active_service_uuid());
        sd_bus_message_close_container(m);
        sd_bus_message_close_container(m);
        sd_bus_message_close_container(m);
        sd_bus_message_open_container(m, 'e', "sv");
        sd_bus_message_append(m, "s", "Transport");
        sd_bus_message_open_container(m, 'v', "s");
        sd_bus_message_append(m, "s", "le");
        sd_bus_message_close_container(m);
        sd_bus_message_close_container(m);
        sd_bus_message_close_container(m);

        sd_bus_error error = SD_BUS_ERROR_NULL;
        r = sd_bus_call(g_bus, m, 0, &error, NULL);
        if (r < 0) log_msg("[meshble] SetDiscoveryFilter: %s", error.message);
        sd_bus_error_free(&error);
        sd_bus_message_unref(m);
    }

    /* Subscribe to InterfacesAdded */
    r = sd_bus_match_signal(g_bus, &g_iface_added_slot,
                            BLUEZ_BUS, "/",
                            OBJMGR_IFACE, "InterfacesAdded",
                            on_interfaces_added, NULL);
    if (r < 0) log_msg("[meshble] InterfacesAdded subscribe failed: %s", strerror(-r));

    /* Emit cached devices */
    emit_cached_devices(g_bus);

    /* StartDiscovery */
    sd_bus_error error = SD_BUS_ERROR_NULL;
    r = sd_bus_call_method(g_bus, BLUEZ_BUS, g_adapter_path,
                           ADAPTER_IFACE, "StartDiscovery",
                           &error, NULL, "");
    if (r < 0) {
        log_msg("[meshble] StartDiscovery failed: %s", error.message);
        sd_bus_error_free(&error);
        if (g_iface_added_slot) { sd_bus_slot_unref(g_iface_added_slot); g_iface_added_slot = NULL; }
        g_device_callback = NULL;
        ctx->result = -1;
        return;
    }
    sd_bus_error_free(&error);
    log_msg("[meshble] Scan started");
    ctx->result = 0;
}

/* ==================== API — connect (runs on worker thread) ==================== */

typedef struct {
    char address[32];
    int timeout_ms;
    int result;
} connect_ctx_t;

typedef struct {
    int done;
    int result;
    char error_name[128];
    char error_msg[256];
} connect_reply_ctx_t;

static int on_connect_reply(sd_bus_message* msg, void* userdata, sd_bus_error* ret_error) {
    (void)ret_error;
    connect_reply_ctx_t* ctx = (connect_reply_ctx_t*)userdata;
    if (!ctx) return 0;

    if (sd_bus_message_is_method_error(msg, NULL) > 0) {
        const sd_bus_error* error = sd_bus_message_get_error(msg);
        const char* name = (error && error->name) ? error->name : "unknown";
        const char* message = (error && error->message) ? error->message : "";
        snprintf(ctx->error_name, sizeof(ctx->error_name), "%s", name);
        snprintf(ctx->error_msg, sizeof(ctx->error_msg), "%s", message);
        ctx->result = map_bluez_connect_error(ctx->error_name, ctx->error_msg);
        log_msg("[meshble] Connect reply error: %s (%s)", ctx->error_msg, ctx->error_name);
    } else {
        ctx->result = 0;
        log_msg("[meshble] Connect reply OK");
    }
    ctx->done = 1;
    return 1;
}

static int on_pair_reply(sd_bus_message* msg, void* userdata, sd_bus_error* ret_error) {
    (void)ret_error;
    connect_reply_ctx_t* ctx = (connect_reply_ctx_t*)userdata;
    if (!ctx) return 0;

    if (sd_bus_message_is_method_error(msg, NULL) > 0) {
        const sd_bus_error* error = sd_bus_message_get_error(msg);
        const char* name = (error && error->name) ? error->name : "unknown";
        const char* message = (error && error->message) ? error->message : "";
        snprintf(ctx->error_name, sizeof(ctx->error_name), "%s", name);
        snprintf(ctx->error_msg, sizeof(ctx->error_msg), "%s", message);
        ctx->result = map_bluez_connect_error(ctx->error_name, ctx->error_msg);
        if (ctx->result == 0) {
            log_msg("[meshble] Pair reply already paired: %s (%s)", ctx->error_msg, ctx->error_name);
        } else {
            log_msg("[meshble] Pair reply error: %s (%s)", ctx->error_msg, ctx->error_name);
        }
    } else {
        ctx->result = 0;
        log_msg("[meshble] Pair reply OK");
    }
    ctx->done = 1;
    return 1;
}

static int ensure_device_paired(sd_bus* bus, const char* device_path, int timeout_ms) {
    int paired = 0;
    if (get_bool_prop(bus, device_path, DEVICE_IFACE, "Paired", &paired) >= 0 && paired) {
        log_msg("[meshble] Device already paired");
        return 0;
    }

    int pair_timeout_ms = timeout_ms > PAIR_TIMEOUT_MS ? timeout_ms : PAIR_TIMEOUT_MS;
    log_msg("[meshble] Pairing required before Meshtastic GATT I/O (timeout=%dms)", pair_timeout_ms);

    connect_reply_ctx_t pair_reply = {0};
    sd_bus_message* pair_msg = NULL;
    int r = sd_bus_message_new_method_call(bus, &pair_msg, BLUEZ_BUS, device_path,
                                            DEVICE_IFACE, "Pair");
    if (r < 0) {
        log_msg("[meshble] Pair msg failed: %s", strerror(-r));
        return -3;
    }

    sd_bus_slot* pair_slot = NULL;
    r = sd_bus_call_async(bus, &pair_slot, pair_msg,
                          on_pair_reply, &pair_reply,
                          (uint64_t)pair_timeout_ms * 1000);
    sd_bus_message_unref(pair_msg);
    if (r < 0) {
        log_msg("[meshble] Pair call failed: %s", strerror(-r));
        return -3;
    }

    int64_t deadline = now_ms() + pair_timeout_ms;
    while (now_ms() < deadline) {
        if (atomic_load(&g_cancel_connect_requested)) {
            log_msg("[meshble] Pair cancelled");
            if (pair_slot) sd_bus_slot_unref(pair_slot);
            return -5;
        }

        for (;;) {
            r = sd_bus_process(bus, NULL);
            if (r <= 0) break;
        }
        process_tasks();

        if (pair_reply.done) {
            if (pair_slot) sd_bus_slot_unref(pair_slot);
            if (pair_reply.result == 0) {
                set_bool_prop(bus, device_path, DEVICE_IFACE, "Trusted", 1);
            }
            return pair_reply.result;
        }

        struct pollfd pfd = { .fd = sd_bus_get_fd(bus), .events = POLLIN };
        poll(&pfd, 1, 100);
    }

    log_msg("[meshble] Pair timeout");
    if (pair_slot) sd_bus_slot_unref(pair_slot);
    return -4;
}

static void do_connect(void* arg) {
    connect_ctx_t* ctx = (connect_ctx_t*)arg;
    int r;

    atomic_store(&g_cancel_connect_requested, false);
    do_disconnect();

    make_device_path(g_adapter_path, ctx->address, g_device_path, sizeof(g_device_path));
    log_msg("[meshble] Connecting to %s (%s)...", ctx->address, g_device_path);

    /* Subscribe to property changes for disconnect detection + ServicesResolved */
    device_props_ctx_t props = {0};
    sd_bus_match_signal(g_bus, &g_props_changed_slot,
                        BLUEZ_BUS, g_device_path,
                        PROPS_IFACE, "PropertiesChanged",
                        on_device_props_changed, &props);

    set_bool_prop(g_bus, g_device_path, DEVICE_IFACE, "Trusted", 1);

    const char* trigger_uuid = active_notify_trigger_uuid();
    if (trigger_uuid) {
        int pair_result = ensure_device_paired(g_bus, g_device_path, ctx->timeout_ms);
        if (pair_result < 0) {
            log_msg("[meshble] Pairing failed before Meshtastic connect: %d", pair_result);
            do_disconnect();
            ctx->result = pair_result;
            return;
        }

        props.services_resolved = 0;
        props.disconnected_seen = 0;
    }

    /* Connect via async sd_bus_call — allows agent RequestPasskey to be
       processed on the worker thread during the Connect call */
    connect_reply_ctx_t connect_reply = {0};

    sd_bus_message* conn_msg = NULL;
    r = sd_bus_message_new_method_call(g_bus, &conn_msg, BLUEZ_BUS, g_device_path,
                                        DEVICE_IFACE, "Connect");
    if (r < 0) {
        log_msg("[meshble] Connect msg failed: %s", strerror(-r));
        do_disconnect();
        ctx->result = -3;
        return;
    }

    sd_bus_slot* connect_slot = NULL;
    r = sd_bus_call_async(g_bus, &connect_slot, conn_msg,
                          on_connect_reply, &connect_reply,
                          (uint64_t)ctx->timeout_ms * 1000);
    sd_bus_message_unref(conn_msg);
    if (r < 0) {
        log_msg("[meshble] Connect call failed: %s", strerror(-r));
        do_disconnect();
        ctx->result = -3;
        return;
    }

    /* Poll-based async Connect: process bus events until we see Connected=1 or timeout */
    int64_t connect_deadline = now_ms() + ctx->timeout_ms;
    int connected_ok = 0;
    while (now_ms() < connect_deadline) {
        if (atomic_load(&g_cancel_connect_requested)) {
            log_msg("[meshble] Connect cancelled before Connected=1");
            if (connect_slot) sd_bus_slot_unref(connect_slot);
            do_disconnect();
            ctx->result = -5;
            return;
        }

        for (;;) {
            r = sd_bus_process(g_bus, NULL);
            if (r <= 0) break;
        }
        process_tasks();

        if (connect_reply.done && connect_reply.result < 0) {
            log_msg("[meshble] Connect failed before Connected=1: %s (%s)",
                    connect_reply.error_msg, connect_reply.error_name);
            if (connect_slot) sd_bus_slot_unref(connect_slot);
            do_disconnect();
            ctx->result = connect_reply.result;
            return;
        }

        /* Check if Connected property is set */
        int conn_val = 0;
        if (get_bool_prop(g_bus, g_device_path, DEVICE_IFACE, "Connected", &conn_val) >= 0 && conn_val) {
            connected_ok = 1;
            break;
        }

        struct pollfd pfd = { .fd = sd_bus_get_fd(g_bus), .events = POLLIN };
        poll(&pfd, 1, 200);
    }

    if (!connected_ok) {
        log_msg("[meshble] Connect timeout — device not connected");
        if (connect_slot) sd_bus_slot_unref(connect_slot);
        do_disconnect();
        ctx->result = -1;
        return;
    }
    log_msg("[meshble] Connect() — device connected");

    /* Wait for ServicesResolved — MUST be true before GATT is usable.
       Do NOT use find_gatt_characteristics() as early exit — cached BlueZ objects
       may exist from previous connections but are not usable until resolved. */
    int64_t deadline = now_ms() + ctx->timeout_ms;
    int resolved_ok = 0;
    int loop_count = 0;
    while (now_ms() < deadline) {
        if (atomic_load(&g_cancel_connect_requested)) {
            log_msg("[meshble] Connect cancelled while waiting for ServicesResolved");
            if (connect_slot) sd_bus_slot_unref(connect_slot);
            do_disconnect();
            ctx->result = -5;
            return;
        }

        /* Process all pending D-Bus events (signals from BlueZ) */
        for (;;) {
            r = sd_bus_process(g_bus, NULL);
            if (r <= 0) break;
        }
        process_tasks();

        if (connect_reply.done && connect_reply.result < 0) {
            log_msg("[meshble] Connect failed while waiting for ServicesResolved: %s (%s)",
                    connect_reply.error_msg, connect_reply.error_name);
            if (connect_slot) sd_bus_slot_unref(connect_slot);
            do_disconnect();
            ctx->result = connect_reply.result;
            return;
        }

        int conn_val = 0;
        int conn_rr = get_bool_prop(g_bus, g_device_path, DEVICE_IFACE, "Connected", &conn_val);
        if (conn_rr < 0) {
            log_msg("[meshble] Connected property unavailable while waiting for ServicesResolved: %s",
                    strerror(-conn_rr));
            if (connect_slot) sd_bus_slot_unref(connect_slot);
            do_disconnect();
            ctx->result = -1;
            return;
        }
        if (!conn_val) {
            log_msg("[meshble] Device disconnected before ServicesResolved");
            if (props.disconnected_seen) {
                log_msg("[meshble] Early disconnect signal observed during service discovery");
            }
            if (connect_slot) sd_bus_slot_unref(connect_slot);
            do_disconnect();
            ctx->result = -1;
            return;
        }

        /* Check signal flag */
        if (props.services_resolved) {
            log_msg("[meshble] ServicesResolved=true (signal)");
            resolved_ok = 1;
            break;
        }

        /* Poll property directly */
        int resolved = 0;
        int rr = get_bool_prop(g_bus, g_device_path, DEVICE_IFACE, "ServicesResolved", &resolved);
        if (loop_count % 5 == 0) {
            log_msg("[meshble] Poll #%d: ServicesResolved r=%d val=%d", loop_count, rr, resolved);
        }
        if (rr >= 0 && resolved) {
            log_msg("[meshble] ServicesResolved=true (polled)");
            resolved_ok = 1;
            break;
        }

        loop_count++;
        struct pollfd pfd = { .fd = sd_bus_get_fd(g_bus), .events = POLLIN };
        poll(&pfd, 1, 200);
    }

    if (!resolved_ok) {
        log_msg("[meshble] ServicesResolved timeout — GATT not ready");
        if (connect_slot) sd_bus_slot_unref(connect_slot);
        do_disconnect();
        ctx->result = -3;
        return;
    }

    /* Final check: connected? */
    int connected_check = 0;
    get_bool_prop(g_bus, g_device_path, DEVICE_IFACE, "Connected", &connected_check);
    if (!connected_check) {
        log_msg("[meshble] Device disconnected during service discovery");
        if (connect_slot) sd_bus_slot_unref(connect_slot);
        do_disconnect();
        ctx->result = -1;
        return;
    }

    /* Now find GATT characteristics — services are resolved, objects are valid */
    r = find_gatt_characteristics(g_bus, g_device_path);
    if (r < 0) {
        log_msg("[meshble] GATT characteristics not found");
        if (connect_slot) sd_bus_slot_unref(connect_slot);
        do_disconnect();
        ctx->result = -3;
        return;
    }

    /* toRadio: WriteValue with retry on InProgress */
    log_msg("[meshble] Using WriteValue for toRadio: %s", g_to_radio_char_path);
    atomic_store(&g_use_dbus_write, true);
    g_to_radio_fd = -1;

    if (trigger_uuid) {
        /* Meshtastic uses fromNum as notification trigger; payload is read from fromRadio. */
        int match_result = sd_bus_match_signal(g_bus, &g_from_num_notify_slot,
                            BLUEZ_BUS, g_from_num_char_path,
                            PROPS_IFACE, "PropertiesChanged",
                            on_from_num_changed, NULL);
        if (match_result >= 0 && start_notify(g_bus, g_from_num_char_path) >= 0) {
            log_msg("[meshble] Using fromNum notifications as fromRadio trigger: %s", g_from_num_char_path);
            atomic_store(&g_use_dbus_read, true);
            g_from_radio_fd = -1;
            atomic_store(&g_notifications_active, true);
            log_msg("[meshble] Connected (write=WriteValue, read=fromNum notify + ReadValue)");
        } else {
            if (g_from_num_notify_slot) {
                sd_bus_slot_unref(g_from_num_notify_slot);
                g_from_num_notify_slot = NULL;
            }
            log_msg("[meshble] fromNum notifications unavailable; falling back to ReadValue polling");
            atomic_store(&g_use_dbus_read, true);
            g_from_radio_fd = -1;
            atomic_store(&g_notifications_active, false);
            log_msg("[meshble] Connected (write=WriteValue, read=ReadValue polling)");
        }
    } else {
        /* MeshCore Companion TX: prefer notifications on the inbound characteristic. */
        sd_bus_match_signal(g_bus, &g_from_radio_notify_slot,
                            BLUEZ_BUS, g_from_radio_char_path,
                            PROPS_IFACE, "PropertiesChanged",
                            on_from_radio_changed, NULL);
        int fd = acquire_notify(g_bus, g_from_radio_char_path, &g_from_radio_mtu);
        if (fd >= 0) {
            g_from_radio_fd = fd;
            atomic_store(&g_use_dbus_read, false);
            atomic_store(&g_notifications_active, true);
            log_msg("[meshble] Connected (write=WriteValue, read=AcquireNotify)");
        } else if (start_notify(g_bus, g_from_radio_char_path) >= 0) {
            g_from_radio_fd = -1;
            atomic_store(&g_use_dbus_read, false);
            atomic_store(&g_notifications_active, true);
            log_msg("[meshble] Connected (write=WriteValue, read=StartNotify)");
        } else {
            log_msg("[meshble] Notifications unavailable; falling back to ReadValue");
            atomic_store(&g_use_dbus_read, true);
            g_from_radio_fd = -1;
            atomic_store(&g_notifications_active, false);
        }
    }

    atomic_store(&g_connected, true);

    /* Keep disconnect detection after connect() returns, but drop stack-local state. */
    if (g_props_changed_slot) { sd_bus_slot_unref(g_props_changed_slot); g_props_changed_slot = NULL; }
    r = sd_bus_match_signal(g_bus, &g_props_changed_slot,
                            BLUEZ_BUS, g_device_path,
                            PROPS_IFACE, "PropertiesChanged",
                            on_device_props_changed, NULL);
    if (r < 0) {
        log_msg("[meshble] PropertiesChanged resubscribe failed: %s", strerror(-r));
    }

    if (g_state_callback) g_state_callback(0, NULL);
    if (connect_slot) sd_bus_slot_unref(connect_slot);
    ctx->result = 0;
}

/* ==================== BlueZ Pairing Agent ==================== */

#define AGENT_PATH "/meshapp/agent"
#define AGENT_MGR_IFACE "org.bluez.AgentManager1"
#define AGENT_IFACE "org.bluez.Agent1"

static int agent_release(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    log_msg("[meshble] Agent: Release");
    return sd_bus_reply_method_return(msg, "");
}

/** Extract device address from D-Bus object path */
static void extract_address_from_path(const char* path, char* addr, int addr_size) {
    /* Path: /org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF → AA:BB:CC:DD:EE:FF */
    const char* dev = strstr(path, "dev_");
    if (dev) {
        dev += 4;
        int i = 0;
        while (*dev && i < addr_size - 1) {
            addr[i++] = (*dev == '_') ? ':' : *dev;
            dev++;
        }
        addr[i] = '\0';
    } else {
        strncpy(addr, path, addr_size - 1);
        addr[addr_size - 1] = '\0';
    }
}

/** BlueZ calls this when it needs a passkey for pairing.
 *  We save the message and notify Java — reply comes later via meshble_respond_passkey. */
static int agent_request_passkey(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    const char* device_path = NULL;
    sd_bus_message_read(msg, "o", &device_path);
    log_msg("[meshble] Agent: RequestPasskey for %s", device_path ? device_path : "?");

    if (!g_passkey_callback) {
        log_msg("[meshble] Agent: No passkey callback — rejecting");
        return sd_bus_reply_method_errorf(msg, "org.bluez.Error.Rejected", "No passkey handler");
    }

    /* Save the D-Bus message — we'll reply asynchronously */
    if (g_pending_passkey_msg) {
        sd_bus_reply_method_errorf(g_pending_passkey_msg,
                                   "org.bluez.Error.Rejected",
                                   "Superseded by a newer passkey request");
        sd_bus_message_unref(g_pending_passkey_msg);
    }
    g_pending_passkey_msg = sd_bus_message_ref(msg);

    /* Notify Java to show PIN dialog */
    char addr[32] = {0};
    if (device_path) extract_address_from_path(device_path, addr, sizeof(addr));
    g_passkey_callback(addr);

    return 1; /* positive = we'll reply later (async) */
}

static int agent_request_pincode(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    const char* device_path = NULL;
    sd_bus_message_read(msg, "o", &device_path);
    log_msg("[meshble] Agent: RequestPinCode for %s rejected (legacy PIN not supported)",
            device_path ? device_path : "?");
    return sd_bus_reply_method_errorf(msg, "org.bluez.Error.Rejected", "Legacy PIN code pairing is not supported");
}

static int agent_display_pincode(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    const char* device_path = NULL;
    const char* pincode = NULL;
    sd_bus_message_read(msg, "os", &device_path, &pincode);
    (void)pincode;
    log_msg("[meshble] Agent: DisplayPinCode for %s", device_path ? device_path : "?");
    return sd_bus_reply_method_return(msg, "");
}

static int agent_display_passkey(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    const char* device_path = NULL;
    uint32_t passkey = 0;
    uint16_t entered = 0;
    sd_bus_message_read(msg, "ouq", &device_path, &passkey, &entered);
    log_msg("[meshble] Agent: DisplayPasskey for %s passkey=%06u entered=%u",
            device_path ? device_path : "?", passkey, entered);
    return sd_bus_reply_method_return(msg, "");
}

static int agent_request_confirmation(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    const char* device_path = NULL;
    uint32_t passkey = 0;
    sd_bus_message_read(msg, "ou", &device_path, &passkey);
    log_msg("[meshble] Agent: RequestConfirmation for %s passkey=%06u (auto-accepted)",
            device_path ? device_path : "?", passkey);
    return sd_bus_reply_method_return(msg, "");
}

static int agent_auto_authorize(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    return sd_bus_reply_method_return(msg, "");
}

static int agent_cancel(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    log_msg("[meshble] Agent: Cancel");
    if (g_pending_passkey_msg) {
        sd_bus_message_unref(g_pending_passkey_msg);
        g_pending_passkey_msg = NULL;
    }
    return sd_bus_reply_method_return(msg, "");
}

static const sd_bus_vtable agent_vtable[] = {
    SD_BUS_VTABLE_START(0),
    SD_BUS_METHOD("Release", "", "", agent_release, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("RequestPinCode", "o", "s", agent_request_pincode, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("RequestPasskey", "o", "u", agent_request_passkey, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("DisplayPinCode", "os", "", agent_display_pincode, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("DisplayPasskey", "ouq", "", agent_display_passkey, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("RequestConfirmation", "ou", "", agent_request_confirmation, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("RequestAuthorization", "o", "", agent_auto_authorize, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("AuthorizeService", "os", "", agent_auto_authorize, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("Cancel", "", "", agent_cancel, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_VTABLE_END,
};

static int register_agent(sd_bus* bus) {
    int r = sd_bus_add_object_vtable(bus, &g_agent_slot, AGENT_PATH,
                                      AGENT_IFACE, agent_vtable, NULL);
    if (r < 0) {
        log_msg("[meshble] Failed to add agent vtable: %s", strerror(-r));
        return r;
    }

    const char* capability = "KeyboardDisplay";
    sd_bus_error error = SD_BUS_ERROR_NULL;
    r = sd_bus_call_method(bus, BLUEZ_BUS, "/org/bluez",
                            AGENT_MGR_IFACE, "RegisterAgent",
                            &error, NULL, "os", AGENT_PATH, capability);
    if (r < 0 && !(error.name && strstr(error.name, "AlreadyExists"))) {
        log_msg("[meshble] RegisterAgent(%s) failed: %s (%s); trying KeyboardOnly",
                capability,
                error.message ? error.message : "?",
                error.name ? error.name : "?");
        sd_bus_error_free(&error);
        capability = "KeyboardOnly";
        sd_bus_error fallback_error = SD_BUS_ERROR_NULL;
        r = sd_bus_call_method(bus, BLUEZ_BUS, "/org/bluez",
                                AGENT_MGR_IFACE, "RegisterAgent",
                                &fallback_error, NULL, "os", AGENT_PATH, capability);
        if (r < 0) {
            if (fallback_error.name && strstr(fallback_error.name, "AlreadyExists")) {
                log_msg("[meshble] Agent already registered");
                r = 0;
            } else {
                log_msg("[meshble] RegisterAgent failed: %s (%s)",
                        fallback_error.message ? fallback_error.message : "?",
                        fallback_error.name ? fallback_error.name : "?");
                sd_bus_error_free(&fallback_error);
                if (g_agent_slot) { sd_bus_slot_unref(g_agent_slot); g_agent_slot = NULL; }
                return r;
            }
        }
        sd_bus_error_free(&fallback_error);
    }
    if (r < 0) {
        if (error.name && strstr(error.name, "AlreadyExists")) {
            log_msg("[meshble] Agent already registered");
            r = 0;
        } else {
            log_msg("[meshble] RegisterAgent failed: %s (%s)",
                    error.message ? error.message : "?",
                    error.name ? error.name : "?");
            sd_bus_error_free(&error);
            if (g_agent_slot) { sd_bus_slot_unref(g_agent_slot); g_agent_slot = NULL; }
            return r;
        }
    }
    sd_bus_error_free(&error);

    sd_bus_error default_error = SD_BUS_ERROR_NULL;
    r = sd_bus_call_method(bus, BLUEZ_BUS, "/org/bluez",
                           AGENT_MGR_IFACE, "RequestDefaultAgent",
                           &default_error, NULL, "o", AGENT_PATH);
    if (r < 0) {
        log_msg("[meshble] RequestDefaultAgent failed: %s (%s)",
                default_error.message ? default_error.message : "?",
                default_error.name ? default_error.name : "?");
    }
    sd_bus_error_free(&default_error);

    log_msg("[meshble] Agent registered (%s)", capability);
    return 0;
}

static void unregister_agent(sd_bus* bus) {
    if (!bus) return;
    sd_bus_call_method(bus, BLUEZ_BUS, "/org/bluez",
                        AGENT_MGR_IFACE, "UnregisterAgent",
                        NULL, NULL, "o", AGENT_PATH);
    if (g_agent_slot) { sd_bus_slot_unref(g_agent_slot); g_agent_slot = NULL; }
    if (g_pending_passkey_msg) { sd_bus_message_unref(g_pending_passkey_msg); g_pending_passkey_msg = NULL; }
}

/* ==================== API — other worker-dispatched ops ==================== */

typedef struct { int result; } adapter_state_ctx_t;

static void do_get_adapter_state(void* arg) {
    adapter_state_ctx_t* ctx = (adapter_state_ctx_t*)arg;
    int powered = 0;
    int r = get_bool_prop(g_bus, g_adapter_path, ADAPTER_IFACE, "Powered", &powered);
    ctx->result = (r < 0) ? 0 : (powered ? 2 : 1);
}

static void do_stop_scan_wrapper(void* arg) {
    (void)arg;
    do_stop_scan();
}

static void do_disconnect_wrapper(void* arg) {
    (void)arg;
    do_disconnect();
}

/* ==================== Public API ==================== */

MESHBLE_API int meshble_init(void) {
    if (atomic_exchange(&g_initialized, true)) return 0;

    /* Open bus BEFORE worker starts — init is single-threaded */
    int r = sd_bus_open_system(&g_bus);
    if (r < 0) {
        log_msg("[meshble] Failed to open system bus: %s", strerror(-r));
        atomic_store(&g_initialized, false);
        return -1;
    }

    r = find_adapter(g_bus, g_adapter_path, sizeof(g_adapter_path));
    if (r < 0) {
        log_msg("[meshble] No BlueZ adapter found: %s", strerror(-r));
        sd_bus_unref(g_bus); g_bus = NULL;
        atomic_store(&g_initialized, false);
        return -1;
    }
    log_msg("[meshble] Adapter: %s", g_adapter_path);

    /* Register pairing agent before worker thread starts (bus not shared yet) */
    register_agent(g_bus);

    if (pipe(g_wake_pipe) < 0) {
        unregister_agent(g_bus);
        sd_bus_unref(g_bus); g_bus = NULL;
        atomic_store(&g_initialized, false);
        return -1;
    }
    fcntl(g_wake_pipe[0], F_SETFL, O_NONBLOCK);
    fcntl(g_wake_pipe[1], F_SETFL, O_NONBLOCK);

    tq_init(&g_tasks);
    atomic_store(&g_profile, PROFILE_MESHTASTIC);
    atomic_store(&g_worker_running, true);
    int thread_result = pthread_create(&g_worker_thread, NULL, worker_loop, NULL);
    if (thread_result != 0) {
        log_msg("[meshble] Failed to start worker thread: %s", strerror(thread_result));
        atomic_store(&g_worker_running, false);
        tq_destroy(&g_tasks);
        if (g_wake_pipe[0] >= 0) { close(g_wake_pipe[0]); g_wake_pipe[0] = -1; }
        if (g_wake_pipe[1] >= 0) { close(g_wake_pipe[1]); g_wake_pipe[1] = -1; }
        unregister_agent(g_bus);
        sd_bus_unref(g_bus); g_bus = NULL;
        atomic_store(&g_initialized, false);
        return -1;
    }

    log_msg("[meshble] Initialized (sd-bus worker thread)");
    return 0;
}

MESHBLE_API void meshble_cleanup(void) {
    if (!atomic_load(&g_initialized)) return;

    /* Dispatch cleanup to worker thread */
    run_on_worker(do_disconnect_wrapper, NULL);
    run_on_worker(do_stop_scan_wrapper, NULL);

    atomic_store(&g_worker_running, false);
    wake_worker();
    pthread_join(g_worker_thread, NULL);

    tq_destroy(&g_tasks);
    if (g_wake_pipe[0] >= 0) { close(g_wake_pipe[0]); g_wake_pipe[0] = -1; }
    if (g_wake_pipe[1] >= 0) { close(g_wake_pipe[1]); g_wake_pipe[1] = -1; }

    unregister_agent(g_bus);
    if (g_bus) { sd_bus_unref(g_bus); g_bus = NULL; }

    g_device_callback = NULL;
    g_data_callback = NULL;
    g_state_callback = NULL;
    g_passkey_callback = NULL;
    atomic_store(&g_initialized, false);
    log_msg("[meshble] Cleanup done");
}

MESHBLE_API int meshble_get_adapter_state(void) {
    if (!atomic_load(&g_initialized) || !g_bus) return 0;
    adapter_state_ctx_t ctx = { .result = 0 };
    run_on_worker(do_get_adapter_state, &ctx);
    return ctx.result;
}

MESHBLE_API void meshble_set_profile(int profile) {
    if (profile == PROFILE_MESHCORE) {
        atomic_store(&g_profile, profile);
    } else {
        atomic_store(&g_profile, PROFILE_MESHTASTIC);
    }
}

MESHBLE_API int meshble_start_scan(meshble_device_cb callback) {
    if (!atomic_load(&g_initialized) || !callback) return -1;
    scan_ctx_t ctx = { .callback = callback, .result = 0 };
    run_on_worker(do_start_scan, &ctx);
    return ctx.result;
}

MESHBLE_API void meshble_stop_scan(void) {
    if (!atomic_load(&g_initialized)) return;
    run_on_worker(do_stop_scan_wrapper, NULL);
}

MESHBLE_API int meshble_connect(const char* address, int timeout_ms) {
    if (!atomic_load(&g_initialized) || !address) return -1;
    atomic_store(&g_cancel_connect_requested, false);
    connect_ctx_t ctx;
    strncpy(ctx.address, address, sizeof(ctx.address) - 1);
    ctx.address[sizeof(ctx.address) - 1] = '\0';
    ctx.timeout_ms = timeout_ms;
    ctx.result = 0;
    run_on_worker(do_connect, &ctx);
    return ctx.result;
}

MESHBLE_API void meshble_disconnect(void) {
    if (!atomic_load(&g_initialized)) return;
    atomic_store(&g_cancel_connect_requested, true);
    atomic_store(&g_connected, false);
    atomic_store(&g_notifications_active, false);
    run_on_worker(do_disconnect_wrapper, NULL);
}

MESHBLE_API int meshble_is_connected(void) {
    return atomic_load(&g_connected) ? 1 : 0;
}

/* Worker-thread wrappers for D-Bus WriteValue/ReadValue */

typedef struct {
    const unsigned char* data;
    int length;
    int result;
} write_ctx_t;

static void do_write_to_radio(void* arg) {
    write_ctx_t* ctx = (write_ctx_t*)arg;
    ctx->result = dbus_write_value(g_bus, g_to_radio_char_path, ctx->data, ctx->length);
}

typedef struct {
    unsigned char* buffer;
    int buf_size;
    int out_len;
    int result;
} read_ctx_t;

static void do_read_from_radio(void* arg) {
    read_ctx_t* ctx = (read_ctx_t*)arg;
    ctx->result = dbus_read_value(g_bus, g_from_radio_char_path,
                                   ctx->buffer, ctx->buf_size, &ctx->out_len);
}

MESHBLE_API int meshble_write_to_radio(const unsigned char* data, int length) {
    if (!atomic_load(&g_connected) || !data || length <= 0) return -1;

    if (atomic_load(&g_use_dbus_write)) {
        /* D-Bus WriteValue — dispatch to worker thread (sd-bus not thread-safe) */
        write_ctx_t ctx = { .data = data, .length = length, .result = 0 };
        run_on_worker(do_write_to_radio, &ctx);
        return ctx.result;
    }

    int fd = g_to_radio_fd;
    if (fd < 0) return -1;
    ssize_t written = write(fd, data, length);
    if (written < 0) {
        log_msg("[meshble] write_to_radio failed: %s", strerror(errno));
        return -1;
    }
    return 0;
}

MESHBLE_API int meshble_read_from_radio(unsigned char* buffer, int buf_size, int* out_len) {
    if (!atomic_load(&g_connected) || !buffer || !out_len) return -1;
    *out_len = 0;

    if (atomic_load(&g_use_dbus_read)) {
        read_ctx_t ctx = { .buffer = buffer, .buf_size = buf_size, .out_len = 0, .result = 0 };
        run_on_worker(do_read_from_radio, &ctx);
        *out_len = ctx.out_len;
        return ctx.result;
    }

    int fd = g_from_radio_fd;
    if (fd < 0) return 0; /* no fd, no data */
    ssize_t n = read(fd, buffer, buf_size);
    if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        return -1;
    }
    *out_len = (int)n;
    return 0;
}

MESHBLE_API void meshble_set_from_radio_listener(meshble_data_cb callback) {
    g_data_callback = callback;
}

MESHBLE_API void meshble_set_state_listener(meshble_state_cb callback) {
    g_state_callback = callback;
}

MESHBLE_API int meshble_notifications_active(void) {
    return atomic_load(&g_notifications_active) ? 1 : 0;
}

MESHBLE_API void meshble_set_log_callback(meshble_log_cb callback) {
    g_log_callback = callback;
}

MESHBLE_API void meshble_set_passkey_request_callback(meshble_passkey_request_cb callback) {
    g_passkey_callback = callback;
}

static void do_respond_passkey(void* arg) {
    uint32_t passkey = *(uint32_t*)arg;
    if (g_pending_passkey_msg) {
        log_msg("[meshble] Responding to passkey request: %u", passkey);
        sd_bus_reply_method_return(g_pending_passkey_msg, "u", passkey);
        sd_bus_message_unref(g_pending_passkey_msg);
        g_pending_passkey_msg = NULL;
    }
}

MESHBLE_API void meshble_respond_passkey(uint32_t passkey) {
    uint32_t pk = passkey;
    run_on_worker(do_respond_passkey, &pk);
}

static void do_cancel_passkey(void* arg) {
    (void)arg;
    if (g_pending_passkey_msg) {
        log_msg("[meshble] Cancelling passkey request");
        sd_bus_reply_method_errorf(g_pending_passkey_msg, "org.bluez.Error.Rejected", "User cancelled");
        sd_bus_message_unref(g_pending_passkey_msg);
        g_pending_passkey_msg = NULL;
    }
}

MESHBLE_API void meshble_cancel_passkey(void) {
    run_on_worker(do_cancel_passkey, NULL);
}
