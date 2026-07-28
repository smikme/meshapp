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
#include <stdexcept>
#include <initializer_list>
#include <unordered_map>
#include <unordered_set>

using namespace winrt;
using namespace Windows::Foundation;
using namespace Windows::Devices::Bluetooth;
using namespace Windows::Devices::Bluetooth::Advertisement;
using namespace Windows::Devices::Bluetooth::GenericAttributeProfile;
using namespace Windows::Devices::Enumeration;
using namespace Windows::Devices::Radios;
using namespace Windows::Storage::Streams;

/* ==================== Helpers (no WinRT deps) ==================== */

static std::mutex g_log_mutex;
static std::mutex g_last_error_mutex;
static std::string g_last_error;

static void set_last_error(std::string message) {
    std::lock_guard<std::mutex> lock(g_last_error_mutex);
    g_last_error = std::move(message);
}

static void clear_last_error() {
    set_last_error(std::string());
}

static std::string get_last_error_copy() {
    std::lock_guard<std::mutex> lock(g_last_error_mutex);
    return g_last_error;
}

static void log_msg(const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    char buf[512];
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    std::lock_guard<std::mutex> lock(g_log_mutex);
    OutputDebugStringA(buf);
    OutputDebugStringA("\n");
    std::fputs(buf, stdout);
    std::fputc('\n', stdout);
    std::fflush(stdout);
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

// BLE UUIDs — plain GUID structs, safe as statics
static constexpr int PROFILE_MESHTASTIC = 0;
static constexpr int PROFILE_MESHCORE = 1;

static const GUID SVC_UUID  = {0x6ba1b218,0x15a8,0x461f,{0x9f,0xa8,0x5d,0xca,0xe2,0x73,0xea,0xfd}};
static const GUID FR_UUID   = {0x2c55e69e,0x4993,0x11ed,{0xb8,0x78,0x02,0x42,0xac,0x12,0x00,0x02}};
static const GUID TR_UUID   = {0xf75c76d2,0x129e,0x4dad,{0xa1,0xdd,0x78,0x66,0x12,0x44,0x01,0xe7}};
static const GUID FN_UUID   = {0xed9da18c,0xa800,0x4f66,{0xa6,0x70,0xaa,0x75,0x47,0xe3,0x44,0x53}};

static const GUID MC_SVC_UUID = {0x6e400001,0xb5a3,0xf393,{0xe0,0xa9,0xe5,0x0e,0x24,0xdc,0xca,0x9e}};
static const GUID MC_RX_UUID  = {0x6e400002,0xb5a3,0xf393,{0xe0,0xa9,0xe5,0x0e,0x24,0xdc,0xca,0x9e}};
static const GUID MC_TX_UUID  = {0x6e400003,0xb5a3,0xf393,{0xe0,0xa9,0xe5,0x0e,0x24,0xdc,0xca,0x9e}};

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

struct ReadFromRadioResult {
    int status;
    std::vector<uint8_t> data;
};

// All state heap-allocated — created in meshble_init, on the worker thread
static BleState* g_ble = nullptr;

// Plain C types — safe as statics
static std::atomic<bool> g_initialized{false};
static std::atomic<bool> g_connected{false};
static std::atomic<bool> g_notifications_active{false};
// Write option auto-detection: -1=unknown, 0=WriteWithResponse, 1=WriteWithoutResponse
static std::atomic<int> g_write_option{-1};
static std::atomic<int> g_profile{PROFILE_MESHTASTIC};
static std::atomic<meshble_device_cb> g_device_callback{nullptr};
static std::atomic<meshble_data_cb> g_data_callback{nullptr};
static std::atomic<meshble_state_cb> g_state_callback{nullptr};
static std::atomic<meshble_passkey_request_cb> g_passkey_request_callback{nullptr};

static std::mutex g_pairing_mutex;
static std::condition_variable g_pairing_cv;
static bool g_pairing_request_active = false;
static bool g_pairing_response_ready = false;
static bool g_pairing_request_cancelled = false;
static std::string g_pairing_request_pin;

static std::mutex g_scan_cache_mutex;
static std::unordered_map<uint64_t, std::string> g_scan_names;
static std::unordered_set<uint64_t> g_scan_matched_addresses;

static int active_profile() {
    return g_profile.load() == PROFILE_MESHCORE ? PROFILE_MESHCORE : PROFILE_MESHTASTIC;
}

static guid active_service_uuid() {
    return active_profile() == PROFILE_MESHCORE ? to_guid(MC_SVC_UUID) : to_guid(SVC_UUID);
}

static bool guid_equals(guid const& left, guid const& right) {
    return std::memcmp(&left, &right, sizeof(guid)) == 0;
}

static bool advertisement_has_service(
        BluetoothLEAdvertisement const& advertisement,
        guid const& service_uuid) {
    for (auto const& uuid : advertisement.ServiceUuids()) {
        if (guid_equals(uuid, service_uuid)) {
            return true;
        }
    }
    return false;
}

static bool has_utf8_sequence(std::string const& value, std::initializer_list<unsigned char> sequence) {
    if (sequence.size() == 0 || value.size() < sequence.size()) {
        return false;
    }

    std::vector<unsigned char> needle(sequence);
    for (size_t i = 0; i <= value.size() - needle.size(); i++) {
        bool match = true;
        for (size_t j = 0; j < needle.size(); j++) {
            if (static_cast<unsigned char>(value[i + j]) != needle[j]) {
                match = false;
                break;
            }
        }
        if (match) {
            return true;
        }
    }
    return false;
}

static int scan_name_quality(std::string const& value) {
    int score = 0;
    for (unsigned char ch : value) {
        if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
            score += 3;
        } else if (ch == '_' || ch == '-' || ch == ' ' || ch == '.') {
            score += 2;
        } else if (ch >= 0x20 && ch < 0x7f) {
            score += 1;
        } else if (ch < 0x20 || ch == 0x7f) {
            score -= 20;
        }
    }

    // U+FFFD replacement character and U+25A1 white square usually mean a
    // malformed or placeholder name. Do not let them overwrite better names.
    if (has_utf8_sequence(value, {0xef, 0xbf, 0xbd})
            || has_utf8_sequence(value, {0xe2, 0x96, 0xa1})) {
        score -= 30;
    }

    return score;
}

