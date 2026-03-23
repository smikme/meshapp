package com.meshtastic.client.connection;

import com.fazecast.jSerialComm.SerialPort;
import com.meshtastic.client.connection.serial.NativeSerialPort;
import com.meshtastic.client.connection.serial.NativeSerialPortFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Подключение к Meshtastic-устройству через serial-порт (USB / Bluetooth SPP).
 * <p>
 * Использует JNA-обёртки ({@link NativeSerialPort}) для прямого доступа к serial-порту
 * через kernel32 (Windows) или libc/termios (macOS/Linux).
 * Ключевое отличие от jSerialComm: порт открывается <b>без активации DTR</b>,
 * что предотвращает сброс ESP32 на USB-Serial мостах (CH340, CP210x и др.).
 * <p>
 * jSerialComm используется только для обнаружения портов ({@code getCommPorts()})
 * и определения типа адаптера ({@code getDescriptivePortName()}).
 */
public class SerialConnection implements MeshtasticConnection {

    private static final Logger log = LoggerFactory.getLogger(SerialConnection.class);

    public static final int DEFAULT_BAUD_RATE = 115200;
    private static final int DEFAULT_READ_TIMEOUT_MS = 500;
    private static final int DEFAULT_PORT_INIT_DELAY_MS = 500;
    private static final long DEFAULT_WRITE_RESPONSE_TIMEOUT_MS = 15_000L;
    private static final long DEFAULT_READ_CALL_STALL_TIMEOUT_MS = 5_000L;
    private static final long DEFAULT_READ_WATCHDOG_PERIOD_MS = 1_000L;

    private final String portName;
    private final int baudRate;
    private final Supplier<NativeSerialPort> portFactory;
    private final LongSupplier currentTimeMillis;
    private final int readTimeoutMs;
    private final int portInitDelayMs;
    private final long writeResponseTimeoutMs;
    private final long readCallStallTimeoutMs;
    private final long readWatchdogPeriodMs;

    private volatile NativeSerialPort nativePort;
    private volatile Consumer<byte[]> dataListener;
    private volatile ConnectionListener connectionListener;
    private volatile boolean running;
    private volatile long lastReceiveAtMillis;
    private volatile long lastPacketReceiveAtMillis;
    private volatile long lastWriteAtMillis;
    private volatile boolean awaitingResponseAfterWrite;
    private volatile int readTimeoutsSinceWrite;
    private volatile long activeReadStartedAtMillis;
    private volatile boolean readCallInFlight;
    private Thread readerThread;
    private ScheduledExecutorService readWatchdogScheduler;
    private ScheduledFuture<?> readWatchdogFuture;
    private final AtomicBoolean terminalSignalSent = new AtomicBoolean();

    public SerialConnection(String portName, int baudRate) {
        this(portName, baudRate, NativeSerialPortFactory::create, System::currentTimeMillis,
                DEFAULT_READ_TIMEOUT_MS, DEFAULT_PORT_INIT_DELAY_MS, DEFAULT_WRITE_RESPONSE_TIMEOUT_MS,
                DEFAULT_READ_CALL_STALL_TIMEOUT_MS, DEFAULT_READ_WATCHDOG_PERIOD_MS);
    }

    public SerialConnection(String portName) {
        this(portName, DEFAULT_BAUD_RATE);
    }

    SerialConnection(String portName, int baudRate,
                     Supplier<NativeSerialPort> portFactory,
                     LongSupplier currentTimeMillis,
                     int readTimeoutMs,
                     int portInitDelayMs,
                     long writeResponseTimeoutMs) {
        this(portName, baudRate, portFactory, currentTimeMillis, readTimeoutMs, portInitDelayMs,
                writeResponseTimeoutMs, DEFAULT_READ_CALL_STALL_TIMEOUT_MS, DEFAULT_READ_WATCHDOG_PERIOD_MS);
    }

    SerialConnection(String portName, int baudRate,
                     Supplier<NativeSerialPort> portFactory,
                     LongSupplier currentTimeMillis,
                     int readTimeoutMs,
                     int portInitDelayMs,
                     long writeResponseTimeoutMs,
                     long readCallStallTimeoutMs,
                     long readWatchdogPeriodMs) {
        this.portName = portName;
        this.baudRate = baudRate;
        this.portFactory = portFactory;
        this.currentTimeMillis = currentTimeMillis;
        this.readTimeoutMs = readTimeoutMs;
        this.portInitDelayMs = portInitDelayMs;
        this.writeResponseTimeoutMs = writeResponseTimeoutMs;
        this.readCallStallTimeoutMs = readCallStallTimeoutMs;
        this.readWatchdogPeriodMs = readWatchdogPeriodMs;
    }

