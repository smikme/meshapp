package com.meshtastic.client.connection;

/**
 * Compatibility marker for existing Meshtastic transport implementations.
 * <p>
 * New protocol adapters should depend on {@link TransportConnection}. TCP,
 * Serial, and BLE classes still implement this interface so existing
 * Meshtastic-specific tests and call sites remain compatible.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface MeshtasticConnection extends TransportConnection {
}
