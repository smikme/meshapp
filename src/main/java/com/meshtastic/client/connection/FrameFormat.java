package com.meshtastic.client.connection;

import com.meshtastic.client.model.ProtocolType;

/**
 * Формат фрейминга, который transport применяет к непрерывному потоку байтов.
 * <p>
 * TCP и Serial не сохраняют границы протокольных сообщений, поэтому перед
 * запуском runtime-а transport выбирает парсер, соответствующий активному
 * протоколу. BLE обычно передаёт уже готовые payload-ы и этот enum не использует.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public enum FrameFormat {
    /** Meshtastic serial/TCP framing с заголовком {@code 0x94 0xC3}. */
    MESHTASTIC,

    /** Стандартный KISS TNC framing для MeshCore KISS modem protocol. */
    KISS,

    /** Raw MeshCore Companion packets без KISS-обёртки. */
    MESHCORE_COMPANION;

    /**
     * Возвращает формат фрейминга, который должен быть включён для указанного протокола.
     *
     * @param protocolType выбранный протокол
     * @return формат фрейминга для TCP/Serial transport-а
     */
    public static FrameFormat forProtocol(ProtocolType protocolType) {
        if (protocolType == ProtocolType.MESHCORE_KISS) {
            return KISS;
        }
        if (protocolType == ProtocolType.MESHCORE_COMPANION) {
            return MESHCORE_COMPANION;
        }
        return MESHTASTIC;
    }
}