    @Override
    public void connect() throws ConnectionException {
        try {
            String desc = getDescriptivePortName(portName);
            log.info("Opening serial port: {} ({})", portName, desc);

            // USB-serial bridge (CH340/CP210x/FTDI): DTR нельзя → вызывает сброс ESP32
            // Native USB CDC (ESP32-S3/S2): DTR нужен → сигнал "хост подключён"
            boolean isUsbBridge = isUsbSerialBridge(portName, desc);
            boolean assertDtr = !isUsbBridge;

            NativeSerialPort port = portFactory.get();
            port.open(portName, baudRate, assertDtr);
            this.nativePort = port;

            Thread.sleep(portInitDelayMs);
            port.drainInput();
            log.info("Connected to serial port {} at {} baud (native JNA, DTR={})",
                    portName, baudRate, assertDtr ? "on" : "off");

            running = true;
            long now = currentTimeMillis.getAsLong();
            lastReceiveAtMillis = now;
            lastPacketReceiveAtMillis = now;
            lastWriteAtMillis = 0;
            awaitingResponseAfterWrite = false;
            readTimeoutsSinceWrite = 0;
            activeReadStartedAtMillis = 0;
            readCallInFlight = false;
            terminalSignalSent.set(false);
            readerThread = new Thread(this::readLoop, "serial-reader-" + portName);
            readerThread.setDaemon(true);
            readerThread.start();
            startReadWatchdog();

            ConnectionListener listener = connectionListener;
            if (listener != null) {
                listener.onConnected();
            }
        } catch (ConnectionException e) {
            closePort();
            throw e;
        } catch (Exception e) {
            closePort();
            throw new ConnectionException("Failed to open serial port " + portName + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void disconnect() {
        running = false;
        stopReadWatchdog();
        if (readerThread != null) {
            readerThread.interrupt();
            try {
                readerThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readerThread = null;
        }

        closePort();

        ConnectionListener listener = connectionListener;
        if (listener != null) {
            listener.onDisconnected();
        }
    }

    @Override
    public boolean isConnected() {
        NativeSerialPort port = nativePort;
        return port != null && port.isOpen() && running;
    }

    @Override
    public synchronized void sendBytes(byte[] data) {
        if (!isConnected()) {
            log.warn("Cannot send: serial port {} not connected", portName);
            return;
        }
        long writeStartedAt = currentTimeMillis.getAsLong();
        lastWriteAtMillis = writeStartedAt;
        awaitingResponseAfterWrite = true;
        readTimeoutsSinceWrite = 0;
        try {
            nativePort.write(data, 0, data.length);
            log.debug("Sent {} bytes to serial {}", data.length, portName);
        } catch (ConnectionException e) {
            awaitingResponseAfterWrite = false;
            log.error("Write failed to serial {}", portName, e);
            ConnectionListener listener = connectionListener;
            if (listener != null) {
                listener.onConnectionError("Write failed: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void setDataListener(Consumer<byte[]> listener) {
        this.dataListener = listener;
    }

    @Override
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    /**
     * Цикл чтения данных из serial-порта.
     * <p>
     * Использует {@link NativeSerialPort#read(byte[], int, int)} с таймаутом.
     * Возвращаемые значения: {@code >0} — прочитано байт, {@code 0} — таймаут,
     * {@code -1} — ошибка/порт закрыт.
     */
    private void readLoop() {
        log.debug("Serial reader thread started for {}", portName);
        FrameParser parser = new FrameParser();

        String errorMessage = null;
        Throwable errorCause = null;

        byte[] buf = new byte[1024];
        try {
            NativeSerialPort port = nativePort;
            if (port == null || !port.isOpen()) {
                throw new ConnectionException("Serial port " + portName + " closed before reader started");
            }
            log.debug("Serial reader ready, starting read loop for {}", portName);
            while (running && !Thread.currentThread().isInterrupted()) {
                int bytesRead;
                markReadCallStarted();
                try {
                    bytesRead = port.read(buf, buf.length, readTimeoutMs);
                } finally {
                    markReadCallCompleted();
                }
                if (bytesRead < 0) {
                    if (running) {
                        log.info("Serial port {} read error (returned {})", portName, bytesRead);
                        errorMessage = "Serial port read error: " + portName;
                    }
                    break;
                }
                if (bytesRead == 0) {
                    if (parser.hasPartialFrame()) {
                        log.debug("Resetting serial frame parser on {} after {} ms inter-byte gap",
                                portName, readTimeoutMs);
                        parser.reset();
                    }
                    // Таймаут — данных нет, проверяем что порт ещё открыт
                    if (!port.isOpen()) {
                        if (running) {
                            log.info("Serial port {} disconnected", portName);
                            errorMessage = "Serial port disconnected: " + portName;
                        }
                        break;
                    }
                    noteReadTimeoutWhileAwaitingResponse();
                    if (isReceiveStalledAfterWrite()) {
                        long now = currentTimeMillis.getAsLong();
                        long silentForMs = Math.max(0L, now - lastWriteAtMillis);
                        long sinceLastByteRxMs = Math.max(0L, now - lastReceiveAtMillis);
                        long sinceLastPacketRxMs = Math.max(0L, now - lastPacketReceiveAtMillis);
                        log.warn("Serial receive stalled on {} after write: silentFor={} ms, sinceLastByteRx={} ms, sinceLastPacketRx={} ms, readTimeouts={}, portOpen={}",
                                portName, silentForMs, sinceLastByteRxMs, sinceLastPacketRxMs, readTimeoutsSinceWrite, port.isOpen());
                        errorMessage = "Serial receive stalled after write: " + portName;
                        break;
                    }
                    continue;
                }
                markByteReceiveProgress();
                for (int i = 0; i < bytesRead; i++) {
                    byte[] packet = parser.processByte(buf[i]);
                    if (packet != null) {
                        markPacketReceiveProgress();
                        Consumer<byte[]> listener = dataListener;
                        if (listener != null) {
                            listener.accept(packet);
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                log.error("Serial reader error on {} [{}]: {}",
                        portName, e.getClass().getSimpleName(), e.getMessage(), e);
                errorMessage = "Read error on " + portName + ": " + e.getClass().getSimpleName()
                        + " — " + e.getMessage();
                errorCause = e;
            }
        }

        // Закрываем порт ДО оповещения слушателя, чтобы порт был освобождён
        // к моменту попытки переподключения через ReconnectService
        closePort();
        stopReadWatchdog();
        notifyConnectionErrorOnce(errorMessage, errorCause);

        log.debug("Serial reader thread exiting for {}", portName);
    }

    private void markByteReceiveProgress() {
        lastReceiveAtMillis = currentTimeMillis.getAsLong();
        if (awaitingResponseAfterWrite) {
            clearAwaitingResponseAfterWrite();
        }
    }

    private void markPacketReceiveProgress() {
        long now = currentTimeMillis.getAsLong();
        lastReceiveAtMillis = now;
        lastPacketReceiveAtMillis = now;
        clearAwaitingResponseAfterWrite();
    }

    private void clearAwaitingResponseAfterWrite() {
        awaitingResponseAfterWrite = false;
        readTimeoutsSinceWrite = 0;
    }

    private boolean isReceiveStalledAfterWrite() {
        if (!awaitingResponseAfterWrite) {
            return false;
        }
        long writeAt = lastWriteAtMillis;
        if (writeAt <= 0) {
            return false;
        }
        long lastReceiveAt = lastReceiveAtMillis;
        if (lastReceiveAt > writeAt) {
            clearAwaitingResponseAfterWrite();
            return false;
        }
        return currentTimeMillis.getAsLong() - writeAt >= writeResponseTimeoutMs;
    }

    private void noteReadTimeoutWhileAwaitingResponse() {
        if (awaitingResponseAfterWrite) {
            readTimeoutsSinceWrite++;
        }
    }

    private void markReadCallStarted() {
        readCallInFlight = true;
        activeReadStartedAtMillis = currentTimeMillis.getAsLong();
    }

    private void markReadCallCompleted() {
        readCallInFlight = false;
        activeReadStartedAtMillis = 0;
    }

    private void startReadWatchdog() {
        stopReadWatchdog();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "serial-read-watchdog-" + portName);
            t.setDaemon(true);
            return t;
        });
        readWatchdogScheduler = scheduler;
        readWatchdogFuture = scheduler.scheduleWithFixedDelay(
                this::runReadWatchdogSafely,
                readWatchdogPeriodMs,
                readWatchdogPeriodMs,
                TimeUnit.MILLISECONDS
        );
    }

    private void stopReadWatchdog() {
        ScheduledFuture<?> future = readWatchdogFuture;
        readWatchdogFuture = null;
        if (future != null) {
            future.cancel(false);
        }
        ScheduledExecutorService scheduler = readWatchdogScheduler;
        readWatchdogScheduler = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void runReadWatchdogSafely() {
        try {
            runReadWatchdog();
        } catch (Throwable t) {
            log.error("Serial read watchdog crashed on {}", portName, t);
        }
    }

    private void runReadWatchdog() {
        if (!running || !readCallInFlight) {
            return;
        }
        long startedAt = activeReadStartedAtMillis;
        if (startedAt <= 0) {
            return;
        }
        long now = currentTimeMillis.getAsLong();
        long blockedForMs = now - startedAt;
        if (blockedForMs < readCallStallTimeoutMs) {
            return;
        }

        long sinceLastByteRxMs = Math.max(0L, now - lastReceiveAtMillis);
        long sinceLastPacketRxMs = Math.max(0L, now - lastPacketReceiveAtMillis);
        long sinceLastWriteMs = lastWriteAtMillis > 0 ? Math.max(0L, now - lastWriteAtMillis) : -1L;
        Thread thread = readerThread;
        String threadState = thread != null ? thread.getState().name() : "null";
        log.warn("Serial read call appears stuck on {}: blockedFor={} ms, sinceLastByteRx={} ms, sinceLastPacketRx={} ms, sinceLastWrite={} ms, threadState={}",
                portName, blockedForMs, sinceLastByteRxMs, sinceLastPacketRxMs, sinceLastWriteMs, threadState);
        forceConnectionErrorFromWatchdog("Serial read loop stalled: " + portName);
    }

    private void forceConnectionErrorFromWatchdog(String message) {
        if (!running || !terminalSignalSent.compareAndSet(false, true)) {
            return;
        }
        running = false;
        stopReadWatchdog();
        Thread thread = readerThread;
        if (thread != null) {
            thread.interrupt();
        }
        closePort();

        ConnectionListener listener = connectionListener;
        if (listener != null) {
            listener.onConnectionError(message, null);
        }
    }

    private void notifyConnectionErrorOnce(String message, Throwable cause) {
        if (message == null || !terminalSignalSent.compareAndSet(false, true)) {
            return;
        }
        ConnectionListener listener = connectionListener;
        if (listener != null) {
            listener.onConnectionError(message, cause);
        }
    }

    /**
     * Получает описательное имя порта через jSerialComm (для логирования).
     * jSerialComm используется ТОЛЬКО для обнаружения портов, не для I/O.
     */
    private static String getDescriptivePortName(String systemName) {
        try {
            for (SerialPort port : SerialPort.getCommPorts()) {
                if (port.getSystemPortName().equals(systemName)) {
                    return port.getDescriptivePortName();
                }
            }
        } catch (Exception e) {
            log.debug("Failed to get descriptive name for {}", systemName, e);
        }
        return systemName;
    }

    /**
     * Определяет USB-serial мост по имени порта и описанию.
     * Мосты (CH340/CP210x/FTDI): DTR вызывает сброс ESP32 через auto-reset circuit.
     * Native USB CDC (usbmodem/ttyACM): DTR = сигнал "хост подключён".
     */
    private static boolean isUsbSerialBridge(String portName, String desc) {
        String lower = (portName + " " + desc).toLowerCase(java.util.Locale.ROOT);
        return lower.contains("usbserial") || lower.contains("ttyusb")
                || lower.contains("ch340") || lower.contains("ch341") || lower.contains("ch9102")
                || lower.contains("cp210") || lower.contains("ftdi");
    }

    private void closePort() {
        NativeSerialPort port = nativePort;
        nativePort = null;
        if (port != null && port.isOpen()) {
            port.close();
            log.info("Closed serial port {}", portName);
        }
    }
}