static bool is_better_scan_name(std::string const& candidate, std::string const& current) {
    if (candidate.empty()) {
        return false;
    }
    if (current.empty()) {
        return true;
    }

    int candidateScore = scan_name_quality(candidate);
    int currentScore = scan_name_quality(current);
    if (candidateScore != currentScore) {
        return candidateScore > currentScore;
    }
    return candidate.size() > current.size();
}

static guid active_inbound_uuid() {
    return active_profile() == PROFILE_MESHCORE ? to_guid(MC_TX_UUID) : to_guid(FR_UUID);
}

static guid active_outbound_uuid() {
    return active_profile() == PROFILE_MESHCORE ? to_guid(MC_RX_UUID) : to_guid(TR_UUID);
}

static bool active_has_notify_trigger() {
    return active_profile() != PROFILE_MESHCORE;
}

static const char* active_profile_name() {
    return active_profile() == PROFILE_MESHCORE ? "MeshCore Companion" : "Meshtastic";
}

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
        if (task) {
            try {
                task();
            } catch (const std::exception& e) {
                log_msg("[meshble] Worker task failed: %s", e.what());
            } catch (...) {
                log_msg("[meshble] Worker task failed: unknown");
            }
        }
    }

    winrt::uninit_apartment();
}

template<typename F>
auto run_on_worker(F&& func) -> decltype(func()) {
    using R = decltype(func());
    if (!g_worker_running.load()) {
        throw std::runtime_error("BLE worker is not running");
    }
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
    if (!g_worker_running.load()) {
        return;
    }
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

static std::string local_name_from_advertisement(BluetoothLEAdvertisement const& advertisement) {
    std::string shortenedName;

    for (auto const& section : advertisement.DataSections()) {
        auto type = section.DataType();
        if (type != 0x08 && type != 0x09) {
            continue;
        }

        auto data = buffer_to_bytes(section.Data());
        while (!data.empty() && data.back() == 0) {
            data.pop_back();
        }
        if (data.empty()) {
            continue;
        }

        std::string value(data.begin(), data.end());
        if (type == 0x09) {
            return value;
        }
        if (shortenedName.empty()) {
            shortenedName = value;
        }
    }

    return shortenedName;
}

static IBuffer bytes_to_buffer(const unsigned char* data, int length) {
    auto writer = DataWriter();
    writer.WriteBytes(array_view<const uint8_t>(data, data + length));
    return writer.DetachBuffer();
}

static const char* gatt_status_str(GattCommunicationStatus s) {
    switch (s) {
        case GattCommunicationStatus::Success:       return "Success";
        case GattCommunicationStatus::Unreachable:   return "Unreachable";
        case GattCommunicationStatus::ProtocolError:  return "ProtocolError";
        case GattCommunicationStatus::AccessDenied:  return "AccessDenied";
        default:                                     return "Unknown";
    }
}

static void clear_pending_pairing_request_locked() {
    g_pairing_request_active = false;
    g_pairing_response_ready = false;
    g_pairing_request_cancelled = false;
    g_pairing_request_pin.clear();
}

static void cancel_pending_pairing_request() {
    std::lock_guard<std::mutex> lock(g_pairing_mutex);
    if (!g_pairing_request_active) {
        return;
    }
    g_pairing_request_active = false;
    g_pairing_response_ready = false;
    g_pairing_request_cancelled = true;
    g_pairing_request_pin.clear();
    g_pairing_cv.notify_all();
}

struct async_timeout_error : std::runtime_error {
    using std::runtime_error::runtime_error;
};

static constexpr auto GATT_READ_TIMEOUT = std::chrono::seconds(5);
static constexpr auto GATT_WRITE_TIMEOUT = std::chrono::seconds(10);
static constexpr auto GATT_CLEANUP_TIMEOUT = std::chrono::seconds(3);

template<typename AsyncOp>
static auto await_async_result(
        AsyncOp op,
        std::chrono::milliseconds timeout,
        const char* label) -> decltype(op.GetResults()) {
    using Result = decltype(op.GetResults());

    if (timeout.count() <= 0) {
        throw async_timeout_error(std::string(label) + " timed out before start");
    }

    auto promise = std::make_shared<std::promise<Result>>();
    auto future = promise->get_future();

    op.Completed([promise, label](AsyncOp const& async, AsyncStatus status) mutable {
        try {
            switch (status) {
                case AsyncStatus::Completed:
                    promise->set_value(async.GetResults());
                    break;
                case AsyncStatus::Canceled:
                    promise->set_exception(std::make_exception_ptr(
                            async_timeout_error(std::string(label) + " cancelled")));
                    break;
                case AsyncStatus::Error:
                    async.GetResults();
                    promise->set_exception(std::make_exception_ptr(
                            std::runtime_error(std::string(label) + " completed with error")));
                    break;
                default:
                    promise->set_exception(std::make_exception_ptr(
                            std::runtime_error(std::string(label) + " completed with unexpected status")));
                    break;
            }
        } catch (...) {
            try {
                promise->set_exception(std::current_exception());
            } catch (...) {}
        }
    });

    if (future.wait_for(timeout) != std::future_status::ready) {
        try { op.Cancel(); } catch (...) {}
        throw async_timeout_error(std::string(label) + " timed out after "
                + std::to_string(timeout.count()) + "ms");
    }

    return future.get();
}

/**
 * Waits for the application to provide a BLE passkey.
 * Uses a dedicated condition variable instead of run_on_worker(), because the
 * worker thread is already blocked inside PairAsync() while WinRT is waiting
 * for the PairingRequested handler to complete.
 */
static bool await_user_passkey(std::string const& address, std::string& out_pin) {
    constexpr auto PASSKEY_RESPONSE_TIMEOUT = std::chrono::minutes(2);

    meshble_passkey_request_cb callback = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_pairing_mutex);
        clear_pending_pairing_request_locked();
        g_pairing_request_active = true;
        callback = g_passkey_request_callback.load();
    }

    if (!callback) {
        log_msg("[meshble] PairingRequested ProvidePin for %s, but no Java callback is registered",
                address.c_str());
        cancel_pending_pairing_request();
        return false;
    }

    try {
        callback(address.c_str());
    } catch (const std::exception& e) {
        log_msg("[meshble] Java passkey callback failed: %s", e.what());
        cancel_pending_pairing_request();
        return false;
    } catch (...) {
        log_msg("[meshble] Java passkey callback failed: unknown");
        cancel_pending_pairing_request();
        return false;
    }

    std::unique_lock<std::mutex> lock(g_pairing_mutex);
    bool ready = g_pairing_cv.wait_for(lock, PASSKEY_RESPONSE_TIMEOUT, [] {
        return !g_pairing_request_active || g_pairing_response_ready || g_pairing_request_cancelled;
    });
    if (!ready || g_pairing_request_cancelled || !g_pairing_response_ready) {
        clear_pending_pairing_request_locked();
        return false;
    }

    out_pin = g_pairing_request_pin;
    clear_pending_pairing_request_locked();
    return !out_pin.empty();
}

