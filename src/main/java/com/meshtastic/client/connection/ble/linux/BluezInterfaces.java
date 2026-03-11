package com.meshtastic.client.connection.ble.linux;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.interfaces.DBusInterface;
import org.freedesktop.dbus.types.Variant;

import java.util.Map;

/**
 * D-Bus интерфейсы BlueZ для BLE-операций.
 * <p>
 * Каждый интерфейс соответствует BlueZ D-Bus API и используется
 * dbus-java для создания прокси-объектов.
 *
 * @see <a href="https://git.kernel.org/pub/scm/bluetooth/bluez.git/tree/doc">BlueZ D-Bus API</a>
 */
final class BluezInterfaces {

    private BluezInterfaces() {}

    /** BlueZ Adapter1: управление сканированием. */
    @DBusInterfaceName("org.bluez.Adapter1")
    interface Adapter1 extends DBusInterface {
        void StartDiscovery();
        void StopDiscovery();
        void SetDiscoveryFilter(Map<String, Variant<?>> filter);
    }

    /** BlueZ Device1: подключение/отключение/сопряжение. */
    @DBusInterfaceName("org.bluez.Device1")
    interface Device1 extends DBusInterface {
        void Connect();
        void Disconnect();
        void Pair();
    }

    /** BlueZ Adapter1: удаление устройства из кэша. */
    @DBusInterfaceName("org.bluez.Adapter1")
    interface Adapter1RemoveDevice extends DBusInterface {
        void RemoveDevice(org.freedesktop.dbus.DBusPath device);
    }

    /** BlueZ GattCharacteristic1: чтение/запись характеристик. */
    @DBusInterfaceName("org.bluez.GattCharacteristic1")
    interface GattCharacteristic1 extends DBusInterface {
        byte[] ReadValue(Map<String, Variant<?>> options);
        void WriteValue(byte[] value, Map<String, Variant<?>> options);
        void StartNotify();
        void StopNotify();
    }
}
