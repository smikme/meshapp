package com.meshtastic.client.connection.serial;

import com.meshtastic.client.connection.ConnectionException;
import com.sun.jna.*;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows-реализация serial-порта через kernel32.dll (JNA).
 * <p>
 * Открывает COM-порт через {@code CreateFileW} и настраивает DCB с
 * {@code fDtrControl = DTR_CONTROL_DISABLE} — DTR не активируется,
 * ESP32 на CH340/CP210x не сбрасывается.
 */
class WinSerialPort implements NativeSerialPort {

    private static final Logger log = LoggerFactory.getLogger(WinSerialPort.class);

    // --- Win32 constants ---
    private static final int GENERIC_READ = 0x80000000;
    private static final int GENERIC_WRITE = 0x40000000;
    private static final int OPEN_EXISTING = 3;
    private static final long INVALID_HANDLE_VALUE = -1L;
    private static final int PURGE_RXCLEAR = 0x0008;

    // DCB fFlags bitmask positions
    private static final int DTR_CONTROL_MASK = 0x0030;   // bits 4-5
    private static final int RTS_CONTROL_MASK = 0x3000;   // bits 12-13
    private static final int FOUTXCTSFLOW_BIT = 0x0004;   // bit 2
    private static final int FOUTXDSRFLOW_BIT = 0x0008;   // bit 3
    private static final int FBINARY_BIT = 0x0001;        // bit 0

    // --- JNA kernel32 interface ---
    interface K32 extends Library {
        K32 INSTANCE = Native.load("kernel32", K32.class);

        Pointer CreateFileW(WString lpFileName, int dwDesiredAccess, int dwShareMode,
                            Pointer lpSecurityAttributes, int dwCreationDisposition,
                            int dwFlagsAndAttributes, Pointer hTemplateFile);

        boolean GetCommState(Pointer hFile, Pointer lpDCB);
        boolean SetCommState(Pointer hFile, Pointer lpDCB);
        boolean SetCommTimeouts(Pointer hFile, Pointer lpCommTimeouts);
        boolean ReadFile(Pointer hFile, byte[] lpBuffer, int nNumberOfBytesToRead,
                         IntByReference lpNumberOfBytesRead, Pointer lpOverlapped);
        boolean WriteFile(Pointer hFile, byte[] lpBuffer, int nNumberOfBytesToWrite,
                          IntByReference lpNumberOfBytesWritten, Pointer lpOverlapped);
        boolean PurgeComm(Pointer hFile, int dwFlags);
        boolean CloseHandle(Pointer hObject);
        int GetLastError();
    }

    // --- DCB layout (28 bytes) ---
    // offset 0:  DWORD DCBlength
    // offset 4:  DWORD BaudRate
    // offset 8:  DWORD fFlags (bitfield: fBinary, fParity, fOutxCtsFlow, fOutxDsrFlow,
    //                          fDtrControl[2], fDsrSensitivity, fTXContinueOnXoff,
    //                          fOutX, fInX, fErrorChar, fNull, fRtsControl[2], fAbortOnError, fDummy2[17])
    // offset 12: WORD  wReserved
    // offset 14: WORD  XonLim
    // offset 16: WORD  XoffLim
    // offset 18: BYTE  ByteSize
    // offset 19: BYTE  Parity
    // offset 20: BYTE  StopBits
    // ... (остальные поля)
    private static final int DCB_SIZE = 28;
    private static final int DCB_OFF_LENGTH = 0;
    private static final int DCB_OFF_BAUDRATE = 4;
    private static final int DCB_OFF_FLAGS = 8;
    private static final int DCB_OFF_BYTESIZE = 18;
    private static final int DCB_OFF_PARITY = 19;
    private static final int DCB_OFF_STOPBITS = 20;

    // COMMTIMEOUTS layout (20 bytes)
    private static final int CT_SIZE = 20;

    private volatile Pointer handle;
    private volatile boolean open;