/**
 * Ensures the device is paired before GATT discovery/write.
 * Windows can return a BluetoothLEDevice and even expose services for an
 * unpaired device, but the first WriteValueAsync then fails with AccessDenied.
 * Pairing up front keeps connect() and first write on the same contract.
 */
static bool ensure_paired(
        BluetoothLEDevice const& device,
        std::string const& address,
        std::chrono::milliseconds timeout) {
    auto device_info = device.DeviceInformation();
    if (device_info == nullptr) {
        log_msg("[meshble] DeviceInformation is unavailable for %s", address.c_str());
        return true;
    }

    auto pairing = device_info.Pairing();
    if (pairing.IsPaired()) {
        return true;
    }

    // Do not fail closed here: some devices report CanPair=false but still allow
    // unencrypted access, and we do not want to break already-working hardware.
    if (!pairing.CanPair()) {
        log_msg("[meshble] %s is not paired and cannot be custom-paired here; continuing without pairing",
                address.c_str());
        return true;
    }

    log_msg("[meshble] %s is not paired; starting custom pairing", address.c_str());

    auto custom_pairing = pairing.Custom();
    auto pairing_revoker = custom_pairing.PairingRequested(winrt::auto_revoke,
        [address](DeviceInformationCustomPairing const&,
                  DevicePairingRequestedEventArgs const& args) {
            auto deferral = args.GetDeferral();
            try {
                switch (args.PairingKind()) {
                    case DevicePairingKinds::ConfirmOnly:
                        log_msg("[meshble] PairingRequested ConfirmOnly for %s", address.c_str());
                        args.Accept();
                        break;

                    case DevicePairingKinds::ProvidePin: {
                        log_msg("[meshble] PairingRequested ProvidePin for %s", address.c_str());
                        std::string pin;
                        if (await_user_passkey(address, pin)) {
                            args.Accept(to_hstring(pin));
                        } else {
                            log_msg("[meshble] PairingRequested ProvidePin cancelled/timed out for %s",
                                    address.c_str());
                        }
                        break;
                    }

                    default:
                        log_msg("[meshble] Unsupported PairingKind %d for %s",
                                (int)args.PairingKind(), address.c_str());
                        break;
                }
            } catch (const hresult_error& e) {
                log_msg("[meshble] PairingRequested handler failed: %ls", e.message().c_str());
            } catch (...) {
                log_msg("[meshble] PairingRequested handler failed: unknown");
            }
            deferral.Complete();
        });

    auto result = await_async_result(
            custom_pairing.PairAsync(
                    DevicePairingKinds::ConfirmOnly | DevicePairingKinds::ProvidePin),
            timeout,
            "PairAsync");

    switch (result.Status()) {
        case DevicePairingResultStatus::Paired:
        case DevicePairingResultStatus::AlreadyPaired:
            log_msg("[meshble] Pairing completed for %s", address.c_str());
            return true;

        default:
            log_msg("[meshble] PairAsync failed for %s: status=%d",
                    address.c_str(), (int)result.Status());
            return false;
    }
}

