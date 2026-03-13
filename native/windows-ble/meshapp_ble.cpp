/**
 * meshapp-ble: Windows BLE for MeshApp
 *
 * C++/WinRT implementation wrapping Windows.Devices.Bluetooth.
 * Exposes flat C API for JNA consumption.
 *
 * IMPORTANT: No static WinRT objects — they must not be constructed during DLL load
 * (before WinRT apartment is initialized). All WinRT state is heap-allocated in meshble_init().
 * All WinRT operations run on a dedicated MTA worker thread.
 */

#include "meshapp_ble.h"

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Devices.Bluetooth.h>
#include <winrt/Windows.Devices.Bluetooth.Advertisement.h>
#include <winrt/Windows.Devices.Bluetooth.GenericAttributeProfile.h>
#include <winrt/Windows.Devices.Enumeration.h>
#include <winrt/Windows.Devices.Radios.h>
#include <winrt/Windows.Storage.Streams.h>

#include <windows.h>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <thread>
#include <chrono>
#include <functional>
#include <future>
#include <queue>
#include <condition_variable>
#include <memory>

using namespace winrt;
using namespace Windows::Foundation;
using namespace Windows::Devices::Bluetooth;
using namespace Windows::Devices::Bluetooth::Advertisement;
using namespace Windows::Devices::Bluetooth::GenericAttributeProfile;
using namespace Windows::Devices::Radios;
using namespace Windows::Storage::Streams;

/* ==================== Helpers (no WinRT deps) ==================== */

static void log_msg(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    char buf[512];
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    OutputDebugStringA(buf);
    OutputDebugStringA("\n");
}

static std::string mac_to_string(uint64_t addr) {
    char buf[18];
    snprintf(buf, sizeof(buf), "%02X:%02X:%02X:%02X:%02X:%02X",
             (int)((addr >> 40) & 0xFF), (int)((addr >> 32) & 0xFF),
             (int)((addr >> 24) & 0xFF), (int)((addr >> 16) & 0xFF),
             (int)((addr >> 8) & 0xFF),  (int)(addr & 0xFF));
    return std::string(buf);
}

