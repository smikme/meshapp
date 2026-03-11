/**
 * meshapp-ble: Linux BLE for MeshApp
 *
 * Pure C implementation using sd-bus (libsystemd) for BlueZ D-Bus communication.
 * Uses AcquireNotify/AcquireWrite for fd-based GATT I/O — bypasses D-Bus for data transfer.
 *
 * Architecture mirrors the Windows WinRT implementation:
 * - Dedicated worker thread running sd-bus event loop
 * - Task queue for cross-thread operations
 * - Flat C API for JNA consumption
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

static void log_msg(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    vfprintf(stderr, fmt, args);
    va_end(args);
    fputc('\n', stderr);
    fflush(stderr);
}

/* ==================== Task Queue ==================== */

typedef struct task_node {
    void (*func)(void* arg);
    void* arg;
    struct task_node* next;
} task_node_t;

typedef struct {
    task_node_t* head;
    task_node_t* tail;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
} task_queue_t;

static void tq_init(task_queue_t* q) {
    q->head = q->tail = NULL;
    pthread_mutex_init(&q->mutex, NULL);
    pthread_cond_init(&q->cond, NULL);
}

static void tq_destroy(task_queue_t* q) {
    pthread_mutex_lock(&q->mutex);
    task_node_t* n = q->head;
    while (n) { task_node_t* next = n->next; free(n); n = next; }
    q->head = q->tail = NULL;
    pthread_mutex_unlock(&q->mutex);
    pthread_mutex_destroy(&q->mutex);
    pthread_cond_destroy(&q->cond);
}