static GattCharacteristic find_characteristic(
        GattDeviceService const& service,
        guid const& uuid,
        std::chrono::milliseconds timeout) {
    auto result = await_async_result(
            service.GetCharacteristicsForUuidAsync(uuid, BluetoothCacheMode::Uncached),
            timeout,
            "GetCharacteristicsForUuidAsync");
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
            auto result = await_async_result(
                    g_ble->from_radio.ReadValueAsync(BluetoothCacheMode::Uncached),
                    GATT_READ_TIMEOUT,
                    "drain ReadValueAsync");
            if (result.Status() != GattCommunicationStatus::Success) break;
            auto data = buffer_to_bytes(result.Value());
            if (data.empty()) break;
            auto callback = g_data_callback.load();
            if (callback) {
                callback(data.data(), (int)data.size());
            }
        } catch (...) { break; }
    }
}

/* ==================== Notification Handlers ==================== */

static void on_from_radio_value_changed(
    GattCharacteristic const&, GattValueChangedEventArgs const& args) {
    try {
        auto data = buffer_to_bytes(args.CharacteristicValue());
        auto callback = g_data_callback.load();
        if (!data.empty() && callback) {
            callback(data.data(), (int)data.size());
        }
    } catch (const hresult_error& e) {
        log_msg("[meshble] fromRadio notification failed: %ls", e.message().c_str());
    } catch (const std::exception& e) {
        log_msg("[meshble] fromRadio notification failed: %s", e.what());
    } catch (...) {
        log_msg("[meshble] fromRadio notification failed: unknown");
    }
}

