package com.meshtastic.client.connection;

import com.fazecast.jSerialComm.SerialPort;
import com.meshtastic.client.platform.OsDetect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * Подключение к Meshtastic-устройству через serial-порт (USB / Bluetooth SPP).
 * <p>
 * Использует jSerialComm для работы с serial-портом. Структура зеркально повторяет
 * {@link TcpConnection}: daemon reader-поток, volatile-поля, synchronized sendBytes().
 * Протокол фрейминга (FrameParser) идентичен TCP.
 */
public class SerialConnection implements MeshtasticConnection {

    private static final Logger log = LoggerFactory.getLogger(SerialConnection.class);

    public static final int DEFAULT_BAUD_RATE = 115200;
    private static final int READ_TIMEOUT_MS = 500;
    private static final int PORT_INIT_DELAY_MS = 500;

    /** Задержка для USB-Serial мостов (CH340/CH341/CH9102 и т.п.), которые сбрасывают
     *  ESP32 через DTR при открытии порта. ESP32 грузится ~2-3 сек после сброса. */
    private static final int PORT_INIT_DELAY_USB_BRIDGE_MS = 3000;

    private final String portName;
    private final int baudRate;

    private volatile SerialPort serialPort;
    private volatile OutputStream outputStream;
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
            serialPort = findPort(portName);
            log.info("Opening serial port: {} ({})", portName, serialPort.getDescriptivePortName());

            if (!serialPort.openPort(0)) {
                throw new ConnectionException("Failed to open serial port: " + portName
                        + " (" + serialPort.getDescriptivePortName() + ")"
                        + " — port may be busy or inaccessible");
            }

            serialPort.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            serialPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
            serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, READ_TIMEOUT_MS, 0);

            // Определяем тип USB-адаптера по описанию порта.
            // USB-Serial мосты (CH340, CH9102 и др.) активируют DTR при openPort(),
            // что сбрасывает ESP32 через цепь EN/RST. Для них нужно:
            //   1) Сбросить DTR/RTS чтобы снять удержание сброса
            //   2) Подождать ~3 сек пока ESP32 загрузится после ресета
            // Нативный USB CDC (Heltec V4, T-Beam S3 и др.) НЕ сбрасывает устройство,
            // но использует DTR как сигнал "хост подключён" — clearDTR() нарушит связь.
            String desc = serialPort.getDescriptivePortName().toUpperCase();
            boolean isUsbBridge = desc.contains("CH340") || desc.contains("CH341")
                    || desc.contains("CH9102") || desc.contains("CP210")
                    || desc.contains("FTDI") || desc.contains("PL2303");

            int initDelay;
            if (isUsbBridge) {
                // Порядок критичен! На схеме автосброса CH340 → ESP32:
                //   DTR → EN (reset), RTS → GPIO0 (boot mode select)
                // openPort() активировал оба → EN LOW (сброс), GPIO0 LOW (download mode).
                // Если отпустить EN (clearDTR) раньше GPIO0 (clearRTS), ESP32 стартует
                // с GPIO0=LOW и входит в download mode (~10 сек таймаут перед нормальным бутом).
                // Правильно: сначала GPIO0 → HIGH (normal boot), затем EN → HIGH (старт).
                serialPort.clearRTS();   // GPIO0 → HIGH (normal boot mode)
                Thread.sleep(50);        // стабилизация сигнала
                serialPort.clearDTR();   // EN → HIGH (устройство стартует с GPIO0=HIGH)
                initDelay = PORT_INIT_DELAY_USB_BRIDGE_MS;
                log.info("USB-Serial bridge detected ({}), cleared RTS→DTR, waiting {}ms for device boot",
                        serialPort.getDescriptivePortName(), initDelay);
            } else {
                initDelay = PORT_INIT_DELAY_MS;
            }
            Thread.sleep(initDelay);

            // Сбросить входной буфер — отбросить мусорные байты от предыдущей сессии
            if (serialPort.bytesAvailable() > 0) {
                serialPort.readBytes(new byte[serialPort.bytesAvailable()], serialPort.bytesAvailable());
                log.debug("Flushed {} stale bytes from serial port {}", serialPort.bytesAvailable(), portName);
            }

            outputStream = serialPort.getOutputStream();
            log.info("Connected to serial port {} at {} baud", portName, baudRate);

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
        SerialPort sp = serialPort;
        return sp != null && sp.isOpen() && running;
    }

    @Override
    public synchronized void sendBytes(byte[] data) {
        if (!isConnected()) {
            log.warn("Cannot send: serial port {} not connected", portName);
            return;
        }
        try {
            outputStream.write(data);
            outputStream.flush();
            log.debug("Sent {} bytes to serial {}", data.length, portName);
        } catch (IOException e) {
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
     * Использует {@link SerialPort#readBytes(byte[], int)} напрямую вместо
     * {@code InputStream.read()}, т.к. обёртка {@code SerialPortInputStream} бросает
     * {@code IOException("The read operation timed out...")} при таймауте в режиме
     * {@code TIMEOUT_READ_SEMI_BLOCKING}, тогда как нативный {@code readBytes()}
     * просто возвращает 0 — что корректно обрабатывается циклом.
     */
    private void readLoop() {
        log.debug("Serial reader thread started for {}", portName);
        FrameParser parser = new FrameParser();

        String errorMessage = null;
        Throwable errorCause = null;

        byte[] buf = new byte[1024];
        try {
            SerialPort sp = serialPort;
            if (sp == null || !sp.isOpen()) {
                throw new IOException("Serial port " + portName + " closed before reader started");
            }
            log.debug("Serial reader ready, starting read loop for {}", portName);
            while (running && !Thread.currentThread().isInterrupted()) {
                // readBytes() возвращает: >0 — прочитано байт, 0 — таймаут, -1 — ошибка
                int bytesRead = sp.readBytes(buf, buf.length);
                if (bytesRead < 0) {
                    if (running) {
                        log.info("Serial port {} read error (returned {})", portName, bytesRead);
                        errorMessage = "Serial port read error: " + portName;
                    }
                    break;
                }
                if (bytesRead == 0) {
                    // Таймаут — данных нет, проверяем что порт ещё открыт
                    if (!sp.isOpen()) {
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
     * Ищет порт среди обнаруженных системой.
     * Использование объекта из {@code getCommPorts()} надёжнее, чем {@code getCommPort(name)},
     * т.к. он содержит корректные нативные дескрипторы (критично для macOS USB CDC ACM).
     */
    private static SerialPort findPort(String name) throws ConnectionException {
        for (SerialPort port : SerialPort.getCommPorts()) {
            if (port.getSystemPortName().equals(name)) {
                return port;
            }
        }
        // Fallback: создать объект по имени (может не работать на некоторых ОС)
        try {
            return SerialPort.getCommPort(name);
        } catch (Exception e) {
            throw new ConnectionException("Serial port not found: " + name, e);
        }
    }

    private void closePort() {
        outputStream = null;
        SerialPort sp = serialPort;
        serialPort = null;
        if (sp != null && sp.isOpen()) {
            sp.closePort();
            log.info("Closed serial port {}", portName);
        }
    }
}