    @Override
    public void open(String portName, int baudRate) throws ConnectionException {
        // COM-порт на Windows открывается как \\.\COMn
        String path = portName.startsWith("\\\\.\\") ? portName : "\\\\.\\" + portName;

        Pointer h = K32.INSTANCE.CreateFileW(
                new WString(path),
                GENERIC_READ | GENERIC_WRITE,
                0, null, OPEN_EXISTING, 0, null);

        if (Pointer.nativeValue(h) == INVALID_HANDLE_VALUE) {
            throw new ConnectionException("Cannot open " + portName
                    + " (error " + K32.INSTANCE.GetLastError() + ")");
        }
        this.handle = h;

        try {
            configureDcb(baudRate);
            configureTimeouts();
        } catch (Exception e) {
            K32.INSTANCE.CloseHandle(h);
            this.handle = null;
            throw new ConnectionException("Failed to configure " + portName + ": " + e.getMessage(), e);
        }

        open = true;
        log.debug("WinSerialPort opened {} at {} baud (DTR disabled)", portName, baudRate);
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

        // Модифицируем fFlags: DTR_CONTROL_DISABLE, RTS_CONTROL_DISABLE,
        // отключаем аппаратный flow control, включаем binary mode
        int flags = dcb.getInt(DCB_OFF_FLAGS);
        flags |= FBINARY_BIT;                     // fBinary = 1
        flags &= ~DTR_CONTROL_MASK;               // fDtrControl = 00 (DTR_CONTROL_DISABLE)
        flags &= ~RTS_CONTROL_MASK;               // fRtsControl = 00 (RTS_CONTROL_DISABLE)
        flags &= ~FOUTXCTSFLOW_BIT;               // fOutxCtsFlow = 0
        flags &= ~FOUTXDSRFLOW_BIT;               // fOutxDsrFlow = 0
        dcb.setInt(DCB_OFF_FLAGS, flags);

        if (!K32.INSTANCE.SetCommState(handle, dcb)) {
            throw new ConnectionException("SetCommState failed (error " + K32.INSTANCE.GetLastError() + ")");
        }
    }

    private void configureTimeouts() throws ConnectionException {
        // COMMTIMEOUTS: ReadIntervalTimeout, ReadTotalTimeoutMultiplier,
        //               ReadTotalTimeoutConstant, WriteTotalTimeoutMultiplier, WriteTotalTimeoutConstant
        Memory ct = new Memory(CT_SIZE);
        ct.clear();
        // Semi-blocking: ReadIntervalTimeout=MAXDWORD, Multiplier=MAXDWORD, Constant=500ms
        // Это заставляет ReadFile вернуть имеющиеся данные или подождать до 500мс
        ct.setInt(0, 0xFFFFFFFF);  // ReadIntervalTimeout = MAXDWORD
        ct.setInt(4, 0xFFFFFFFF);  // ReadTotalTimeoutMultiplier = MAXDWORD
        ct.setInt(8, 500);         // ReadTotalTimeoutConstant = 500ms
        ct.setInt(12, 0);          // WriteTotalTimeoutMultiplier
        ct.setInt(16, 1000);       // WriteTotalTimeoutConstant = 1s

        if (!K32.INSTANCE.SetCommTimeouts(handle, ct)) {
            throw new ConnectionException("SetCommTimeouts failed (error " + K32.INSTANCE.GetLastError() + ")");
        }
    }

    @Override
    public int read(byte[] buf, int len, int timeoutMs) {
        Pointer h = handle;
        if (h == null || !open) return -1;

        IntByReference bytesRead = new IntByReference(0);
        boolean ok = K32.INSTANCE.ReadFile(h, buf, len, bytesRead, null);
        if (!ok) {
            log.debug("ReadFile error: {}", K32.INSTANCE.GetLastError());
            return -1;
        }
        return bytesRead.getValue();
    }

    @Override
    public void write(byte[] data, int offset, int len) throws ConnectionException {
        Pointer h = handle;
        if (h == null || !open) {
            throw new ConnectionException("Port is closed");
        }

        byte[] toWrite;
        if (offset == 0 && len == data.length) {
            toWrite = data;
        } else {
            toWrite = new byte[len];
            System.arraycopy(data, offset, toWrite, 0, len);
        }

        IntByReference bytesWritten = new IntByReference(0);
        if (!K32.INSTANCE.WriteFile(h, toWrite, len, bytesWritten, null)) {
            throw new ConnectionException("WriteFile failed (error " + K32.INSTANCE.GetLastError() + ")");
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
            K32.INSTANCE.CloseHandle(h);
            log.debug("WinSerialPort closed");
        }
    }
}
