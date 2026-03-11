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

#define SERVICE_UUID       "6ba1b218-15a8-461f-9fa8-5dcae273eafd"
#define FROM_RADIO_UUID    "2c55e69e-4993-11ed-b878-0242ac120002"
#define TO_RADIO_UUID      "f75c76d2-129e-4dad-a1dd-7866124401e7"
#define FROM_NUM_UUID      "ed9da18c-a800-4f66-a670-aa7547e34453"

#define MAX_PATH           1024
#define MAX_DRAIN          100
#define POLL_TIMEOUT_MS    100

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

static task_queue_t g_tasks;
static int g_wake_pipe[2] = {-1, -1};

static char g_adapter_path[MAX_PATH];
static char g_device_path[MAX_PATH];

static int g_to_radio_fd = -1;
static int g_from_radio_fd = -1;
static uint16_t g_to_radio_mtu = 0;
static uint16_t g_from_radio_mtu = 0;

static char g_from_radio_char_path[MAX_PATH];
static char g_to_radio_char_path[MAX_PATH];
static char g_from_num_char_path[MAX_PATH];

static meshble_device_cb g_device_callback = NULL;
static meshble_data_cb g_data_callback = NULL;
static meshble_state_cb g_state_callback = NULL;

static sd_bus_slot* g_iface_added_slot = NULL;
static sd_bus_slot* g_props_changed_slot = NULL;

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
    if (r < 0) return r;

    r = sd_bus_message_enter_container(reply, 'a', "{oa{sa{sv}}}");
    if (r < 0) goto done;

    while (sd_bus_message_enter_container(reply, 'e', "oa{sa{sv}}") > 0) {
        const char* path = NULL;
        sd_bus_message_read(reply, "o", &path);

        if (!path || strncmp(path, device_path, strlen(device_path)) != 0) {
            sd_bus_message_skip(reply, "a{sa{sv}}");
            sd_bus_message_exit_container(reply);
            continue;
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
                            if (strcasecmp(uuid, FROM_RADIO_UUID) == 0) {
                                strncpy(g_from_radio_char_path, path, MAX_PATH - 1);
                                log_msg("[meshble] fromRadio: %s", path);
                            } else if (strcasecmp(uuid, TO_RADIO_UUID) == 0) {
                                strncpy(g_to_radio_char_path, path, MAX_PATH - 1);
                                log_msg("[meshble] toRadio: %s", path);
                            } else if (strcasecmp(uuid, FROM_NUM_UUID) == 0) {
                                strncpy(g_from_num_char_path, path, MAX_PATH - 1);
                                log_msg("[meshble] fromNum: %s", path);
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

static int on_device_props_changed(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)err;
    const char* iface = NULL;
    sd_bus_message_read(msg, "s", &iface);
    if (!iface || strcmp(iface, DEVICE_IFACE) != 0) return 0;

    sd_bus_message_enter_container(msg, 'a', "{sv}");
    while (sd_bus_message_enter_container(msg, 'e', "sv") > 0) {
        const char* pname = NULL;
        sd_bus_message_read(msg, "s", &pname);

        if (pname && strcmp(pname, "Connected") == 0) {
            int val = 0;
            sd_bus_message_read(msg, "v", "b", &val);
            log_msg("[meshble] Device Connected=%d", val);
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
            if (val && userdata) {
                int* flag = (int*)userdata;
                *flag = 1;
            }
        } else {
            sd_bus_message_skip(msg, "v");
        }
        sd_bus_message_exit_container(msg);
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

    if (g_from_radio_fd >= 0) { close(g_from_radio_fd); g_from_radio_fd = -1; }
    if (g_to_radio_fd >= 0) { close(g_to_radio_fd); g_to_radio_fd = -1; }

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
        sd_bus_message_append(m, "s", SERVICE_UUID);
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

static int try_connect_and_resolve(connect_ctx_t* ctx) {
    volatile int services_resolved = 0;

    sd_bus_match_signal(g_bus, &g_props_changed_slot,
                        BLUEZ_BUS, g_device_path,
                        PROPS_IFACE, "PropertiesChanged",
                        on_device_props_changed, (void*)&services_resolved);

    set_bool_prop(g_bus, g_device_path, DEVICE_IFACE, "Trusted", 1);

    sd_bus_error error = SD_BUS_ERROR_NULL;
    sd_bus_message* conn_msg = NULL;
    int r = sd_bus_message_new_method_call(g_bus, &conn_msg, BLUEZ_BUS, g_device_path,
                                            DEVICE_IFACE, "Connect");
    if (r >= 0) {
        r = sd_bus_call(g_bus, conn_msg, (uint64_t)ctx->timeout_ms * 1000, &error, NULL);
        sd_bus_message_unref(conn_msg);
    }
    if (r < 0) {
        log_msg("[meshble] Connect failed: %s (%s)", error.message, error.name);
        int is_gone = (error.name && strstr(error.name, "UnknownObject"));
        int is_denied = (error.name && (strstr(error.name, "AccessDenied") ||
                                         strstr(error.name, "AuthenticationFailed")));
        sd_bus_error_free(&error);
        if (is_gone) return -2;
        if (is_denied) return -4;
        return -3;
    }
    sd_bus_error_free(&error);
    log_msg("[meshble] Connect() returned, waiting for ServicesResolved...");

    /* Wait for ServicesResolved — poll property + process D-Bus signals */
    int64_t deadline = now_ms() + ctx->timeout_ms;
    while (!services_resolved && now_ms() < deadline) {
        for (;;) {
            r = sd_bus_process(g_bus, NULL);
            if (r <= 0) break;
        }
        if (services_resolved) break;

        int resolved = 0;
        if (get_bool_prop(g_bus, g_device_path, DEVICE_IFACE, "ServicesResolved", &resolved) >= 0
            && resolved) {
            log_msg("[meshble] ServicesResolved=true (polled)");
            services_resolved = 1;
            break;
        }

        struct pollfd pfd = { .fd = sd_bus_get_fd(g_bus), .events = POLLIN };
        poll(&pfd, 1, 200);
    }

    if (!services_resolved) {
        log_msg("[meshble] ServicesResolved timeout");
        return -1;
    }
    log_msg("[meshble] ServicesResolved=true");
    return 0;
}

static void do_connect(void* arg) {
    connect_ctx_t* ctx = (connect_ctx_t*)arg;

    do_disconnect();

    make_device_path(g_adapter_path, ctx->address, g_device_path, sizeof(g_device_path));
    log_msg("[meshble] Connecting to %s (%s)...", ctx->address, g_device_path);

    /* Check Paired status for diagnostics */
    int paired = 0;
    get_bool_prop(g_bus, g_device_path, DEVICE_IFACE, "Paired", &paired);
    log_msg("[meshble] Device Paired=%d", paired);

    int r = try_connect_and_resolve(ctx);

    /* If ServicesResolved timed out, remove device from BlueZ cache and retry fresh */
    if (r == -1) {
        log_msg("[meshble] Retrying: removing device from BlueZ cache...");
        /* Disconnect first */
        sd_bus_call_method(g_bus, BLUEZ_BUS, g_device_path,
                           DEVICE_IFACE, "Disconnect", NULL, NULL, "");
        /* Unref signal slot before removal */
        if (g_props_changed_slot) { sd_bus_slot_unref(g_props_changed_slot); g_props_changed_slot = NULL; }
        /* Remove device from BlueZ cache — forces fresh service discovery */
        sd_bus_error rm_error = SD_BUS_ERROR_NULL;
        int rm = sd_bus_call_method(g_bus, BLUEZ_BUS, g_adapter_path,
                                     ADAPTER_IFACE, "RemoveDevice",
                                     &rm_error, NULL, "o", g_device_path);
        if (rm < 0) {
            log_msg("[meshble] RemoveDevice failed: %s", rm_error.message);
            sd_bus_error_free(&rm_error);
        } else {
            sd_bus_error_free(&rm_error);
            log_msg("[meshble] Device removed, waiting for re-discovery...");

            /* Wait for device to reappear via discovery (up to 5 seconds) */
            sd_bus_call_method(g_bus, BLUEZ_BUS, g_adapter_path,
                               ADAPTER_IFACE, "StartDiscovery", NULL, NULL, "");

            int64_t rediscovery_deadline = now_ms() + 5000;
            int found = 0;
            while (!found && now_ms() < rediscovery_deadline) {
                for (;;) {
                    int pr = sd_bus_process(g_bus, NULL);
                    if (pr <= 0) break;
                }
                /* Check if device path exists again */
                int connected_val = 0;
                if (get_bool_prop(g_bus, g_device_path, DEVICE_IFACE, "Connected", &connected_val) >= 0) {
                    found = 1;
                    break;
                }
                struct pollfd pfd = { .fd = sd_bus_get_fd(g_bus), .events = POLLIN };
                poll(&pfd, 1, 200);
            }

            sd_bus_call_method(g_bus, BLUEZ_BUS, g_adapter_path,
                               ADAPTER_IFACE, "StopDiscovery", NULL, NULL, "");

            if (found) {
                log_msg("[meshble] Device re-discovered, connecting fresh...");
                r = try_connect_and_resolve(ctx);
            } else {
                log_msg("[meshble] Device not re-discovered within timeout");
                r = -2;
            }
        }
    }

    if (r != 0) {
        do_disconnect();
        ctx->result = r;
        return;
    }

    r = find_gatt_characteristics(g_bus, g_device_path);
    if (r < 0) {
        log_msg("[meshble] GATT characteristics not found");
        do_disconnect();
        ctx->result = -3;
        return;
    }

    g_to_radio_fd = acquire_write(g_bus, g_to_radio_char_path, &g_to_radio_mtu);
    if (g_to_radio_fd < 0) {
        log_msg("[meshble] AcquireWrite failed for toRadio");
        do_disconnect();
        ctx->result = -3;
        return;
    }

    g_from_radio_fd = acquire_notify(g_bus, g_from_radio_char_path, &g_from_radio_mtu);
    if (g_from_radio_fd < 0) {
        log_msg("[meshble] AcquireNotify failed for fromRadio");
        do_disconnect();
        ctx->result = -3;
        return;
    }

    fcntl(g_from_radio_fd, F_SETFL, O_NONBLOCK);

    if (g_from_num_char_path[0]) {
        sd_bus_call_method(g_bus, BLUEZ_BUS, g_from_num_char_path,
                           CHAR_IFACE, "StartNotify", NULL, NULL, "");
    }

    atomic_store(&g_connected, true);
    atomic_store(&g_notifications_active, true);
    log_msg("[meshble] Connected (fd-based GATT I/O: write_fd=%d read_fd=%d)",
            g_to_radio_fd, g_from_radio_fd);

    if (g_state_callback) g_state_callback(0, NULL);
    ctx->result = 0;
}

/* ==================== BlueZ Agent (NoInputNoOutput) ==================== */

#define AGENT_PATH "/meshapp/agent"
#define AGENT_MGR_IFACE "org.bluez.AgentManager1"
#define AGENT_IFACE "org.bluez.Agent1"

static sd_bus_slot* g_agent_slot = NULL;

static int agent_release(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    return sd_bus_reply_method_return(msg, "");
}

static int agent_request_default(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    /* Auto-accept: reply empty for RequestConfirmation, RequestAuthorization, AuthorizeService */
    return sd_bus_reply_method_return(msg, "");
}

static int agent_cancel(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    return sd_bus_reply_method_return(msg, "");
}

static const sd_bus_vtable agent_vtable[] = {
    SD_BUS_VTABLE_START(0),
    SD_BUS_METHOD("Release", "", "", agent_release, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("RequestPinCode", "o", "s", agent_request_default, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("RequestPasskey", "o", "u", agent_request_default, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("DisplayPinCode", "os", "", agent_request_default, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("DisplayPasskey", "ouu", "", agent_request_default, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("RequestConfirmation", "ou", "", agent_request_default, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("RequestAuthorization", "o", "", agent_request_default, SD_BUS_VTABLE_UNPRIVILEGED),
    SD_BUS_METHOD("AuthorizeService", "os", "", agent_request_default, SD_BUS_VTABLE_UNPRIVILEGED),
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

    sd_bus_error error = SD_BUS_ERROR_NULL;
    r = sd_bus_call_method(bus, BLUEZ_BUS, "/org/bluez",
                            AGENT_MGR_IFACE, "RegisterAgent",
                            &error, NULL, "os", AGENT_PATH, "NoInputNoOutput");
    if (r < 0) {
        /* AlreadyExists is OK — agent from previous init */
        if (error.name && strstr(error.name, "AlreadyExists")) {
            log_msg("[meshble] Agent already registered");
            sd_bus_error_free(&error);
        } else {
            log_msg("[meshble] RegisterAgent failed: %s (%s)", error.message, error.name);
            sd_bus_error_free(&error);
            return r;
        }
    } else {
        sd_bus_error_free(&error);
    }

    r = sd_bus_call_method(bus, BLUEZ_BUS, "/org/bluez",
                            AGENT_MGR_IFACE, "RequestDefaultAgent",
                            NULL, NULL, "o", AGENT_PATH);
    if (r < 0) {
        log_msg("[meshble] RequestDefaultAgent failed (non-fatal)");
    }

    log_msg("[meshble] Agent registered (NoInputNoOutput)");
    return 0;
}

static void unregister_agent(sd_bus* bus) {
    if (!bus) return;
    sd_bus_call_method(bus, BLUEZ_BUS, "/org/bluez",
                        AGENT_MGR_IFACE, "UnregisterAgent",
                        NULL, NULL, "o", AGENT_PATH);
    if (g_agent_slot) { sd_bus_slot_unref(g_agent_slot); g_agent_slot = NULL; }
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

    /* Register BlueZ Agent — required for service discovery on many devices */
    register_agent(g_bus);

    if (pipe(g_wake_pipe) < 0) {
        sd_bus_unref(g_bus); g_bus = NULL;
        atomic_store(&g_initialized, false);
        return -1;
    }
    fcntl(g_wake_pipe[0], F_SETFL, O_NONBLOCK);
    fcntl(g_wake_pipe[1], F_SETFL, O_NONBLOCK);

    tq_init(&g_tasks);
    atomic_store(&g_worker_running, true);
    pthread_create(&g_worker_thread, NULL, worker_loop, NULL);

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
    atomic_store(&g_initialized, false);
    log_msg("[meshble] Cleanup done");
}

MESHBLE_API int meshble_get_adapter_state(void) {
    if (!atomic_load(&g_initialized) || !g_bus) return 0;
    adapter_state_ctx_t ctx = { .result = 0 };
    run_on_worker(do_get_adapter_state, &ctx);
    return ctx.result;
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
    atomic_store(&g_connected, false);
    atomic_store(&g_notifications_active, false);
    run_on_worker(do_disconnect_wrapper, NULL);
}

MESHBLE_API int meshble_is_connected(void) {
    return atomic_load(&g_connected) ? 1 : 0;
}

/* write/read use fd-based I/O — thread-safe at OS level, no D-Bus needed */
MESHBLE_API int meshble_write_to_radio(const unsigned char* data, int length) {
    if (!atomic_load(&g_connected) || !data || length <= 0) return -1;
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
    int fd = g_from_radio_fd;
    if (fd < 0) return -1;
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
