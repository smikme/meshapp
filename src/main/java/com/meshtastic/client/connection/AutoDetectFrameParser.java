package com.meshtastic.client.connection;

/**
 * Parser, который используется только во время auto-detect протокола.
 * <p>
 * Первый байт frame-а выбирает временный режим parsing-а: Meshtastic, KISS или
 * MeshCore Companion. Это не даёт raw Companion packets ошибочно поглощать
 * Meshtastic/KISS framing bytes и сохраняет один общий входной callback transport-а.
 */
final class AutoDetectFrameParser implements StreamFrameParser {

    private final FrameParser meshtasticParser = new FrameParser();
    private final KissFrameParser kissParser = new KissFrameParser();
    private final MeshCoreCompanionFrameParser companionParser = new MeshCoreCompanionFrameParser(true);
    private Mode mode = Mode.NONE;

    /**
     * Передаёт байт в parser, выбранный по текущему режиму auto-detect.
     *
     * @param b очередной байт из stream-а
     * @return готовый protocol payload или {@code null}, если frame ещё не завершён
     */
    @Override
    public byte[] processByte(byte b) {
        return switch (mode) {
            case NONE -> processUnclassifiedByte(b);
            case MESHTASTIC -> processMeshtasticByte(b);
            case KISS -> processKissByte(b);
            case COMPANION -> processCompanionByte(b);
        };
    }

    /**
     * Завершает raw MeshCore Companion packet по паузе чтения.
     * <p>
     * Для Meshtastic и KISS flush не применяется, потому что там границы frame-а
     * задаются явным заголовком/длиной или delimiter-ом.
     *
     * @return готовый Companion packet или {@code null}
     */
    @Override
    public byte[] flushPartialFrame() {
        if (mode != Mode.COMPANION) {
            return null;
        }
        byte[] frame = companionParser.flushPartialFrame();
        if (frame != null) {
            mode = Mode.NONE;
        }
        return frame;
    }

    /**
     * Проверяет, хранит ли один из вложенных parser-ов незавершённый frame.
     *
     * @return {@code true}, если есть частично принятые данные
     */
    @Override
    public boolean hasPartialFrame() {
        return meshtasticParser.hasPartialFrame()
                || kissParser.hasPartialFrame()
                || companionParser.hasPartialFrame();
    }

    /**
     * Сбрасывает все вложенные parser-ы и возвращает auto-detect в неопределённый режим.
     */
    @Override
    public void reset() {
        meshtasticParser.reset();
        kissParser.reset();
        companionParser.reset();
        mode = Mode.NONE;
    }

    /**
     * Классифицирует первый байт нового frame-а и передаёт его подходящему parser-у.
     */
    private byte[] processUnclassifiedByte(byte b) {
        if (b == FrameParser.START_BYTE_1) {
            mode = Mode.MESHTASTIC;
            return processMeshtasticByte(b);
        }
        if (b == KissFrameParser.FEND) {
            mode = Mode.KISS;
            return processKissByte(b);
        }
        mode = Mode.COMPANION;
        return processCompanionByte(b);
    }

    /**
     * Обрабатывает байт как часть Meshtastic serial/TCP frame-а.
     */
    private byte[] processMeshtasticByte(byte b) {
        byte[] frame = meshtasticParser.processByte(b);
        if (frame != null || !meshtasticParser.hasPartialFrame()) {
            mode = Mode.NONE;
        }
        return frame;
    }

    /**
     * Обрабатывает байт как часть KISS frame-а.
     */
    private byte[] processKissByte(byte b) {
        byte[] frame = kissParser.processByte(b);
        if (frame != null || !kissParser.hasPartialFrame()) {
            mode = Mode.NONE;
        }
        return frame;
    }

    /**
     * Обрабатывает байт как часть raw MeshCore Companion packet-а.
     */
    private byte[] processCompanionByte(byte b) {
        byte[] frame = companionParser.processByte(b);
        if (frame != null || !companionParser.hasPartialFrame()) {
            mode = Mode.NONE;
        }
        return frame;
    }

    private enum Mode {
        NONE,
        MESHTASTIC,
        KISS,
        COMPANION
    }
}
