package com.meshtastic.client.connection;

import com.fazecast.jSerialComm.SerialPort;
import com.meshtastic.client.connection.serial.NativeSerialPort;
import com.meshtastic.client.connection.serial.NativeSerialPortFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

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
    private static final int READ_TIMEOUT_MS = 500;
    private static final int PORT_INIT_DELAY_MS = 500;

    private final String portName;
    private final int baudRate;

    private volatile NativeSerialPort nativePort;
    private volatile Consumer<byte[]> dataListener;
    private volatile ConnectionListener connectionListener;
    private volatile boolean running;
    private Thread readerThread;

    public SerialConnection(String portName, int baudRate) {
        this.portName = portName;
        this.baudRate = baudRate;
    }

    public SerialConnection(String portName) {
        this(portName, DEFAULT_BAUD_RATE);
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

            NativeSerialPort port = NativeSerialPortFactory.create();
            port.open(portName, baudRate, assertDtr);
            this.nativePort = port;

            Thread.sleep(PORT_INIT_DELAY_MS);
            port.drainInput();
            log.info("Connected to serial port {} at {} baud (native JNA, DTR={})",
                    portName, baudRate, assertDtr ? "on" : "off");

            running = true;
            readerThread = new Thread(this::readLoop, "serial-reader-" + portName);
            readerThread.setDaemon(true);
            readerThread.start();

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
        try {
            nativePort.write(data, 0, data.length);
            log.debug("Sent {} bytes to serial {}", data.length, portName);
        } catch (ConnectionException e) {
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
                int bytesRead = port.read(buf, buf.length, READ_TIMEOUT_MS);
                if (bytesRead < 0) {
                    if (running) {
                        log.info("Serial port {} read error (returned {})", portName, bytesRead);
                        errorMessage = "Serial port read error: " + portName;
                    }
                    break;
                }
                if (bytesRead == 0) {
                    // Таймаут — данных нет, проверяем что порт ещё открыт
                    if (!port.isOpen()) {
                        if (running) {
                            log.info("Serial port {} disconnected", portName);
                            errorMessage = "Serial port disconnected: " + portName;
                        }
                        break;
                    }
                    continue;
                }
                for (int i = 0; i < bytesRead; i++) {
                    byte[] packet = parser.processByte(buf[i]);
                    if (packet != null) {
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

        if (errorMessage != null) {
            ConnectionListener listener = connectionListener;
            if (listener != null) {
                listener.onConnectionError(errorMessage, errorCause);
            }
        }

        log.debug("Serial reader thread exiting for {}", portName);
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
