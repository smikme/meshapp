package com.meshtastic.client.connection;

/**
 * Фабрика stream parser-ов для TCP/Serial transport-ов.
 */
final class FrameParsers {

    private FrameParsers() {
    }

    /**
     * Создаёт parser для указанного формата фрейминга.
     *
     * @param frameFormat формат, выбранный пользователем или auto-detect-ом
     * @return новый parser с пустым внутренним состоянием
     */
    static StreamFrameParser create(FrameFormat frameFormat) {
        return switch (frameFormat) {
            case MESHTASTIC -> new FrameParser();
            case KISS -> new KissFrameParser();
            case MESHCORE_COMPANION -> new MeshCoreCompanionFrameParser();
            case AUTO -> new AutoDetectFrameParser();
        };
    }
}
