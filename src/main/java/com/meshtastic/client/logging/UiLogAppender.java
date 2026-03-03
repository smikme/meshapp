package com.meshtastic.client.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.meshtastic.client.model.LogEntry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Logback appender — складывает события в in-memory буфер для отображения в UI.
 * Регистрируется в logback.xml. Доступ к буферу через статические методы.
 */
public class UiLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final int MAX_ENTRIES = 5000;

    @SuppressWarnings("PMD.LooseCoupling")
    private static final List<LogEntry> buffer = new CopyOnWriteArrayList<>();
    private static volatile Consumer<LogEntry> liveListener;

    @Override
    protected void append(ILoggingEvent event) {
        LogEntry entry = new LogEntry(
                event.getTimeStamp(),
                event.getLevel().toString(),
                event.getFormattedMessage()
        );

        if (buffer.size() >= MAX_ENTRIES) {
            buffer.remove(0);
        }
        buffer.add(entry);

        Consumer<LogEntry> listener = liveListener;
        if (listener != null) {
            listener.accept(entry);
        }
    }

    /** Все накопленные записи */
    public static List<LogEntry> getBuffer() {
        return List.copyOf(buffer);
    }

    /** Подписаться на новые события в реальном времени */
    public static void setLiveListener(Consumer<LogEntry> listener) {
        liveListener = listener;
    }

    /** Снять подписку */
    public static void clearLiveListener() {
        liveListener = null;
    }

    /** Очистить буфер */
    public static void clearBuffer() {
        buffer.clear();
    }
}
