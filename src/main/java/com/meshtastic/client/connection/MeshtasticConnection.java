package com.meshtastic.client.connection;

/**
 * Совместимый маркер для существующих Meshtastic transport-реализаций.
 * <p>
 * Новые протокольные адаптеры должны зависеть от {@link TransportConnection}.
 * TCP/Serial/BLE классы пока продолжают реализовывать этот интерфейс, чтобы
 * существующие Meshtastic-специфичные тесты и call site-ы остались совместимыми.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public interface MeshtasticConnection extends TransportConnection {
}