static uint64_t string_to_mac(const char* str) {
    unsigned int b[6];
    if (sscanf(str, "%02X:%02X:%02X:%02X:%02X:%02X",
               &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6)
        return 0;
    return ((uint64_t)b[0] << 40) | ((uint64_t)b[1] << 32) |
           ((uint64_t)b[2] << 24) | ((uint64_t)b[3] << 16) |
           ((uint64_t)b[4] << 8)  | (uint64_t)b[5];
}

/* ==================== BLE State (heap-allocated, no static WinRT) ==================== */

// Meshtastic BLE UUIDs — plain GUID structs, safe as statics
static const GUID SVC_UUID  = {0x6ba1b218,0x15a8,0x461f,{0x9f,0xa8,0x5d,0xca,0xe2,0x73,0xea,0xfd}};
static const GUID FR_UUID   = {0x2c55e69e,0x4993,0x11ed,{0xb8,0x78,0x02,0x42,0xac,0x12,0x00,0x02}};
static const GUID TR_UUID   = {0xf75c76d2,0x129e,0x4dad,{0xa1,0xdd,0x78,0x66,0x12,0x44,0x01,0xe7}};
static const GUID FN_UUID   = {0xed9da18c,0xa800,0x4f66,{0xa6,0x70,0xaa,0x75,0x47,0xe3,0x44,0x53}};

static inline guid to_guid(const GUID& g) {
    return *reinterpret_cast<const guid*>(&g);
}

struct BleState {
    // Scanning
    BluetoothLEAdvertisementWatcher watcher{nullptr};
    event_token watcher_received_token{};
    event_token watcher_stopped_token{};

    // Connection
    BluetoothLEDevice device{nullptr};
    GattDeviceService service{nullptr};
    GattCharacteristic from_radio{nullptr};
    GattCharacteristic to_radio{nullptr};
    GattCharacteristic from_num{nullptr};
    event_token from_radio_notify_token{};
    event_token from_num_notify_token{};
    event_token connection_status_token{};
};

// All state heap-allocated — created in meshble_init, on the worker thread
static BleState* g_ble = nullptr;

// Plain C types — safe as statics
static std::atomic<bool> g_initialized{false};
static std::atomic<bool> g_connected{false};
static std::atomic<bool> g_notifications_active{false};
static meshble_device_cb g_device_callback = nullptr;
static meshble_data_cb g_data_callback = nullptr;
static meshble_state_cb g_state_callback = nullptr;

/* ==================== Worker Thread ==================== */

static std::thread g_worker_thread;
static std::mutex g_queue_mutex;
static std::condition_variable g_queue_cv;
static std::queue<std::function<void()>> g_task_queue;
static std::atomic<bool> g_worker_running{false};

static void worker_loop() {
    winrt::init_apartment(apartment_type::multi_threaded);

    while (g_worker_running.load()) {
        std::function<void()> task;
        {
            std::unique_lock<std::mutex> lock(g_queue_mutex);
            g_queue_cv.wait_for(lock, std::chrono::milliseconds(100),
                [] { return !g_task_queue.empty() || !g_worker_running.load(); });
            if (!g_worker_running.load() && g_task_queue.empty()) break;
            if (g_task_queue.empty()) continue;
            task = std::move(g_task_queue.front());
            g_task_queue.pop();
        }
        if (task) task();
    }

    winrt::uninit_apartment();
}

template<typename F>
auto run_on_worker(F&& func) -> decltype(func()) {
    using R = decltype(func());
    auto promise = std::make_shared<std::promise<R>>();
    auto future = promise->get_future();
    {
        std::lock_guard<std::mutex> lock(g_queue_mutex);
        if constexpr (std::is_void_v<R>) {
            g_task_queue.push([p = std::move(promise), f = std::forward<F>(func)]() {
                try { f(); p->set_value(); }
                catch (...) { p->set_exception(std::current_exception()); }
            });
        } else {
            g_task_queue.push([p = std::move(promise), f = std::forward<F>(func)]() {
                try { p->set_value(f()); }
                catch (...) { p->set_exception(std::current_exception()); }
            });
        }
    }
    g_queue_cv.notify_one();
    return future.get();
}

static void post_to_worker(std::function<void()> func) {
    {
        std::lock_guard<std::mutex> lock(g_queue_mutex);
        g_task_queue.push(std::move(func));
    }
    g_queue_cv.notify_one();
}

/* ==================== WinRT Helpers (called on worker thread only) ==================== */

static std::vector<uint8_t> buffer_to_bytes(IBuffer const& buffer) {
    auto reader = DataReader::FromBuffer(buffer);
    std::vector<uint8_t> data(reader.UnconsumedBufferLength());
    if (!data.empty()) reader.ReadBytes(data);
    return data;
}

static IBuffer bytes_to_buffer(const unsigned char* data, int length) {
    auto writer = DataWriter();
    writer.WriteBytes(array_view<const uint8_t>(data, data + length));
    return writer.DetachBuffer();
}

static GattCharacteristic find_characteristic(GattDeviceService const& service, guid const& uuid) {
    auto result = service.GetCharacteristicsForUuidAsync(uuid).get();
    if (result.Status() == GattCommunicationStatus::Success) {
        auto chars = result.Characteristics();
        if (chars.Size() > 0) return chars.GetAt(0);
    }
    return nullptr;
}

static void drain_from_radio() {
    if (!g_connected || !g_ble || g_ble->from_radio == nullptr) return;
    for (int i = 0; i < 100; i++) {
        try {
            auto result = g_ble->from_radio.ReadValueAsync(BluetoothCacheMode::Uncached).get();
            if (result.Status() != GattCommunicationStatus::Success) break;
            auto data = buffer_to_bytes(result.Value());
            if (data.empty()) break;
            if (g_data_callback) g_data_callback(data.data(), (int)data.size());
        } catch (...) { break; }
    }
}

/* ==================== Notification Handlers ==================== */

static void on_from_radio_value_changed(
    GattCharacteristic const&, GattValueChangedEventArgs const& args) {
    auto data = buffer_to_bytes(args.CharacteristicValue());
    if (!data.empty() && g_data_callback)
        g_data_callback(data.data(), (int)data.size());
}

static void on_from_num_value_changed(
    GattCharacteristic const&, GattValueChangedEventArgs const&) {
    post_to_worker([] { drain_from_radio(); });
}

static void on_connection_status_changed(
    BluetoothLEDevice const& device, IInspectable const&) {
    if (device.ConnectionStatus() == BluetoothConnectionStatus::Disconnected) {
        g_connected = false;
        log_msg("[meshble] Device disconnected");
        if (g_state_callback) g_state_callback(1, nullptr);
    }
}

/* ==================== Internal ops (worker thread) ==================== */

static void do_stop_scan() {
    if (!g_ble) return;
    try {
        if (g_ble->watcher != nullptr) {
            if (g_ble->watcher.Status() == BluetoothLEAdvertisementWatcherStatus::Started)
                g_ble->watcher.Stop();
            g_ble->watcher.Received(g_ble->watcher_received_token);
            g_ble->watcher.Stopped(g_ble->watcher_stopped_token);
            g_ble->watcher = nullptr;
        }
    } catch (...) {}
    g_device_callback = nullptr;
    log_msg("[meshble] Scan stopped");
}

static void do_disconnect() {
    g_connected = false;
    g_notifications_active = false;
    if (!g_ble) return;

    try {
        if (g_ble->from_radio != nullptr) {
            try {
                g_ble->from_radio.ValueChanged(g_ble->from_radio_notify_token);
                g_ble->from_radio.WriteClientCharacteristicConfigurationDescriptorAsync(
                    GattClientCharacteristicConfigurationDescriptorValue::None).get();
            } catch (...) {}
            g_ble->from_radio = nullptr;
        }
        if (g_ble->from_num != nullptr) {
            try { g_ble->from_num.ValueChanged(g_ble->from_num_notify_token); } catch (...) {}
            g_ble->from_num = nullptr;
        }
        g_ble->to_radio = nullptr;
        if (g_ble->service != nullptr) { g_ble->service.Close(); g_ble->service = nullptr; }
        if (g_ble->device != nullptr) {
            g_ble->device.ConnectionStatusChanged(g_ble->connection_status_token);
            g_ble->device.Close(); g_ble->device = nullptr;
        }
    } catch (...) {}
    log_msg("[meshble] Disconnected");
}

/* ==================== API ==================== */

MESHBLE_API int meshble_init(void) {
    if (g_initialized.exchange(true)) return 0;

    g_worker_running = true;
    g_worker_thread = std::thread(worker_loop);

    // Allocate BLE state on worker thread (WinRT apartment ready)
    try {
        run_on_worker([] {
            g_ble = new BleState();
        });
    } catch (...) {
        g_worker_running = false;
        if (g_worker_thread.joinable()) g_worker_thread.join();
        g_initialized = false;
        return -1;
    }

    log_msg("[meshble] Initialized (MTA worker thread)");
    return 0;
}

MESHBLE_API void meshble_cleanup(void) {
    if (!g_initialized) return;

    try {
        run_on_worker([] {
            do_disconnect();
            do_stop_scan();
            delete g_ble;
            g_ble = nullptr;
        });
    } catch (...) {}

    g_device_callback = nullptr;
    g_data_callback = nullptr;
    g_state_callback = nullptr;

    g_worker_running = false;
    g_queue_cv.notify_one();
    if (g_worker_thread.joinable()) g_worker_thread.join();

    g_initialized = false;
    log_msg("[meshble] Cleanup done");
}

MESHBLE_API int meshble_get_adapter_state(void) {
    if (!g_initialized) return 0;
    try {
        return run_on_worker([]() -> int {
            try {
                auto radios = Radio::GetRadiosAsync().get();
                for (auto const& radio : radios) {
                    if (radio.Kind() == RadioKind::Bluetooth) {
                        switch (radio.State()) {
                            case RadioState::On:       return 2;
                            case RadioState::Off:      return 1;
                            case RadioState::Disabled:  return 1;
                            default:                   return 0;
                        }
                    }
                }
                return 3;  // No BT radio
            } catch (...) { return 0; }
        });
    } catch (...) { return 0; }
}

MESHBLE_API int meshble_start_scan(meshble_device_cb callback) {
    if (!g_initialized || !callback) return -1;
    try {
        return run_on_worker([callback]() -> int {
            do_stop_scan();
            g_device_callback = callback;
            try {
                g_ble->watcher = BluetoothLEAdvertisementWatcher();
                g_ble->watcher.ScanningMode(BluetoothLEScanningMode::Active);

                auto filter = BluetoothLEAdvertisementFilter();
                auto adv = BluetoothLEAdvertisement();
                adv.ServiceUuids().Append(to_guid(SVC_UUID));
                filter.Advertisement(adv);
                g_ble->watcher.AdvertisementFilter(filter);

                g_ble->watcher_received_token = g_ble->watcher.Received(
                    [](BluetoothLEAdvertisementWatcher const&,
                       BluetoothLEAdvertisementReceivedEventArgs const& args) {
                        if (!g_device_callback) return;
                        auto addr = mac_to_string(args.BluetoothAddress());
                        auto name = args.Advertisement().LocalName();
                        std::string nameStr;
                        if (!name.empty()) nameStr = winrt::to_string(name);
                        g_device_callback(addr.c_str(),
                            nameStr.empty() ? nullptr : nameStr.c_str(),
                            args.RawSignalStrengthInDBm());
                    });

                g_ble->watcher_stopped_token = g_ble->watcher.Stopped(
                    [](BluetoothLEAdvertisementWatcher const&,
                       BluetoothLEAdvertisementWatcherStoppedEventArgs const& args) {
                        log_msg("[meshble] Watcher stopped, error: %d", (int)args.Error());
                    });

                g_ble->watcher.Start();
                log_msg("[meshble] Scan started");
                return 0;
            } catch (const hresult_error& e) {
                log_msg("[meshble] Scan failed: %ls", e.message().c_str());
                return -1;
            } catch (...) {
                log_msg("[meshble] Scan failed: unknown");
                return -1;
            }
        });
    } catch (...) { return -1; }
}

MESHBLE_API void meshble_stop_scan(void) {
    if (!g_initialized) return;
    try { run_on_worker([] { do_stop_scan(); }); } catch (...) {}
}

MESHBLE_API int meshble_connect(const char* address, int timeout_ms) {
    if (!g_initialized || !address) return -1;
    std::string addr(address);

    try {
        return run_on_worker([addr, timeout_ms]() -> int {
            do_disconnect();
            uint64_t mac = string_to_mac(addr.c_str());
            if (mac == 0) return -2;

            try {
                log_msg("[meshble] Connecting to %s ...", addr.c_str());

                auto dev = BluetoothLEDevice::FromBluetoothAddressAsync(mac).get();
                if (dev == nullptr) { log_msg("[meshble] Device not found"); return -2; }
                g_ble->device = dev;
                g_ble->connection_status_token = g_ble->device.ConnectionStatusChanged(on_connection_status_changed);

                log_msg("[meshble] Discovering GATT services...");
                auto svc_result = g_ble->device.GetGattServicesForUuidAsync(to_guid(SVC_UUID)).get();
                if (svc_result.Status() == GattCommunicationStatus::AccessDenied) { do_disconnect(); return -4; }
                if (svc_result.Status() != GattCommunicationStatus::Success || svc_result.Services().Size() == 0) {
                    do_disconnect(); return -3;
                }
                g_ble->service = svc_result.Services().GetAt(0);

                log_msg("[meshble] Discovering characteristics...");
                g_ble->from_radio = find_characteristic(g_ble->service, to_guid(FR_UUID));
                g_ble->to_radio   = find_characteristic(g_ble->service, to_guid(TR_UUID));
                g_ble->from_num   = find_characteristic(g_ble->service, to_guid(FN_UUID));

                if (!g_ble->to_radio || !g_ble->from_radio) {
                    log_msg("[meshble] Required characteristics not found");
                    do_disconnect(); return -3;
                }

                // fromRadio notifications unreliable on many Windows BLE adapters:
                // subscription succeeds but notifications are never delivered.
                // Always use Java-side polling (same approach as macOS).
                g_notifications_active = false;
                log_msg("[meshble] fromRadio: using polling (notifications skipped)");

                if (g_ble->from_num != nullptr) {
                    try {
                        auto nr2 = g_ble->from_num.WriteClientCharacteristicConfigurationDescriptorAsync(
                            GattClientCharacteristicConfigurationDescriptorValue::Notify).get();
                        if (nr2 == GattCommunicationStatus::Success)
                            g_ble->from_num_notify_token = g_ble->from_num.ValueChanged(on_from_num_value_changed);
                    } catch (...) {}
                }

                g_connected = true;
                drain_from_radio();
                log_msg("[meshble] Connected (notifications=%s)", g_notifications_active.load() ? "yes" : "polling");
                if (g_state_callback) g_state_callback(0, nullptr);
                return 0;

            } catch (const hresult_error& e) {
                log_msg("[meshble] Connect error: %ls", e.message().c_str());
                do_disconnect(); return -3;
            } catch (...) {
                do_disconnect(); return -3;
            }
        });
    } catch (...) { return -3; }
}

MESHBLE_API void meshble_disconnect(void) {
    if (!g_initialized) return;
    g_connected = false;
    g_notifications_active = false;
    post_to_worker([] { do_disconnect(); });
}

MESHBLE_API int meshble_is_connected(void) {
    return g_connected.load() ? 1 : 0;
}

MESHBLE_API int meshble_write_to_radio(const unsigned char* data, int length) {
    if (!g_connected || !data || length <= 0) return -1;
    std::vector<unsigned char> copy(data, data + length);
    try {
        return run_on_worker([copy]() -> int {
            if (!g_connected || !g_ble || g_ble->to_radio == nullptr) return -1;
            try {
                auto buf = bytes_to_buffer(copy.data(), (int)copy.size());
                auto r = g_ble->to_radio.WriteValueAsync(buf, GattWriteOption::WriteWithResponse).get();
                return (r == GattCommunicationStatus::Success) ? 0 : -1;
            } catch (...) { return -1; }
        });
    } catch (...) { return -1; }
}

MESHBLE_API int meshble_read_from_radio(unsigned char* buffer, int buf_size, int* out_len) {
    if (!g_connected || !buffer || !out_len) return -1;
    *out_len = 0;
    try {
        return run_on_worker([buffer, buf_size, out_len]() -> int {
            if (!g_connected || !g_ble || g_ble->from_radio == nullptr) return -1;
            try {
                auto result = g_ble->from_radio.ReadValueAsync(BluetoothCacheMode::Uncached).get();
                if (result.Status() != GattCommunicationStatus::Success) return -1;
                auto data = buffer_to_bytes(result.Value());
                if (data.empty()) return 0;
                int n = (int)data.size() < buf_size ? (int)data.size() : buf_size;
                memcpy(buffer, data.data(), n);
                *out_len = n;
                return 0;
            } catch (...) { return -1; }
        });
    } catch (...) { return -1; }
}

MESHBLE_API void meshble_set_from_radio_listener(meshble_data_cb callback) { g_data_callback = callback; }
MESHBLE_API void meshble_set_state_listener(meshble_state_cb callback) { g_state_callback = callback; }
MESHBLE_API int meshble_notifications_active(void) { return g_notifications_active.load() ? 1 : 0; }
