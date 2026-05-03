package com.meshtastic.client.connection.serial;

import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.platform.OsDetect;
import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * POSIX-реализация serial-порта через libc (macOS + Linux).
 * <p>
 * Открывает устройство через {@code open()} с {@code O_NOCTTY} и настраивает termios
 * с {@code CLOCAL} (игнорировать auto-modem control) и без {@code HUPCL}
 * (не дёргать DTR при close). Явное управление DTR/RTS выполняется отдельным шагом
 * после {@code tcsetattr()}, чтобы порт не менял линии самопроизвольно.
 * <p>
 * Linux дополнительно управляет RTS/DTR через прямой вызов {@code ioctl()} из libc.
 * На macOS для modem lines используется отдельный native shim: системный
 * {@code ioctl()} variadic, и его прямой вызов через JNA на Apple Silicon
 * может abort'ить JVM.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class PosixSerialPort implements NativeSerialPort {

    private static final Logger log = LoggerFactory.getLogger(PosixSerialPort.class);

    // --- POSIX constants (общие для macOS и Linux) ---
    private static final int O_RDWR = 0x0002;
    private static final int O_NOCTTY = 0x20000;   // macOS: 0x20000, Linux: 0x100
    private static final int O_NONBLOCK_MAC = 0x0004;
    private static final int O_NONBLOCK_LINUX = 0x0800;
    private static final int O_NOCTTY_LINUX = 0x0100;

    private static final int TCSANOW = 0;
    private static final int TCIFLUSH = 0;
    private static final int F_SETFL = 4;

    // c_cflag bits (одинаковые на macOS и Linux)
    private static final long CLOCAL = 0x00008000L;
    private static final long CREAD = 0x00000800L;
    private static final long CSIZE = 0x00000300L;
    private static final long CS8 = 0x00000300L;
    private static final long PARENB = 0x00001000L;
    private static final long CSTOPB = 0x00000400L;
    private static final long HUPCL = 0x00004000L;

    // c_cflag bits (Linux-specific, отличаются от macOS)
    private static final long CLOCAL_LINUX = 0x00000800L;
    private static final long CREAD_LINUX = 0x00000080L;
    private static final long CSIZE_LINUX = 0x00000030L;
    private static final long CS8_LINUX = 0x00000030L;
    private static final long PARENB_LINUX = 0x00000100L;
    private static final long CSTOPB_LINUX = 0x00000040L;
    private static final long HUPCL_LINUX = 0x00000400L;

    // c_lflag bits
    private static final long ECHO_MAC = 0x00000008L;
    private static final long ICANON_MAC = 0x00000100L;
    private static final long ISIG_MAC = 0x00000080L;
    private static final long IEXTEN_MAC = 0x00000400L;
    private static final long ECHO_LINUX = 0x00000008L;
    private static final long ICANON_LINUX = 0x00000002L;
    private static final long ISIG_LINUX = 0x00000001L;
    private static final long IEXTEN_LINUX = 0x00008000L;

    // c_iflag bits (macOS)
    private static final long IXON_MAC = 0x00000200L;
    private static final long IXOFF_MAC = 0x00000400L;
    private static final long BRKINT_MAC = 0x00000002L;
    private static final long ICRNL_MAC = 0x00000100L;
    private static final long INLCR_MAC = 0x00000040L;
    private static final long PARMRK_MAC = 0x00000008L;
    private static final long INPCK_MAC = 0x00000010L;
    private static final long ISTRIP_MAC = 0x00000020L;
    // c_iflag bits (Linux)
    private static final long IXON_LINUX = 0x00000400L;
    private static final long IXOFF_LINUX = 0x00001000L;
    private static final long BRKINT_LINUX = 0x00000002L;
    private static final long ICRNL_LINUX = 0x00000100L;
    private static final long INLCR_LINUX = 0x00000040L;
    private static final long PARMRK_LINUX = 0x00000008L;
    private static final long INPCK_LINUX = 0x00000010L;
    private static final long ISTRIP_LINUX = 0x00000020L;

    // c_oflag bits
    private static final long OPOST_MAC = 0x00000001L;
    private static final long OPOST_LINUX = 0x00000001L;

    // Baud rate constants
    private static final long B115200_MAC = 115200L;
    private static final long B115200_LINUX = 0x1002L;

    // poll
    private static final short POLLIN = 0x0001;

    // ioctl: modem control
    private static final long TIOCMBIS_LINUX = 0x5416L;
    private static final long TIOCMGET_LINUX = 0x5415L;
    private static final int TIOCM_DTR = 0x0002;
    private static final int TIOCM_RTS = 0x0004;

    // --- Termios layout ---
    // macOS (64-bit): c_iflag(8) + c_oflag(8) + c_cflag(8) + c_lflag(8) + c_cc(20) + c_ispeed(8) + c_ospeed(8) = 68
    // Linux (64-bit): c_iflag(4) + c_oflag(4) + c_cflag(4) + c_lflag(4) + c_line(1) + c_cc(32) + padding(3) + c_ispeed(4) + c_ospeed(4) = 60
    private static final int TERMIOS_SIZE_MAC = 72;
    private static final int TERMIOS_SIZE_LINUX = 60;

    // macOS offsets (unsigned long = 8 bytes each)
    private static final int OFF_IFLAG_MAC = 0;
    private static final int OFF_OFLAG_MAC = 8;
    private static final int OFF_CFLAG_MAC = 16;
    private static final int OFF_LFLAG_MAC = 24;
    private static final int OFF_CC_MAC = 32;     // c_cc[20]
    private static final int OFF_ISPEED_MAC = 56;
    private static final int OFF_OSPEED_MAC = 64;
    private static final int VMIN_MAC = 16;        // c_cc index
    private static final int VTIME_MAC = 17;

    // Linux offsets (unsigned int = 4 bytes each)
    private static final int OFF_IFLAG_LINUX = 0;
    private static final int OFF_OFLAG_LINUX = 4;
    private static final int OFF_CFLAG_LINUX = 8;
    private static final int OFF_LFLAG_LINUX = 12;
    // c_line at 16 (1 byte), c_cc at 17 (32 bytes)
    private static final int OFF_CC_LINUX = 17;
    private static final int VMIN_LINUX = 6;       // c_cc index
    private static final int VTIME_LINUX = 5;

    // --- JNA libc interface ---
    interface CLib extends Library {
        CLib INSTANCE = Native.load("c", CLib.class);

        int open(String pathname, int flags);
        int close(int fd);
        int read(int fd, byte[] buf, int count);
        int write(int fd, byte[] buf, int count);
        int tcgetattr(int fd, Pointer termios);
        int tcsetattr(int fd, int optional_actions, Pointer termios);
        int cfsetispeed(Pointer termios, NativeLong speed);
        int cfsetospeed(Pointer termios, NativeLong speed);
        int tcflush(int fd, int queue_selector);
        int fcntl(int fd, int cmd, int arg);
        int poll(Pointer fds, int nfds, int timeout);
        int ioctl(int fd, long request, Pointer arg);
        String strerror(int errnum);
    }

    // errno — берём из JNA LastError
    private static int errno() {
        return Native.getLastError();
    }

    private volatile int fd = -1;
    private volatile boolean open;

    @Override
    public void open(String portName, int baudRate, boolean assertDtr) throws ConnectionException {
        // Дополняем путь /dev/ если нужно
        String path = portName.startsWith("/dev/") ? portName : "/dev/" + portName;
        boolean isMac = OsDetect.isMacOs();

        int flags = O_RDWR;
        flags |= isMac ? (O_NOCTTY | O_NONBLOCK_MAC) : (O_NOCTTY_LINUX | O_NONBLOCK_LINUX);

        int result = CLib.INSTANCE.open(path, flags);
        if (result < 0) {
            throw new ConnectionException("Cannot open " + path + ": " + CLib.INSTANCE.strerror(errno()));
        }
        this.fd = result;

        try {
            configureTermios(baudRate, isMac);
            CLib.INSTANCE.fcntl(fd, F_SETFL, 0);
            configureModemLines(isMac, assertDtr);
        } catch (Exception e) {
            CLib.INSTANCE.close(fd);
            fd = -1;
            throw new ConnectionException("Failed to configure " + path + ": " + e.getMessage(), e);
        }

        open = true;
        log.debug("PosixSerialPort opened {} at {} baud (DTR={}, RTS=asserted)",
                portName, baudRate, assertDtr ? "on" : "off");
    }

    /**
     * Настраивает RTS/DTR после termios-конфигурации.
     * Linux использует прямой вызов libc. macOS идёт через отдельный shim с фиксированной ABI,
     * чтобы не вызывать variadic {@code ioctl()} из JNA на Apple Silicon.
     */
    private void configureModemLines(boolean isMac, boolean assertDtr) throws ConnectionException {
        if (isMac) {
            configureMacModemLines(assertDtr);
            return;
        }
        configureLinuxModemLines(assertDtr);
    }

    /**
     * macOS modem-line control через маленькую native-библиотеку с фиксированными сигнатурами.
     * Это сохраняет полный контроль над DTR/RTS, но избегает прямого JNA-вызова variadic
     * {@code ioctl()}, который на arm64 может аварийно завершить JVM.
     */
    private void configureMacModemLines(boolean assertDtr) throws ConnectionException {
        MacOsSerialLibrary.Api serialLib = MacOsSerialLibrary.instance();

        if (!assertDtr) {
            int clearResult = serialLib.meshserial_clear_modem_bits(fd, TIOCM_DTR);
            if (clearResult != 0) {
                throw modemLineError("TIOCMBIC(DTR)", clearResult);
            }
        }

        int setBits = TIOCM_RTS;
        if (assertDtr) {
            setBits |= TIOCM_DTR;
        }

        int setResult = serialLib.meshserial_set_modem_bits(fd, setBits);
        if (setResult != 0) {
            throw modemLineError("TIOCMBIS", setResult);
        }

        IntByReference actualBits = new IntByReference();
        int getResult = serialLib.meshserial_get_modem_bits(fd, actualBits);
        if (getResult == 0) {
            logModemLines(actualBits.getValue());
        } else {
            log.debug("TIOCMGET failed: {}", describeNativeError(getResult));
        }
    }

    private void configureLinuxModemLines(boolean assertDtr) {
        // RTS всегда активируем (на CH340 держит Q1 OFF → EN HIGH).
        // DTR активируем только для native USB CDC (сигнал "хост подключён").
        int modemFlags = TIOCM_RTS;
        if (assertDtr) {
            modemFlags |= TIOCM_DTR;
        }

        Memory modemBits = new Memory(4);
        modemBits.setInt(0, modemFlags);
        int r = CLib.INSTANCE.ioctl(fd, TIOCMBIS_LINUX, modemBits);
        if (r != 0) {
            log.warn("ioctl TIOCMBIS failed: errno={}", errno());
        }

        Memory actualBits = new Memory(4);
        actualBits.setInt(0, 0);
        if (CLib.INSTANCE.ioctl(fd, TIOCMGET_LINUX, actualBits) == 0) {
            logModemLines(actualBits.getInt(0));
        } else {
            log.debug("TIOCMGET failed: errno={}", errno());
        }
    }

    private void logModemLines(int bits) {
        log.debug("Modem lines after setup: DTR={}, RTS={}, raw=0x{}",
                (bits & TIOCM_DTR) != 0 ? "ON" : "OFF",
                (bits & TIOCM_RTS) != 0 ? "ON" : "OFF",
                Integer.toHexString(bits));
    }

    /**
     * Преобразует код возврата native shim в диагностическое сообщение с errno.
     */
    private ConnectionException modemLineError(String operation, int nativeResult) {
        return new ConnectionException(operation + " failed: " + describeNativeError(nativeResult));
    }

    private String describeNativeError(int nativeResult) {
        int err = nativeResult < 0 ? -nativeResult : nativeResult;
        return CLib.INSTANCE.strerror(err) + " (" + err + ")";
    }

    private void configureTermios(int baudRate, boolean isMac) throws ConnectionException {
        int termiosSize = isMac ? TERMIOS_SIZE_MAC : TERMIOS_SIZE_LINUX;
        Memory termios = new Memory(termiosSize);
        termios.clear();

        if (CLib.INSTANCE.tcgetattr(fd, termios) != 0) {
            throw new ConnectionException("tcgetattr failed: " + CLib.INSTANCE.strerror(errno()));
        }

        if (isMac) {
            configureMac(termios);
        } else {
            configureLinux(termios);
        }

        // Установить скорость через cfsetispeed/cfsetospeed
        long speed = isMac ? baudRateMac(baudRate) : baudRateLinux(baudRate);
        CLib.INSTANCE.cfsetispeed(termios, new NativeLong(speed));
        CLib.INSTANCE.cfsetospeed(termios, new NativeLong(speed));

        if (CLib.INSTANCE.tcsetattr(fd, TCSANOW, termios) != 0) {
            throw new ConnectionException("tcsetattr failed: " + CLib.INSTANCE.strerror(errno()));
        }
    }

    private void configureMac(Memory t) {
        // c_iflag: raw input (как jSerialComm)
        long iflag = t.getLong(OFF_IFLAG_MAC);
        iflag &= ~(IXON_MAC | IXOFF_MAC | BRKINT_MAC | ICRNL_MAC | INLCR_MAC | PARMRK_MAC | INPCK_MAC | ISTRIP_MAC);
        t.setLong(OFF_IFLAG_MAC, iflag);

        // c_oflag: raw output
        long oflag = t.getLong(OFF_OFLAG_MAC);
        oflag &= ~OPOST_MAC;
        t.setLong(OFF_OFLAG_MAC, oflag);

        // c_cflag: 8N1, CLOCAL, CREAD, без HUPCL
        // На macOS не хотим опускать DTR при close, иначе часть USB-UART мостов ресетит ESP32.
        long cflag = t.getLong(OFF_CFLAG_MAC);
        cflag &= ~(CSIZE | PARENB | CSTOPB | HUPCL);
        cflag |= CS8 | CLOCAL | CREAD;
        t.setLong(OFF_CFLAG_MAC, cflag);

        // c_lflag: raw mode
        long lflag = t.getLong(OFF_LFLAG_MAC);
        lflag &= ~(ECHO_MAC | ICANON_MAC | ISIG_MAC | IEXTEN_MAC);
        t.setLong(OFF_LFLAG_MAC, lflag);

        // VMIN=0, VTIME=0 — non-blocking (таймаут через poll)
        t.setByte(OFF_CC_MAC + VMIN_MAC, (byte) 0);
        t.setByte(OFF_CC_MAC + VTIME_MAC, (byte) 0);
    }

    private void configureLinux(Memory t) {
        // c_iflag: raw input (как jSerialComm)
        int iflag = t.getInt(OFF_IFLAG_LINUX);
        iflag &= ~((int) IXON_LINUX | (int) IXOFF_LINUX | (int) BRKINT_LINUX
                | (int) ICRNL_LINUX | (int) INLCR_LINUX | (int) PARMRK_LINUX
                | (int) INPCK_LINUX | (int) ISTRIP_LINUX);
        t.setInt(OFF_IFLAG_LINUX, iflag);

        // c_oflag
        int oflag = t.getInt(OFF_OFLAG_LINUX);
        oflag &= ~(int) OPOST_LINUX;
        t.setInt(OFF_OFLAG_LINUX, oflag);

        // c_cflag: 8N1, CLOCAL, CREAD, HUPCL (как jSerialComm)
        int cflag = t.getInt(OFF_CFLAG_LINUX);
        cflag &= ~((int) CSIZE_LINUX | (int) PARENB_LINUX | (int) CSTOPB_LINUX);
        cflag |= (int) CS8_LINUX | (int) CLOCAL_LINUX | (int) CREAD_LINUX | (int) HUPCL_LINUX;
        t.setInt(OFF_CFLAG_LINUX, cflag);

        // c_lflag
        int lflag = t.getInt(OFF_LFLAG_LINUX);
        lflag &= ~((int) ECHO_LINUX | (int) ICANON_LINUX | (int) ISIG_LINUX | (int) IEXTEN_LINUX);
        t.setInt(OFF_LFLAG_LINUX, lflag);

        // VMIN=0, VTIME=0
        t.setByte(OFF_CC_LINUX + VMIN_LINUX, (byte) 0);
        t.setByte(OFF_CC_LINUX + VTIME_LINUX, (byte) 0);
    }

    @Override
    public int read(byte[] buf, int len, int timeoutMs) {
        int f = fd;
        if (f < 0 || !open) return -1;

        // poll() для таймаута перед read()
        // struct pollfd: int fd (4) + short events (2) + short revents (2) = 8 bytes
        Memory pollFd = new Memory(8);
        pollFd.setInt(0, f);
        pollFd.setShort(4, POLLIN);
        pollFd.setShort(6, (short) 0);

        int pollResult = CLib.INSTANCE.poll(pollFd, 1, timeoutMs);
        if (pollResult <= 0) {
            return 0; // таймаут или ошибка
        }

        int bytesRead = CLib.INSTANCE.read(f, buf, len);
        if (bytesRead < 0) {
            int err = errno();
            // EAGAIN/EWOULDBLOCK — нет данных (не ошибка)
            if (err == 11 || err == 35) return 0;
            log.debug("read() error: {} ({})", CLib.INSTANCE.strerror(err), err);
            return -1;
        }
        if (bytesRead == 0) {
            return -1; // EOF — порт закрыт
        }
        return bytesRead;
    }

    @Override
    public void write(byte[] data, int offset, int len) throws ConnectionException {
        int f = fd;
        if (f < 0 || !open) {
            throw new ConnectionException("Port is closed");
        }

        byte[] toWrite;
        if (offset == 0 && len == data.length) {
            toWrite = data;
        } else {
            toWrite = new byte[len];
            System.arraycopy(data, offset, toWrite, 0, len);
        }

        int written = CLib.INSTANCE.write(f, toWrite, len);
        if (written < 0) {
            throw new ConnectionException("write() failed: " + CLib.INSTANCE.strerror(errno()));
        }
        if (written != len) {
            throw new ConnectionException("Incomplete write: " + written + "/" + len);
        }
    }

    @Override
    public void drainInput() {
        int f = fd;
        if (f >= 0 && open) {
            CLib.INSTANCE.tcflush(f, TCIFLUSH);
        }
    }

    @Override
    public boolean isOpen() {
        return open && fd >= 0;
    }

    @Override
    public void close() {
        open = false;
        int f = fd;
        fd = -1;
        if (f >= 0) {
            CLib.INSTANCE.close(f);
            log.debug("PosixSerialPort closed (fd={})", f);
        }
    }

    private static long baudRateMac(int baudRate) {
        return baudRate; // macOS: числовое значение = константа скорости
    }

    private static long baudRateLinux(int baudRate) {
        // Linux baud rate constants (termios.h)
        return switch (baudRate) {
            case 9600 -> 0x000DL;
            case 19200 -> 0x000EL;
            case 38400 -> 0x000FL;
            case 57600 -> 0x1001L;
            case 115200 -> 0x1002L;
            case 230400 -> 0x1003L;
            case 460800 -> 0x1004L;
            case 921600 -> 0x1007L;
            default -> 0x1002L; // fallback to 115200
        };
    }
}