static void on_from_num_value_changed(
    GattCharacteristic const&, GattValueChangedEventArgs const&) {
    try {
        if (!g_notifications_active.load()) {
            return;
        }
        post_to_worker([] { drain_from_radio(); });
    } catch (const std::exception& e) {
        log_msg("[meshble] fromNum notification failed: %s", e.what());
    } catch (...) {
        log_msg("[meshble] fromNum notification failed: unknown");
    }
}

static void on_connection_status_changed(
    BluetoothLEDevice const& device, IInspectable const&) {
    try {
        if (device.ConnectionStatus() == BluetoothConnectionStatus::Disconnected) {
            g_connected = false;
            log_msg("[meshble] Device disconnected");
            auto callback = g_state_callback.load();
            if (callback) {
                callback(1, nullptr);
            }
        }
    } catch (const hresult_error& e) {
        log_msg("[meshble] ConnectionStatusChanged failed: %ls", e.message().c_str());
    } catch (const std::exception& e) {
        log_msg("[meshble] ConnectionStatusChanged failed: %s", e.what());
    } catch (...) {
        log_msg("[meshble] ConnectionStatusChanged failed: unknown");
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
    g_device_callback.store(nullptr);
    {
        std::lock_guard<std::mutex> lock(g_scan_cache_mutex);
        g_scan_names.clear();
        g_scan_matched_addresses.clear();
    }
    log_msg("[meshble] Scan stopped");
}

static void do_disconnect() {
    g_connected = false;
    g_notifications_active = false;
    g_write_option = -1;
    cancel_pending_pairing_request();
    if (!g_ble) return;

    try {
        if (g_ble->from_radio != nullptr) {
            try {
                g_ble->from_radio.ValueChanged(g_ble->from_radio_notify_token);
                await_async_result(
                        g_ble->from_radio.WriteClientCharacteristicConfigurationDescriptorAsync(
                                GattClientCharacteristicConfigurationDescriptorValue::None),
                        GATT_CLEANUP_TIMEOUT,
                        "Disable notifications");
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

    cancel_pending_pairing_request();

    try {
        run_on_worker([] {
            do_disconnect();
            do_stop_scan();
            delete g_ble;
            g_ble = nullptr;
        });
    } catch (...) {}

    g_device_callback.store(nullptr);
    g_data_callback.store(nullptr);
    g_state_callback.store(nullptr);
    g_passkey_request_callback.store(nullptr);

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
                auto radios = await_async_result(
                        Radio::GetRadiosAsync(),
                        GATT_READ_TIMEOUT,
                        "GetRadiosAsync");
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

MESHBLE_API void meshble_set_profile(int profile) {
    if (profile == PROFILE_MESHCORE) {
        g_profile = profile;
    } else {
        g_profile = PROFILE_MESHTASTIC;
    }
}

MESHBLE_API int meshble_start_scan(meshble_device_cb callback) {
    if (!g_initialized || !callback) return -1;
    try {
        return run_on_worker([callback]() -> int {
            do_stop_scan();
            g_device_callback.store(callback);
            try {
                g_ble->watcher = BluetoothLEAdvertisementWatcher();
                g_ble->watcher.ScanningMode(BluetoothLEScanningMode::Active);
                auto service_uuid = active_service_uuid();

                g_ble->watcher_received_token = g_ble->watcher.Received(
                    [service_uuid](BluetoothLEAdvertisementWatcher const&,
                       BluetoothLEAdvertisementReceivedEventArgs const& args) {
                        try {
                            auto callback = g_device_callback.load();
                            if (!callback) return;
                            auto raw_addr = args.BluetoothAddress();
                            auto advertisement = args.Advertisement();
                            std::string sectionName = local_name_from_advertisement(advertisement);
                            std::string winrtName;
                            auto name = advertisement.LocalName();
                            if (!name.empty()) winrtName = winrt::to_string(name);
                            std::string nameStr = is_better_scan_name(sectionName, winrtName)
                                    ? sectionName
                                    : winrtName;

                            bool hasTargetService = advertisement_has_service(advertisement, service_uuid);
                            bool shouldNotify = false;
                            std::string displayName;
                            {
                                std::lock_guard<std::mutex> lock(g_scan_cache_mutex);
                                auto currentName = g_scan_names.find(raw_addr);
                                if (currentName == g_scan_names.end()
                                        || is_better_scan_name(nameStr, currentName->second)) {
                                    g_scan_names[raw_addr] = nameStr;
                                }

                                bool knownTarget = g_scan_matched_addresses.find(raw_addr)
                                        != g_scan_matched_addresses.end();
                                if (hasTargetService) {
                                    g_scan_matched_addresses.insert(raw_addr);
                                    knownTarget = true;
                                    shouldNotify = true;
                                } else if (knownTarget && !nameStr.empty()) {
                                    shouldNotify = true;
                                }

                                auto cachedName = g_scan_names.find(raw_addr);
                                if (cachedName != g_scan_names.end()) {
                                    displayName = cachedName->second;
                                }
                            }

                            if (!shouldNotify) return;
                            auto addr = mac_to_string(raw_addr);
                            callback(addr.c_str(),
                                displayName.empty() ? nullptr : displayName.c_str(),
                                args.RawSignalStrengthInDBm());
                        } catch (const hresult_error& e) {
                            log_msg("[meshble] Advertisement callback failed: %ls", e.message().c_str());
                        } catch (const std::exception& e) {
                            log_msg("[meshble] Advertisement callback failed: %s", e.what());
                        } catch (...) {
                            log_msg("[meshble] Advertisement callback failed: unknown");
                        }
                    });

                g_ble->watcher_stopped_token = g_ble->watcher.Stopped(
                    [](BluetoothLEAdvertisementWatcher const&,
                       BluetoothLEAdvertisementWatcherStoppedEventArgs const& args) {
                        try {
                            log_msg("[meshble] Watcher stopped, error: %d", (int)args.Error());
                        } catch (...) {}
                    });

                g_ble->watcher.Start();
                log_msg("[meshble] Scan started (software service filter)");
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
    clear_last_error();
    if (!g_initialized) {
        set_last_error("Windows BLE backend is not initialized");
        return -1;
    }
    if (!address) {
        set_last_error("BLE address is missing");
        return -1;
    }
    std::string addr(address);
    auto step_timeout = std::chrono::milliseconds(timeout_ms > 0 ? timeout_ms : 10000);
    auto pairing_timeout = step_timeout;
    auto minimum_pairing_timeout = std::chrono::minutes(2) + std::chrono::seconds(5);
    if (pairing_timeout < minimum_pairing_timeout) {
        pairing_timeout = minimum_pairing_timeout;
    }

    try {
        return run_on_worker([addr, step_timeout, pairing_timeout]() -> int {
            do_disconnect();
            uint64_t mac = string_to_mac(addr.c_str());
            if (mac == 0) {
                set_last_error("Invalid BLE address: " + addr);
                return -2;
            }

            try {
                log_msg("[meshble] Connecting to %s ...", addr.c_str());

                log_msg("[meshble] Resolving device object...");
                auto dev = await_async_result(
                        BluetoothLEDevice::FromBluetoothAddressAsync(mac),
                        step_timeout,
                        "FromBluetoothAddressAsync");
                if (dev == nullptr) {
                    set_last_error("Device not found: " + addr);
                    log_msg("[meshble] Device not found");
                    return -2;
                }

                if (!ensure_paired(dev, addr, pairing_timeout)) {
                    set_last_error("BLE pairing not completed or rejected for " + addr);
                    try { dev.Close(); } catch (...) {}
                    do_disconnect();
                    return -4;
                }

                // Re-open after pairing so the next GATT requests run against a fresh
                // WinRT device object with updated authentication state.
                try { dev.Close(); } catch (...) {}
                log_msg("[meshble] Re-opening device after pairing...");
                dev = await_async_result(
                        BluetoothLEDevice::FromBluetoothAddressAsync(mac),
                        step_timeout,
                        "FromBluetoothAddressAsync(reopen)");
                if (dev == nullptr) {
                    set_last_error("Device not found after pairing: " + addr);
                    log_msg("[meshble] Device not found after pairing");
                    return -2;
                }

                g_ble->device = dev;
                g_ble->connection_status_token = g_ble->device.ConnectionStatusChanged(on_connection_status_changed);

                log_msg("[meshble] Discovering GATT services...");
                auto svc_result = await_async_result(
                        g_ble->device.GetGattServicesForUuidAsync(
                                active_service_uuid(),
                                BluetoothCacheMode::Uncached),
                        step_timeout,
                        "GetGattServicesForUuidAsync");
                if (svc_result.Status() == GattCommunicationStatus::AccessDenied) {
                    set_last_error("Access denied while discovering GATT service for " + addr);
                    do_disconnect(); return -4;
                }
                if (svc_result.Status() != GattCommunicationStatus::Success || svc_result.Services().Size() == 0) {
                    set_last_error(std::string("GATT service discovery failed for ")
                            + addr
                            + " profile="
                            + active_profile_name()
                            + " status="
                            + gatt_status_str(svc_result.Status())
                            + " services="
                            + std::to_string(svc_result.Services().Size()));
                    do_disconnect(); return -3;
                }
                g_ble->service = svc_result.Services().GetAt(0);

                log_msg("[meshble] Discovering characteristics...");
                g_ble->from_radio = find_characteristic(g_ble->service, active_inbound_uuid(), step_timeout);
                g_ble->to_radio   = find_characteristic(g_ble->service, active_outbound_uuid(), step_timeout);
                g_ble->from_num   = active_has_notify_trigger()
                        ? find_characteristic(g_ble->service, to_guid(FN_UUID), step_timeout)
                        : nullptr;

                if (!g_ble->to_radio || !g_ble->from_radio) {
                    set_last_error(std::string("Required GATT characteristics not found for ")
                            + addr
                            + " profile="
                            + active_profile_name());
                    log_msg("[meshble] Required characteristics not found");
                    do_disconnect(); return -3;
                }

                if (active_has_notify_trigger()) {
                    // Meshtastic fromRadio notifications are unreliable on many Windows BLE adapters:
                    // keep the existing Java-side polling path.
                    g_notifications_active = false;
                    log_msg("[meshble] inbound: using polling only (native drains disabled)");
                } else {
                    auto status = await_async_result(
                            g_ble->from_radio.WriteClientCharacteristicConfigurationDescriptorAsync(
                                    GattClientCharacteristicConfigurationDescriptorValue::Notify),
                            step_timeout,
                            "Enable notifications");
                    if (status == GattCommunicationStatus::Success) {
                        g_ble->from_radio_notify_token =
                                g_ble->from_radio.ValueChanged(on_from_radio_value_changed);
                        g_notifications_active = true;
                        log_msg("[meshble] inbound: using notifications");
                    } else {
                        g_notifications_active = false;
                        log_msg("[meshble] inbound notifications unavailable; Java polling fallback");
                    }
                }

                g_connected = true;
                log_msg("[meshble] Connected (notifications=%s)", g_notifications_active.load() ? "yes" : "polling");
                if (auto callback = g_state_callback.load()) {
                    callback(0, nullptr);
                }
                return 0;

            } catch (const async_timeout_error& e) {
                set_last_error(std::string("Connect timeout for ") + addr + ": " + e.what());
                log_msg("[meshble] Connect timeout: %s", e.what());
                do_disconnect(); return -1;
            } catch (const hresult_error& e) {
                set_last_error(std::string("WinRT connect error for ")
                        + addr
                        + ": "
                        + winrt::to_string(e.message()));
                log_msg("[meshble] Connect error: %ls", e.message().c_str());
                do_disconnect(); return -3;
            } catch (const std::exception& e) {
                set_last_error(std::string("Connect error for ") + addr + ": " + e.what());
                do_disconnect(); return -3;
            } catch (...) {
                set_last_error("Unknown connect error for " + addr);
                do_disconnect(); return -3;
            }
        });
    } catch (const std::exception& e) {
        set_last_error(std::string("Connect worker error for ") + addr + ": " + e.what());
        return -3;
    } catch (...) {
        set_last_error("Unknown connect worker error for " + addr);
        return -3;
    }
}

MESHBLE_API const char* meshble_get_last_error(void) {
    static thread_local std::string copy;
    copy = get_last_error_copy();
    return copy.empty() ? nullptr : copy.c_str();
}

MESHBLE_API void meshble_disconnect(void) {
    if (!g_initialized) return;
    g_connected = false;
    g_notifications_active = false;
    cancel_pending_pairing_request();
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
                int opt = g_write_option.load();

                if (opt <= 0) {
                    // Try WriteWithResponse first (or if already known to work)
                    auto r = await_async_result(
                            g_ble->to_radio.WriteValueAsync(buf, GattWriteOption::WriteWithResponse),
                            GATT_WRITE_TIMEOUT,
                            "WriteValueAsync(WithResponse)");
                    if (r == GattCommunicationStatus::Success) {
                        if (opt == -1) {
                            g_write_option = 0;
                            log_msg("[meshble] writeToRadio: WriteWithResponse works");
                        }
                        return 0;
                    }
                    if (opt == 0) {
                        // Previously worked but now failed
                        log_msg("[meshble] writeToRadio failed: %s", gatt_status_str(r));
                        return (r == GattCommunicationStatus::AccessDenied) ? -2 : -1;
                    }
                    // opt == -1: first attempt failed, try WriteWithoutResponse
                    log_msg("[meshble] writeToRadio WriteWithResponse failed: %s, trying WriteWithoutResponse",
                            gatt_status_str(r));
                    // Remember AccessDenied from first attempt
                    bool first_access_denied = (r == GattCommunicationStatus::AccessDenied);
                    buf = bytes_to_buffer(copy.data(), (int)copy.size());

                    // Try WriteWithoutResponse
                    auto r2 = await_async_result(
                            g_ble->to_radio.WriteValueAsync(buf, GattWriteOption::WriteWithoutResponse),
                            GATT_WRITE_TIMEOUT,
                            "WriteValueAsync(WithoutResponse)");
                    if (r2 == GattCommunicationStatus::Success) {
                        g_write_option = 1;
                        log_msg("[meshble] writeToRadio: WriteWithoutResponse works");
                        return 0;
                    }
                    log_msg("[meshble] writeToRadio WriteWithoutResponse failed: %s", gatt_status_str(r2));
                    if (r2 == GattCommunicationStatus::AccessDenied || first_access_denied) return -2;
                    return -1;
                }

                // opt == 1: WriteWithoutResponse known to work
                auto r2 = await_async_result(
                        g_ble->to_radio.WriteValueAsync(buf, GattWriteOption::WriteWithoutResponse),
                        GATT_WRITE_TIMEOUT,
                        "WriteValueAsync(WithoutResponse)");
                if (r2 == GattCommunicationStatus::Success) {
                    return 0;
                }
                log_msg("[meshble] writeToRadio WriteWithoutResponse failed: %s", gatt_status_str(r2));
                return (r2 == GattCommunicationStatus::AccessDenied) ? -2 : -1;
            } catch (const hresult_error& e) {
                log_msg("[meshble] writeToRadio exception: %ls", e.message().c_str());
                return -1;
            } catch (const std::exception& e) {
                log_msg("[meshble] writeToRadio exception: %s", e.what());
                return -1;
            } catch (...) {
                log_msg("[meshble] writeToRadio unknown exception");
                return -1;
            }
        });
    } catch (...) { return -1; }
}

MESHBLE_API int meshble_read_from_radio(unsigned char* buffer, int buf_size, int* out_len) {
    if (!g_connected || !buffer || buf_size <= 0 || !out_len) return -1;
    *out_len = 0;
    try {
        auto read = run_on_worker([]() -> ReadFromRadioResult {
            if (!g_connected || !g_ble || g_ble->from_radio == nullptr) return {-1, {}};
            try {
                auto result = await_async_result(
                        g_ble->from_radio.ReadValueAsync(BluetoothCacheMode::Uncached),
                        GATT_READ_TIMEOUT,
                        "ReadValueAsync");
                if (result.Status() != GattCommunicationStatus::Success) return {-1, {}};
                auto data = buffer_to_bytes(result.Value());
                return {0, std::move(data)};
            } catch (...) { return {-1, {}}; }
        });

        if (read.status != 0 || read.data.empty()) {
            return read.status;
        }

        // JNA owns these output buffers for the duration of this native call.
        // Keep raw writes on the original call thread; the WinRT worker returns
        // an owned vector instead of touching Java/JNA memory cross-thread.
        int n = (int)read.data.size() < buf_size ? (int)read.data.size() : buf_size;
        memcpy(buffer, read.data.data(), n);
        *out_len = n;
        return 0;
    } catch (...) { return -1; }
}

MESHBLE_API void meshble_set_from_radio_listener(meshble_data_cb callback) { g_data_callback.store(callback); }
MESHBLE_API void meshble_set_state_listener(meshble_state_cb callback) { g_state_callback.store(callback); }
MESHBLE_API void meshble_set_passkey_request_callback(meshble_passkey_request_cb callback) {
    g_passkey_request_callback.store(callback);
}

MESHBLE_API void meshble_respond_passkey(uint32_t passkey) {
    char pin_buf[7];
    snprintf(pin_buf, sizeof(pin_buf), "%06u", (unsigned)(passkey % 1000000U));

    {
        std::lock_guard<std::mutex> lock(g_pairing_mutex);
        if (!g_pairing_request_active) {
            return;
        }
        g_pairing_request_pin = pin_buf;
        g_pairing_response_ready = true;
        g_pairing_request_cancelled = false;
        g_pairing_request_active = false;
    }
    g_pairing_cv.notify_all();
}

MESHBLE_API void meshble_cancel_passkey(void) {
    cancel_pending_pairing_request();
}

MESHBLE_API int meshble_notifications_active(void) { return g_notifications_active.load() ? 1 : 0; }