static void tq_push(task_queue_t* q, void (*func)(void*), void* arg) {
    task_node_t* n = (task_node_t*)malloc(sizeof(task_node_t));
    n->func = func;
    n->arg = arg;
    n->next = NULL;
    pthread_mutex_lock(&q->mutex);
    if (q->tail) { q->tail->next = n; q->tail = n; }
    else { q->head = q->tail = n; }
    pthread_cond_signal(&q->cond);
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

/* ==================== Global State ==================== */

static sd_bus* g_bus = NULL;
static pthread_t g_worker_thread;
static atomic_bool g_worker_running;
static atomic_bool g_initialized;
static atomic_bool g_connected;
static atomic_bool g_notifications_active;

static task_queue_t g_tasks;
static int g_wake_pipe[2] = {-1, -1}; /* [0]=read, [1]=write */

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

/* sd-bus signal match slots */
static sd_bus_slot* g_iface_added_slot = NULL;
static sd_bus_slot* g_props_changed_slot = NULL;
static sd_bus_slot* g_char_props_slot = NULL;

/* ==================== Helpers ==================== */

static int strcasecmp_uuid(const char* a, const char* b) {
    return strcasecmp(a, b);
}

/* Convert "AA:BB:CC:DD:EE:FF" → "/org/bluez/hci0/dev_AA_BB_CC_DD_EE_FF" */
static void make_device_path(const char* adapter, const char* address, char* out, int outsize) {
    char addr_underscored[18];
    strncpy(addr_underscored, address, sizeof(addr_underscored) - 1);
    addr_underscored[17] = '\0';
    for (int i = 0; addr_underscored[i]; i++)
        if (addr_underscored[i] == ':') addr_underscored[i] = '_';
    snprintf(out, outsize, "%s/dev_%s", adapter, addr_underscored);
}

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ==================== BlueZ D-Bus Helpers ==================== */

/* Find the first BlueZ adapter path (e.g., /org/bluez/hci0) */
static int find_adapter(sd_bus* bus, char* out, int outsize) {
    sd_bus_message* reply = NULL;
    int r = sd_bus_call_method(bus, BLUEZ_BUS, "/",
                               OBJMGR_IFACE, "GetManagedObjects",
                               NULL, &reply, "");
    if (r < 0) return r;

    /* Parse a{oa{sa{sv}}} */
    r = sd_bus_message_enter_container(reply, 'a', "{oa{sa{sv}}}");
    if (r < 0) goto done;

    while (sd_bus_message_enter_container(reply, 'e', "oa{sa{sv}}") > 0) {
        const char* path = NULL;
        sd_bus_message_read(reply, "o", &path);

        /* Iterate interfaces dict */
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
        sd_bus_message_exit_container(reply); /* a{sa{sv}} */
        sd_bus_message_exit_container(reply); /* dict entry */
    }
    r = -ENODEV;
done:
    sd_bus_message_unref(reply);
    return r;
}

/* Read a boolean property */
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

/* Set a boolean property */
static int set_bool_prop(sd_bus* bus, const char* path, const char* iface,
                         const char* prop, int val) {
    return sd_bus_call_method(bus, BLUEZ_BUS, path,
                              PROPS_IFACE, "Set",
                              NULL, NULL, "ssv", iface, prop, "b", val);
}

/* ==================== GATT Characteristic Discovery ==================== */

/* Find Meshtastic GATT characteristic paths under a device path */
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

        /* Only look under our device path */
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
                /* Parse properties to find UUID */
                sd_bus_message_enter_container(reply, 'a', "{sv}");
                while (sd_bus_message_enter_container(reply, 'e', "sv") > 0) {
                    const char* pname = NULL;
                    sd_bus_message_read(reply, "s", &pname);

                    if (pname && strcmp(pname, "UUID") == 0) {
                        const char* uuid = NULL;
                        sd_bus_message_read(reply, "v", "s", &uuid);
                        if (uuid) {
                            if (strcasecmp_uuid(uuid, FROM_RADIO_UUID) == 0) {
                                strncpy(g_from_radio_char_path, path, MAX_PATH - 1);
                                log_msg("[meshble] fromRadio: %s", path);
                            } else if (strcasecmp_uuid(uuid, TO_RADIO_UUID) == 0) {
                                strncpy(g_to_radio_char_path, path, MAX_PATH - 1);
                                log_msg("[meshble] toRadio: %s", path);
                            } else if (strcasecmp_uuid(uuid, FROM_NUM_UUID) == 0) {
                                strncpy(g_from_num_char_path, path, MAX_PATH - 1);
                                log_msg("[meshble] fromNum: %s", path);
                            }
                        }
                    } else {
                        sd_bus_message_skip(reply, "v");
                    }
                    sd_bus_message_exit_container(reply); /* dict entry */
                }
                sd_bus_message_exit_container(reply); /* a{sv} */
            } else {
                sd_bus_message_skip(reply, "a{sv}");
            }
            sd_bus_message_exit_container(reply); /* iface dict entry */
        }
        sd_bus_message_exit_container(reply); /* a{sa{sv}} */
        sd_bus_message_exit_container(reply); /* object dict entry */
    }

done:
    sd_bus_message_unref(reply);
    return (g_from_radio_char_path[0] && g_to_radio_char_path[0]) ? 0 : -ENOENT;
}

/* ==================== AcquireWrite / AcquireNotify ==================== */

/**
 * Call AcquireWrite on a GATT characteristic.
 * Returns file descriptor for writing, or -1 on error.
 */
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
    if (r < 0) {
        sd_bus_message_unref(reply);
        return -1;
    }

    /* sd-bus gives us an fd that may be invalidated when the message is freed,
       so dup it */
    int real_fd = fcntl(fd, F_DUPFD_CLOEXEC, 3);
    sd_bus_message_unref(reply);

    if (real_fd < 0) return -1;
    if (mtu_out) *mtu_out = mtu;
    log_msg("[meshble] AcquireWrite fd=%d mtu=%d", real_fd, mtu);
    return real_fd;
}

/**
 * Call AcquireNotify on a GATT characteristic.
 * Returns file descriptor for reading notifications, or -1 on error.
 */
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
    if (r < 0) {
        sd_bus_message_unref(reply);
        return -1;
    }

    int real_fd = fcntl(fd, F_DUPFD_CLOEXEC, 3);
    sd_bus_message_unref(reply);

    if (real_fd < 0) return -1;
    if (mtu_out) *mtu_out = mtu;
    log_msg("[meshble] AcquireNotify fd=%d mtu=%d", real_fd, mtu);
    return real_fd;
}

