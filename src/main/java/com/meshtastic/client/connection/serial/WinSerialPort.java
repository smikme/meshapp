package com.meshtastic.client.connection.serial;

import com.meshtastic.client.connection.ConnectionException;
import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows-реализация serial-порта через kernel32.dll (JNA).
 * <p>
 * Открывает COM-порт через {@code CreateFileW} с {@code FILE_FLAG_OVERLAPPED}
 * для параллельного чтения и записи, и настраивает DCB с
 * modem-line policy selected by {@link SerialModemLinePolicy}.
 * <p>
 * DTR/RTS are kept disabled for USB-UART bridges so ESP32 auto-reset circuits
 * are not triggered on every open/reconnect.
 * {@code fAbortOnError = 0} → I/O не блокируется при ошибках драйвера.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class WinSerialPort implements NativeSerialPort {

    private static final Logger log = LoggerFactory.getLogger(WinSerialPort.class);

    // --- Win32 constants ---
    private static final int GENERIC_READ = 0x80000000;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int OPEN_EXISTING = 3;
    private static final int FILE_FLAG_OVERLAPPED = 0x40000000;
    private static final long INVALID_HANDLE_VALUE = -1L;
    private static final int PURGE_TXABORT = 0x0001;
    private static final int PURGE_RXABORT = 0x0002;
    private static final int PURGE_TXCLEAR = 0x0004;
    private static final int PURGE_RXCLEAR = 0x0008;
    private static final int WAIT_OBJECT_0 = 0x00000000;
    private static final int WAIT_TIMEOUT = 0x00000102;
    private static final int WAIT_FAILED = 0xFFFFFFFF;
    private static final int INFINITE = 0xFFFFFFFF;

    // OVERLAPPED structure: 5 fields (Internal, InternalHigh, Offset, OffsetHigh, hEvent)
    // На 64-bit Windows: ULONG_PTR (8) + ULONG_PTR (8) + DWORD (4) + DWORD (4) + HANDLE (8) = 32 bytes
    // На 32-bit: ULONG_PTR (4) + ULONG_PTR (4) + DWORD (4) + DWORD (4) + HANDLE (4) = 20 bytes
    private static final int OVERLAPPED_SIZE = Native.POINTER_SIZE == 8 ? 32 : 20;
    private static final int OVERLAPPED_EVENT_OFFSET = Native.POINTER_SIZE == 8 ? 24 : 16;

    // DCB fFlags bitfield (DWORD at offset 8)
    private static final int FBINARY_BIT = 0x0001;         // bit 0
    private static final int DTR_CONTROL_ENABLE = 0x0010;   // bits 4-5 = 01
    private static final int RTS_CONTROL_ENABLE = 0x1000;   // bits 12-13 = 01

    // DCB layout
    private static final int DCB_SIZE = 28;
    private static final int DCB_OFF_LENGTH = 0;
    private static final int DCB_OFF_BAUDRATE = 4;
    private static final int DCB_OFF_FLAGS = 8;
    private static final int DCB_OFF_XONLIM = 14;
    private static final int DCB_OFF_XOFFLIM = 16;
    private static final int DCB_OFF_BYTESIZE = 18;
    private static final int DCB_OFF_PARITY = 19;
    private static final int DCB_OFF_STOPBITS = 20;
    private static final int DCB_OFF_XONCHAR = 21;
    private static final int DCB_OFF_XOFFCHAR = 22;

    // COMMTIMEOUTS layout (20 bytes)
    private static final int CT_SIZE = 20;
    // COMSTAT layout: flags DWORD + cbInQue DWORD + cbOutQue DWORD
    private static final int COMSTAT_SIZE = 12;
    private static final int COMSTAT_OFF_IN_QUEUE = 4;

    // --- JNA kernel32 interface ---
    interface K32 extends Library {
        K32 INSTANCE = Native.load("kernel32", K32.class);

        Pointer CreateFileW(WString lpFileName, int dwDesiredAccess, int dwShareMode,
                            Pointer lpSecurityAttributes, int dwCreationDisposition,
                            int dwFlagsAndAttributes, Pointer hTemplateFile);

        boolean GetCommState(Pointer hFile, Pointer lpDCB);
        boolean SetCommState(Pointer hFile, Pointer lpDCB);
        boolean SetCommTimeouts(Pointer hFile, Pointer lpCommTimeouts);
        boolean ClearCommError(Pointer hFile, IntByReference lpErrors, Pointer lpStat);
        boolean ReadFile(Pointer hFile, Pointer lpBuffer, int nNumberOfBytesToRead,
                         IntByReference lpNumberOfBytesRead, Pointer lpOverlapped);
        boolean WriteFile(Pointer hFile, Pointer lpBuffer, int nNumberOfBytesToWrite,
                          IntByReference lpNumberOfBytesWritten, Pointer lpOverlapped);
        boolean GetOverlappedResult(Pointer hFile, Pointer lpOverlapped,
                                    IntByReference lpNumberOfBytesTransferred, boolean bWait);
        Pointer CreateEventW(Pointer lpEventAttributes, boolean bManualReset,
                             boolean bInitialState, WString lpName);
        int WaitForSingleObject(Pointer hHandle, int dwMilliseconds);
        boolean ResetEvent(Pointer hEvent);
        boolean PurgeComm(Pointer hFile, int dwFlags);
        boolean CancelIo(Pointer hFile);
        boolean CancelIoEx(Pointer hFile, Pointer lpOverlapped);
        boolean CloseHandle(Pointer hObject);
        int GetLastError();
    }

    private static final int ERROR_IO_PENDING = 997;
    private static final int ERROR_OPERATION_ABORTED = 995;
    private static final int ERROR_NOT_FOUND = 1168;
    private static final long COMM_ERROR_LOG_INTERVAL_MS = 1_000L;
    private static final int WRITE_TIMEOUT_MS = 5_000;

    private static final int CE_RXOVER = 0x0001;
    private static final int CE_OVERRUN = 0x0002;
    private static final int CE_RXPARITY = 0x0004;
    private static final int CE_FRAME = 0x0008;
    private static final int CE_BREAK = 0x0010;
    private static final int CE_TXFULL = 0x0100;
    private static final int CE_PTO = 0x0200;
    private static final int CE_IOE = 0x0400;
    private static final int CE_DNS = 0x0800;
    private static final int CE_OOP = 0x1000;
    private static final int CE_MODE = 0x8000;

    private volatile Pointer handle;
    private volatile boolean open;

    // Отдельные event-объекты для read и write — позволяют работать параллельно
    private Pointer readEvent;
    private Pointer writeEvent;

    private SerialModemLinePolicy modemLinePolicy;
    private volatile int lastLoggedCommErrorMask;
    private volatile long lastLoggedCommErrorAtMillis;

    @Override
    public void open(String portName, int baudRate, SerialModemLinePolicy modemLinePolicy) throws ConnectionException {
        this.modemLinePolicy = modemLinePolicy;
        String path = portName.startsWith("\\\\.\\") ? portName : "\\\\.\\" + portName;

        // FILE_FLAG_OVERLAPPED — критично для параллельного read/write.
        // Без этого ReadFile блокирует WriteFile на ~500мс (read timeout).
        Pointer h = K32.INSTANCE.CreateFileW(
                new WString(path),
                GENERIC_READ | GENERIC_WRITE,
                0, null, OPEN_EXISTING, FILE_FLAG_OVERLAPPED, null);

        if (Pointer.nativeValue(h) == INVALID_HANDLE_VALUE) {
            throw new ConnectionException("Cannot open " + portName
                    + " (error " + K32.INSTANCE.GetLastError() + ")");
        }
        this.handle = h;

        try {
            readEvent = createEvent();
            writeEvent = createEvent();
            configureDcb(baudRate);
            configureTimeouts();
        } catch (Exception e) {
            closeEvents();
            K32.INSTANCE.CloseHandle(h);
            this.handle = null;
            throw new ConnectionException("Failed to configure " + portName + ": " + e.getMessage(), e);
        }

        open = true;
        log.debug("WinSerialPort opened {} at {} baud (DTR={}, RTS={}, policy={}, overlapped)",
                portName, baudRate,
                modemLinePolicy.assertDtr() ? "enabled" : "disabled",
                modemLinePolicy.assertRts() ? "enabled" : "disabled",
                modemLinePolicy.reason());
    }

    private Pointer createEvent() throws ConnectionException {
        Pointer evt = K32.INSTANCE.CreateEventW(null, true, false, null);
        if (evt == null || Pointer.nativeValue(evt) == 0) {
            throw new ConnectionException("CreateEvent failed (error " + K32.INSTANCE.GetLastError() + ")");
        }
        return evt;
    }

    private void configureDcb(int baudRate) throws ConnectionException {
        Memory dcb = new Memory(DCB_SIZE);
        dcb.clear();
        dcb.setInt(DCB_OFF_LENGTH, DCB_SIZE);

        if (!K32.INSTANCE.GetCommState(handle, dcb)) {
            throw new ConnectionException("GetCommState failed (error " + K32.INSTANCE.GetLastError() + ")");
        }

        dcb.setInt(DCB_OFF_BAUDRATE, baudRate);
        dcb.setByte(DCB_OFF_BYTESIZE, (byte) 8);
        dcb.setByte(DCB_OFF_PARITY, (byte) 0);   // NOPARITY
        dcb.setByte(DCB_OFF_STOPBITS, (byte) 0);  // ONESTOPBIT

        // fFlags: выставляем ВСЕ биты явно (не read-modify-write!).
        // Критично: драйвер CH340 может оставить fAbortOnError=1 по умолчанию,
        // что блокирует ВСЁ I/O после любой ошибки на порту.
        //
        // DTR: ENABLE for native USB CDC, DISABLE for USB-UART bridges.
        // RTS: do not force-enable for bridges; some ESP32 boards wire it into auto-reset.
        int flags = FBINARY_BIT;
        if (modemLinePolicy.assertDtr()) flags |= DTR_CONTROL_ENABLE;
        if (modemLinePolicy.assertRts()) flags |= RTS_CONTROL_ENABLE;
        dcb.setInt(DCB_OFF_FLAGS, flags);

        dcb.setShort(DCB_OFF_XONLIM, (short) 2048);
        dcb.setShort(DCB_OFF_XOFFLIM, (short) 512);
        dcb.setByte(DCB_OFF_XONCHAR, (byte) 17);
        dcb.setByte(DCB_OFF_XOFFCHAR, (byte) 19);

        if (!K32.INSTANCE.SetCommState(handle, dcb)) {
            throw new ConnectionException("SetCommState failed (error " + K32.INSTANCE.GetLastError() + ")");
        }
    }

    private void configureTimeouts() throws ConnectionException {
        Memory ct = new Memory(CT_SIZE);
        ct.clear();
        // Для overlapped I/O все таймауты = 0:
        // ReadFile/WriteFile возвращают IO_PENDING, реальный таймаут
        // контролируется через WaitForSingleObject на event-объектах.
        // Если задать MAXDWORD/MAXDWORD/N — драйвер завершает операцию
        // с 0 байтами немедленно при пустом буфере, event сигнализируется
        // сразу, и WaitForSingleObject никогда не ждёт реальных данных.
        ct.setInt(0, 0);   // ReadIntervalTimeout = 0
        ct.setInt(4, 0);   // ReadTotalTimeoutMultiplier = 0
        ct.setInt(8, 0);   // ReadTotalTimeoutConstant = 0
        ct.setInt(12, 0);  // WriteTotalTimeoutMultiplier = 0
        ct.setInt(16, 0);  // WriteTotalTimeoutConstant = 0

        if (!K32.INSTANCE.SetCommTimeouts(handle, ct)) {
            throw new ConnectionException("SetCommTimeouts failed (error " + K32.INSTANCE.GetLastError() + ")");
        }
    }

    @Override
    public int read(byte[] buf, int len, int timeoutMs) {
        Pointer h = handle;
        if (h == null || !open) return -1;

        // Первый байт ждём одним overlapped ReadFile с timeout.
        // Это убирает постоянный polling через ClearCommError на пустом порту.
        int totalRead = readChunk(h, buf, 0, 1, timeoutMs);
        if (totalRead <= 0) {
            return totalRead;
        }

        while (totalRead < len) {
            int available = bytesAvailable(h);
            if (available <= 0) {
                break;
            }

            int chunkRead = readChunk(h, buf, totalRead, Math.min(len - totalRead, available), 0);
            if (chunkRead < 0) {
                return totalRead > 0 ? totalRead : -1;
            }
            if (chunkRead == 0) {
                break;
            }
            totalRead += chunkRead;
            available = bytesAvailable(h);
        }

        return totalRead;
    }

    private static int copyCompletedRead(Memory nativeBuf, byte[] buf, int offset, IntByReference bytesRead) {
        int n = bytesRead.getValue();
        if (n > 0) {
            nativeBuf.read(0, buf, offset, n);
        }
        return n;
    }

    /**
     * Выполняет один overlapped read для указанного чанка.
     * timeoutMs=0 используется только для уже накопившегося хвоста после первого байта.
     */
    private int readChunk(Pointer h, byte[] buf, int offset, int len, int timeoutMs) {
        // Нативный буфер (Memory) живёт до конца метода — ReadFile пишет в него
        // асинхронно, и данные будут на месте когда GetOverlappedResult вернёт управление.
        // Нельзя передавать byte[] в overlapped ReadFile — JNA освободит временный
        // нативный буфер до завершения I/O, и данные пропадут (все нули).
        Memory nativeBuf = new Memory(len);

        Memory ovl = new Memory(OVERLAPPED_SIZE);
        ovl.clear();
        ovl.setPointer(OVERLAPPED_EVENT_OFFSET, readEvent);
        K32.INSTANCE.ResetEvent(readEvent);

        IntByReference bytesRead = new IntByReference(0);
        boolean ok = K32.INSTANCE.ReadFile(h, nativeBuf, len, bytesRead, ovl);

        if (ok) {
            return copyCompletedRead(nativeBuf, buf, offset, bytesRead);
        }

        int err = K32.INSTANCE.GetLastError();
        if (err != ERROR_IO_PENDING) {
            if (open) log.debug("ReadFile error: {}", err);
            return -1;
        }

        int waitResult = K32.INSTANCE.WaitForSingleObject(readEvent, timeoutMs);
        if (waitResult == WAIT_OBJECT_0) {
            if (K32.INSTANCE.GetOverlappedResult(h, ovl, bytesRead, false)) {
                return copyCompletedRead(nativeBuf, buf, offset, bytesRead);
            }
            return -1;
        }

        if (waitResult == WAIT_TIMEOUT) {
            return finishTimedOutRead(h, ovl, nativeBuf, buf, offset, bytesRead);
        }

        log.debug("WaitForSingleObject unexpected: 0x{}", Integer.toHexString(waitResult));
        return -1;
    }

    /**
     * Возвращает количество входящих байт, уже буферизованных драйвером.
     * Нужен для быстрого дочитывания хвоста после первого успешного байта.
     */
    private int bytesAvailable(Pointer h) {
        Memory stat = new Memory(COMSTAT_SIZE);
        stat.clear();
        IntByReference errors = new IntByReference();
        if (!K32.INSTANCE.ClearCommError(h, errors, stat)) {
            if (open) {
                log.debug("ClearCommError failed: {}", K32.INSTANCE.GetLastError());
            }
            return 0;
        }
        int inQueue = Math.max(stat.getInt(COMSTAT_OFF_IN_QUEUE), 0);
        int errorMask = errors.getValue();
        if (errorMask != 0) {
            maybeLogCommError(errorMask, inQueue);
        } else {
            lastLoggedCommErrorMask = 0;
            lastLoggedCommErrorAtMillis = 0;
        }
        return inQueue;
    }

    /**
     * Завершает timed-out overlapped read.
     * <p>
     * На Windows отмена может вернуть уже полученные байты, если устройство успело
     * дописать их между {@code WAIT_TIMEOUT} и фактической отменой операции.
     * Их нельзя терять: выпадение даже нескольких байт рвёт Meshtastic frame и
     * даёт protobuf parse errors / потерю ACK при рабочем канале записи.
     */
    private int finishTimedOutRead(Pointer h, Memory ovl, Memory nativeBuf,
                                   byte[] buf, int offset, IntByReference bytesRead) {
        cancelPendingIo(h, ovl);

        boolean completed = K32.INSTANCE.GetOverlappedResult(h, ovl, bytesRead, true);
        int n = copyCompletedRead(nativeBuf, buf, offset, bytesRead);
        if (n > 0) {
            log.debug("Timed-out ReadFile returned {} bytes after cancel", n);
            return n;
        }

        if (!completed) {
            int err = K32.INSTANCE.GetLastError();
            if (err != ERROR_OPERATION_ABORTED) {
                log.debug("Timed-out ReadFile completion failed: {}", err);
                return -1;
            }
        }
        return 0;
    }

    @Override
    public void write(byte[] data, int offset, int len) throws ConnectionException {
        Pointer h = handle;
        if (h == null || !open) {
            throw new ConnectionException("Port is closed");
        }

        // Нативный буфер для overlapped WriteFile — аналогично read(),
        // byte[] нельзя передавать в асинхронную операцию.
        Memory nativeBuf = new Memory(len);
        nativeBuf.write(0, data, offset, len);

        Memory ovl = new Memory(OVERLAPPED_SIZE);
        ovl.clear();
        ovl.setPointer(OVERLAPPED_EVENT_OFFSET, writeEvent);
        K32.INSTANCE.ResetEvent(writeEvent);

        IntByReference bytesWritten = new IntByReference(0);
        boolean ok = K32.INSTANCE.WriteFile(h, nativeBuf, len, bytesWritten, ovl);

        if (!ok) {
            int err = K32.INSTANCE.GetLastError();
            if (err != ERROR_IO_PENDING) {
                throw new ConnectionException("WriteFile failed (error " + err + ")");
            }
            int waitResult = K32.INSTANCE.WaitForSingleObject(writeEvent, WRITE_TIMEOUT_MS);
            if (waitResult == WAIT_TIMEOUT) {
                cancelPendingIo(h, ovl);
                K32.INSTANCE.GetOverlappedResult(h, ovl, bytesWritten, true);
                throw new ConnectionException("Write timed out after " + WRITE_TIMEOUT_MS + " ms");
            }
            if (waitResult == WAIT_FAILED) {
                throw new ConnectionException("Write wait failed (error " + K32.INSTANCE.GetLastError() + ")");
            }
            if (waitResult != WAIT_OBJECT_0) {
                throw new ConnectionException("Write wait returned 0x" + Integer.toHexString(waitResult));
            }
            if (!K32.INSTANCE.GetOverlappedResult(h, ovl, bytesWritten, false)) {
                throw new ConnectionException("Write GetOverlappedResult failed (error "
                        + K32.INSTANCE.GetLastError() + ")");
            }
        }

        if (bytesWritten.getValue() != len) {
            throw new ConnectionException("Incomplete write: " + bytesWritten.getValue() + "/" + len);
        }
    }

    @Override
    public void drainInput() {
        Pointer h = handle;
        if (h != null && open) {
            K32.INSTANCE.PurgeComm(h, PURGE_RXCLEAR);
        }
    }

    @Override
    public boolean isOpen() {
        return open && handle != null;
    }

    @Override
    public void close() {
        open = false;
        Pointer h = handle;
        handle = null;
        if (h != null) {
            abortComm(h);
            cancelPendingIo(h, null);
            K32.INSTANCE.CloseHandle(h);
        }
        closeEvents();
        log.debug("WinSerialPort closed");
    }

    private void closeEvents() {
        if (readEvent != null) {
            K32.INSTANCE.CloseHandle(readEvent);
            readEvent = null;
        }
        if (writeEvent != null) {
            K32.INSTANCE.CloseHandle(writeEvent);
            writeEvent = null;
        }
    }

    private void maybeLogCommError(int errorMask, int inQueue) {
        long now = System.currentTimeMillis();
        if (errorMask != lastLoggedCommErrorMask
                || (now - lastLoggedCommErrorAtMillis) >= COMM_ERROR_LOG_INTERVAL_MS) {
            log.warn("ClearCommError reported {} on serial port (inQueue={})",
                    formatCommErrors(errorMask), inQueue);
            lastLoggedCommErrorMask = errorMask;
            lastLoggedCommErrorAtMillis = now;
        }
    }

    static String formatCommErrors(int mask) {
        if (mask == 0) {
            return "none";
        }

        StringBuilder sb = new StringBuilder();
        int remaining = mask;
        remaining = appendCommError(sb, remaining, CE_RXOVER, "RXOVER");
        remaining = appendCommError(sb, remaining, CE_OVERRUN, "OVERRUN");
        remaining = appendCommError(sb, remaining, CE_RXPARITY, "RXPARITY");
        remaining = appendCommError(sb, remaining, CE_FRAME, "FRAME");
        remaining = appendCommError(sb, remaining, CE_BREAK, "BREAK");
        remaining = appendCommError(sb, remaining, CE_TXFULL, "TXFULL");
        remaining = appendCommError(sb, remaining, CE_PTO, "PTO");
        remaining = appendCommError(sb, remaining, CE_IOE, "IOE");
        remaining = appendCommError(sb, remaining, CE_DNS, "DNS");
        remaining = appendCommError(sb, remaining, CE_OOP, "OOP");
        remaining = appendCommError(sb, remaining, CE_MODE, "MODE");
        if (remaining != 0) {
            appendCommErrorName(sb, String.format("0x%04X", remaining));
        }
        return sb.toString();
    }

    private static int appendCommError(StringBuilder sb, int remaining, int flag, String name) {
        if ((remaining & flag) != 0) {
            appendCommErrorName(sb, name);
            remaining &= ~flag;
        }
        return remaining;
    }

    private static void appendCommErrorName(StringBuilder sb, String name) {
        if (!sb.isEmpty()) {
            sb.append('|');
        }
        sb.append(name);
    }

    private void abortComm(Pointer h) {
        int flags = PURGE_RXABORT | PURGE_TXABORT | PURGE_RXCLEAR | PURGE_TXCLEAR;
        if (!K32.INSTANCE.PurgeComm(h, flags) && open) {
            log.debug("PurgeComm abort failed: {}", K32.INSTANCE.GetLastError());
        }
    }

    private void cancelPendingIo(Pointer h, Pointer ovl) {
        if (h == null) {
            return;
        }
        if (K32.INSTANCE.CancelIoEx(h, ovl)) {
            return;
        }

        int err = K32.INSTANCE.GetLastError();
        if (err == ERROR_NOT_FOUND || err == ERROR_OPERATION_ABORTED) {
            return;
        }

        if (ovl == null && K32.INSTANCE.CancelIo(h)) {
            return;
        }

        if (open) {
            log.debug("CancelIoEx failed: {}", err);
        }
    }
}