/* ==================== Signal Handlers ==================== */

/* InterfacesAdded handler — emits discovered BLE devices */
static int on_interfaces_added(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)userdata; (void)err;
    meshble_device_cb cb = g_device_callback;
    if (!cb) return 0;

    const char* path = NULL;
    int r = sd_bus_message_read(msg, "o", &path);
    if (r < 0) return 0;

    /* Parse interfaces dict a{sa{sv}} looking for org.bluez.Device1 */
    r = sd_bus_message_enter_container(msg, 'a', "{sa{sv}}");
    if (r < 0) return 0;

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

            if (address[0]) {
                cb(address, name[0] ? name : NULL, rssi);
            }
        } else {
            sd_bus_message_skip(msg, "a{sv}");
        }
        sd_bus_message_exit_container(msg);
    }
    return 0;
}

/* PropertiesChanged handler for device (Connected, ServicesResolved) */
static int on_device_props_changed(sd_bus_message* msg, void* userdata, sd_bus_error* err) {
    (void)err;

    const char* iface = NULL;
    sd_bus_message_read(msg, "s", &iface);
    if (!iface || strcmp(iface, DEVICE_IFACE) != 0) return 0;

    /* Parse changed properties dict a{sv} */
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
            /* Signal is used in connect() via a flag + pipe wake */
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

/* ==================== Emit Cached Devices ==================== */

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

static void* worker_loop(void* arg) {
    (void)arg;
    log_msg("[meshble] Worker thread started");

    while (atomic_load(&g_worker_running)) {
        /* Build pollfd array: sd-bus fd + wake pipe + fromRadio fd */
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
            if (r < 0) break;
            if (r == 0) break;
        }

        /* Process wake pipe — drain and execute tasks */
        if (fds[wake_idx].revents & POLLIN) {
            uint8_t buf[64];
            while (read(g_wake_pipe[0], buf, sizeof(buf)) > 0) {}

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

        /* Process fromRadio fd — notification data via AcquireNotify */
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

/* Wake worker thread to process tasks */
static void wake_worker(void) {
    uint8_t b = 1;
    if (write(g_wake_pipe[1], &b, 1) < 0) { /* ignore */ }
}

/* ==================== Internal Operations ==================== */

static void do_disconnect(void) {
    atomic_store(&g_connected, false);
    atomic_store(&g_notifications_active, false);

    if (g_from_radio_fd >= 0) { close(g_from_radio_fd); g_from_radio_fd = -1; }
    if (g_to_radio_fd >= 0) { close(g_to_radio_fd); g_to_radio_fd = -1; }

    /* Unsubscribe from signals */
    if (g_props_changed_slot) { sd_bus_slot_unref(g_props_changed_slot); g_props_changed_slot = NULL; }
    if (g_char_props_slot) { sd_bus_slot_unref(g_char_props_slot); g_char_props_slot = NULL; }

    /* Disconnect BlueZ device */
    if (g_device_path[0] && g_bus) {
        sd_bus_call_method(g_bus, BLUEZ_BUS, g_device_path,
                           DEVICE_IFACE, "Disconnect",
                           NULL, NULL, "");
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
                           ADAPTER_IFACE, "StopDiscovery",
                           NULL, NULL, "");
    }
    g_device_callback = NULL;
    log_msg("[meshble] Scan stopped");
}

/* ==================== API Implementation ==================== */

MESHBLE_API int meshble_init(void) {
    if (atomic_exchange(&g_initialized, true)) return 0;

    int r = sd_bus_open_system(&g_bus);
    if (r < 0) {
        log_msg("[meshble] Failed to open system bus: %s", strerror(-r));
        atomic_store(&g_initialized, false);
        return -1;
    }

    r = find_adapter(g_bus, g_adapter_path, sizeof(g_adapter_path));
    if (r < 0) {
        log_msg("[meshble] No BlueZ adapter found: %s", strerror(-r));
        sd_bus_unref(g_bus);
        g_bus = NULL;
        atomic_store(&g_initialized, false);
        return -1;
    }
    log_msg("[meshble] Adapter: %s", g_adapter_path);

    /* Create wake pipe (non-blocking) */
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

    do_disconnect();
    do_stop_scan();

    atomic_store(&g_worker_running, false);
    wake_worker();
    pthread_join(g_worker_thread, NULL);

    tq_destroy(&g_tasks);
    if (g_wake_pipe[0] >= 0) { close(g_wake_pipe[0]); g_wake_pipe[0] = -1; }
    if (g_wake_pipe[1] >= 0) { close(g_wake_pipe[1]); g_wake_pipe[1] = -1; }

    if (g_bus) { sd_bus_unref(g_bus); g_bus = NULL; }

    g_device_callback = NULL;
    g_data_callback = NULL;
    g_state_callback = NULL;
    atomic_store(&g_initialized, false);

    log_msg("[meshble] Cleanup done");
}

MESHBLE_API int meshble_get_adapter_state(void) {
    if (!atomic_load(&g_initialized) || !g_bus) return 0;
    int powered = 0;
    int r = get_bool_prop(g_bus, g_adapter_path, ADAPTER_IFACE, "Powered", &powered);
    if (r < 0) return 0;
    return powered ? 2 : 1;
}

MESHBLE_API int meshble_start_scan(meshble_device_cb callback) {
    if (!atomic_load(&g_initialized) || !callback) return -1;

    do_stop_scan();
    g_device_callback = callback;

    /* Set discovery filter for Meshtastic UUID + LE transport */
    sd_bus_message* m = NULL;
    int r = sd_bus_message_new_method_call(g_bus, &m, BLUEZ_BUS, g_adapter_path,
                                            ADAPTER_IFACE, "SetDiscoveryFilter");
    if (r >= 0) {
        sd_bus_message_open_container(m, 'a', "{sv}");
        /* UUIDs */
        sd_bus_message_open_container(m, 'e', "sv");
        sd_bus_message_append(m, "s", "UUIDs");
        sd_bus_message_open_container(m, 'v', "as");
        sd_bus_message_open_container(m, 'a', "s");
        sd_bus_message_append(m, "s", SERVICE_UUID);
        sd_bus_message_close_container(m);
        sd_bus_message_close_container(m);
        sd_bus_message_close_container(m);
        /* Transport */
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

    /* Subscribe to InterfacesAdded for new devices */
    r = sd_bus_match_signal(g_bus, &g_iface_added_slot,
                            BLUEZ_BUS, "/",
                            OBJMGR_IFACE, "InterfacesAdded",
                            on_interfaces_added, NULL);
    if (r < 0) log_msg("[meshble] Failed to subscribe InterfacesAdded: %s", strerror(-r));

    /* Emit already-known devices */
    emit_cached_devices(g_bus);

    /* Start discovery */
    sd_bus_error error = SD_BUS_ERROR_NULL;
    r = sd_bus_call_method(g_bus, BLUEZ_BUS, g_adapter_path,
                           ADAPTER_IFACE, "StartDiscovery",
                           &error, NULL, "");
    if (r < 0) {
        log_msg("[meshble] StartDiscovery failed: %s", error.message);
        sd_bus_error_free(&error);
        return -1;
    }
    sd_bus_error_free(&error);

    log_msg("[meshble] Scan started");
    return 0;
}

MESHBLE_API void meshble_stop_scan(void) {
    if (!atomic_load(&g_initialized)) return;
    do_stop_scan();
}

MESHBLE_API int meshble_connect(const char* address, int timeout_ms) {
    if (!atomic_load(&g_initialized) || !address) return -1;

    do_disconnect();

    make_device_path(g_adapter_path, address, g_device_path, sizeof(g_device_path));
    log_msg("[meshble] Connecting to %s (%s)...", address, g_device_path);

    /* Set Trusted=true */
    set_bool_prop(g_bus, g_device_path, DEVICE_IFACE, "Trusted", 1);

    /* Flag for ServicesResolved signal */
    volatile int services_resolved = 0;

    /* Subscribe to PropertiesChanged for disconnect + ServicesResolved */
    sd_bus_match_signal(g_bus, &g_props_changed_slot,
                        BLUEZ_BUS, g_device_path,
                        PROPS_IFACE, "PropertiesChanged",
                        on_device_props_changed, (void*)&services_resolved);

    /* Call Device1.Connect() */
    sd_bus_error error = SD_BUS_ERROR_NULL;
    int r = sd_bus_call_method(g_bus, BLUEZ_BUS, g_device_path,
                               DEVICE_IFACE, "Connect",
                               &error, NULL, "");
    if (r < 0) {
        log_msg("[meshble] Connect failed: %s (%s)", error.message, error.name);
        sd_bus_error_free(&error);

        /* Try rediscovery if device was removed */
        if (error.name && strstr(error.name, "UnknownObject")) {
            sd_bus_error_free(&error);
            do_disconnect();
            return -2;
        }
        sd_bus_error_free(&error);
        do_disconnect();
        return -3;
    }
    sd_bus_error_free(&error);
    log_msg("[meshble] Connect() returned, waiting for ServicesResolved...");

    /* Check if ServicesResolved is already true */
    int resolved = 0;
    get_bool_prop(g_bus, g_device_path, DEVICE_IFACE, "ServicesResolved", &resolved);
    if (resolved) services_resolved = 1;

    /* Wait for ServicesResolved with timeout */
    int64_t deadline = now_ms() + timeout_ms;
    while (!services_resolved && now_ms() < deadline) {
        /* Process sd-bus events to receive signals */
        for (;;) {
            r = sd_bus_process(g_bus, NULL);
            if (r <= 0) break;
        }
        if (services_resolved) break;

        /* Short poll */
        struct pollfd pfd = { .fd = sd_bus_get_fd(g_bus), .events = POLLIN };
        poll(&pfd, 1, 100);
    }

    if (!services_resolved) {
        log_msg("[meshble] ServicesResolved timeout");
        do_disconnect();
        return -1;
    }
    log_msg("[meshble] ServicesResolved=true");

    /* Find GATT characteristics */
    r = find_gatt_characteristics(g_bus, g_device_path);
    if (r < 0) {
        log_msg("[meshble] GATT characteristics not found");
        do_disconnect();
        return -3;
    }

    /* AcquireWrite on toRadio */
    g_to_radio_fd = acquire_write(g_bus, g_to_radio_char_path, &g_to_radio_mtu);
    if (g_to_radio_fd < 0) {
        log_msg("[meshble] AcquireWrite failed for toRadio");
        do_disconnect();
        return -3;
    }

    /* AcquireNotify on fromRadio */
    g_from_radio_fd = acquire_notify(g_bus, g_from_radio_char_path, &g_from_radio_mtu);
    if (g_from_radio_fd < 0) {
        log_msg("[meshble] AcquireNotify failed for fromRadio");
        do_disconnect();
        return -3;
    }

    /* Set fromRadio fd to non-blocking */
    fcntl(g_from_radio_fd, F_SETFL, O_NONBLOCK);

    /* fromNum: StartNotify (optional, for extra notification hint) */
    if (g_from_num_char_path[0]) {
        sd_bus_call_method(g_bus, BLUEZ_BUS, g_from_num_char_path,
                           CHAR_IFACE, "StartNotify",
                           NULL, NULL, "");
    }

    atomic_store(&g_connected, true);
    atomic_store(&g_notifications_active, true);
    log_msg("[meshble] Connected (fd-based GATT I/O: write_fd=%d read_fd=%d)",
            g_to_radio_fd, g_from_radio_fd);

    /* Wake worker to include fromRadio fd in poll */
    wake_worker();

    if (g_state_callback) g_state_callback(0, NULL);
    return 0;
}

MESHBLE_API void meshble_disconnect(void) {
    if (!atomic_load(&g_initialized)) return;
    atomic_store(&g_connected, false);
    atomic_store(&g_notifications_active, false);
    do_disconnect();
}

MESHBLE_API int meshble_is_connected(void) {
    return atomic_load(&g_connected) ? 1 : 0;
}

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
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0; /* No data */
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
