package com.meshtastic.client.platform;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Windows: управление Mica/Acrylic через DWM API.
 * <p>
 * Windows 11 22H2+: {@code DWMWA_SYSTEMBACKDROP_TYPE} (dwmapi.dll) — системный Acrylic/Mica.
 * Windows 10: {@code SetWindowCompositionAttribute} (user32.dll) — ACCENT_ENABLE_ACRYLICBLURBEHIND.
 * <p>
 * Совместно с StageStyle.TRANSPARENT обеспечивает кастомный title bar
 * (идентичный macOS) и blur backdrop.
 * <p>
 * Drag и resize реализованы в RootPane через JavaFX EventFilter.
 */
public class NativeWinWindowControl {

    private static final Logger log = LoggerFactory.getLogger(NativeWinWindowControl.class);
    private final WinDef.HWND hwnd;

    // DWM attribute constants (Windows 11 22H2+)
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;
    private static final int DWMWA_SYSTEMBACKDROP_TYPE = 38;
    private static final int DWMWCP_DOROUND = 2;

    // SetWindowCompositionAttribute constants (Windows 10 fallback)
    private static final int WCA_ACCENT_POLICY = 19;
    private static final int ACCENT_ENABLE_ACRYLICBLURBEHIND = 4;

    public NativeWinWindowControl(Window window) {
        this.hwnd = extractHwnd(window);
    }

    /**
     * Рефлексия: Window → TkStage → PlatformWindow → getNativeHandle() → HWND
     */
    private static WinDef.HWND extractHwnd(Window window) {
        try {
            Method getPeer = Window.class.getDeclaredMethod("getPeer");
            getPeer.setAccessible(true);
            Object tkStage = getPeer.invoke(window);

            Method getPlatformWindow = tkStage.getClass().getDeclaredMethod("getPlatformWindow");
            getPlatformWindow.setAccessible(true);
            Object platformWindow = getPlatformWindow.invoke(tkStage);

            Method getNativeHandle = platformWindow.getClass().getMethod("getNativeHandle");
            getNativeHandle.setAccessible(true);
            long nativeHandle = (long) getNativeHandle.invoke(platformWindow);

            return new WinDef.HWND(new Pointer(nativeHandle));
        } catch (Exception e) {
            log.error("Не удалось получить HWND окна", e);
            return null;
        }
    }

    // ==================== DWM Frame ====================

    /**
     * Расширить DWM frame на всю клиентскую область.
     * MARGINS{-1,-1,-1,-1} = «sheet of glass» — DWM-поверхность
     * покрывает всю клиентскую область, через неё виден backdrop.
     */
    public boolean extendFrameIntoClientArea() {
        if (hwnd == null) { return false; }
        try {
            MARGINS margins = new MARGINS(-1, -1, -1, -1);
            WinNT.HRESULT hr = Dwm.INSTANCE.DwmExtendFrameIntoClientArea(hwnd, margins);
            boolean ok = hr.longValue() == 0;
            if (ok) {
                log.info("DwmExtendFrameIntoClientArea: sheet of glass");
            } else {
                log.warn("DwmExtendFrameIntoClientArea failed: 0x{}", Long.toHexString(hr.longValue()));
            }
            return ok;
        } catch (Exception e) {
            log.error("Не удалось расширить DWM frame", e);
            return false;
        }
    }

    // ==================== Backdrop & Appearance ====================

    /** Установить системный backdrop (Mica, Mica Alt, Acrylic, None). Windows 11 22H2+. */
    public boolean setWindowBackdrop(DwmSystemBackdropType backdrop) {
        if (hwnd == null) { return false; }
        try {
            WinNT.HRESULT hr = Dwm.INSTANCE.DwmSetWindowAttribute(
                    hwnd, DWMWA_SYSTEMBACKDROP_TYPE,
                    new WinDef.DWORDByReference(new WinDef.DWORD(backdrop.value)),
                    WinDef.DWORD.SIZE);
            boolean ok = hr.longValue() == 0;
            log.info("setWindowBackdrop({}): {}", backdrop,
                    ok ? "OK" : "HRESULT=0x" + Long.toHexString(hr.longValue()));
            return ok;
        } catch (Exception e) {
            log.error("Не удалось установить backdrop", e);
            return false;
        }
    }

    /** Тёмный режим title bar */
    public boolean setDarkMode(boolean dark) {
        if (hwnd == null) { return false; }
        try {
            WinNT.HRESULT hr = Dwm.INSTANCE.DwmSetWindowAttribute(
                    hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE,
                    new WinDef.BOOLByReference(new WinDef.BOOL(dark)),
                    WinDef.DWORD.SIZE);
            return hr.longValue() == 0;
        } catch (Exception e) {
            log.error("Не удалось установить dark mode", e);
            return false;
        }
    }

    /** Скруглённые углы (Windows 11+, non-fatal на Win10) */
    public boolean setRoundedCorners() {
        if (hwnd == null) { return false; }
        try {
            WinNT.HRESULT hr = Dwm.INSTANCE.DwmSetWindowAttribute(
                    hwnd, DWMWA_WINDOW_CORNER_PREFERENCE,
                    new WinDef.DWORDByReference(new WinDef.DWORD(DWMWCP_DOROUND)),
                    WinDef.DWORD.SIZE);
            return hr.longValue() == 0;
        } catch (Exception e) {
            log.warn("Скруглённые углы недоступны (не Windows 11?)", e);
            return false;
        }
    }

    // ==================== Win10 Fallback: SetWindowCompositionAttribute ====================

    /**
     * Acrylic blur через SetWindowCompositionAttribute (user32.dll).
     * Недокументированный API, работает с layered windows (StageStyle.TRANSPARENT).
     * <p>
     * ACCENT_POLICY: {AccentState, AccentFlags, GradientColor(ABGR), AnimationId} — 16 байт.
     * WINDOWCOMPOSITIONATTRIBDATA: {Attribute(int), pad, pData(ptr), cbData(size_t)}.
     *
     * @return true если acrylic blur успешно применён
     */
    public boolean setAcrylicViaCompositionAttribute() {
        if (hwnd == null) { return false; }
        try {
            ACCENT_POLICY accent = new ACCENT_POLICY();
            accent.accentState = ACCENT_ENABLE_ACRYLICBLURBEHIND;
            accent.accentFlags = 2;
            accent.gradientColor = 0x01000000; // ABGR: near-transparent black
            accent.animationId = 0;
            accent.write();

            // WINDOWCOMPOSITIONATTRIBDATA layout (зависит от разрядности):
            // 64-bit: int(4) + pad(4) + ptr(8) + size_t(8) = 24 байт
            // 32-bit: int(4) + ptr(4) + size_t(4) = 12 байт
            int ps = Native.POINTER_SIZE;
            int offPData = (ps > 4) ? ps : 4;      // выровнять pData по размеру указателя
            int total = offPData + ps + ps;
            Memory wca = new Memory(total);
            wca.clear();
            wca.setInt(0, WCA_ACCENT_POLICY);
            wca.setPointer(offPData, accent.getPointer());
            if (ps == 8) { wca.setLong(offPData + ps, accent.size()); }
            else { wca.setInt(offPData + ps, accent.size()); }

            boolean ok = User32Ext.INSTANCE.SetWindowCompositionAttribute(hwnd, wca);
            log.info("SetWindowCompositionAttribute(ACRYLIC): {}", ok ? "OK" : "failed");
            return ok;
        } catch (Exception e) {
            log.warn("SetWindowCompositionAttribute недоступен", e);
            return false;
        }
    }

    // ==================== Orchestration ====================

    /**
     * Настроить окно для blur backdrop.
     * <p>
     * Стратегия: Win11 22H2+ — DWMWA_SYSTEMBACKDROP_TYPE (системный Acrylic),
     * fallback Win10 — SetWindowCompositionAttribute (ACCENT_ENABLE_ACRYLICBLURBEHIND).
     * <p>
     * Не модифицирует стили окна (WS_CAPTION и пр.) — StageStyle.TRANSPARENT
     * обеспечивает borderless окно, drag/resize реализованы в JavaFX (RootPane).
     *
     * @return true если backdrop успешно применён
     */
    public boolean prepareMicaWindow(boolean isDark) {
        if (hwnd == null) { return false; }

        // 1. Расширить DWM frame на клиентскую область
        extendFrameIntoClientArea();

        // 2. Dark mode (Win10 1903+)
        setDarkMode(isDark);

        // 3. Backdrop: Win11 API → Win10 fallback
        boolean backdropOk = setWindowBackdrop(DwmSystemBackdropType.ACRYLIC);
        if (!backdropOk) {
            log.info("DWMWA_SYSTEMBACKDROP_TYPE недоступен, пробуем SetWindowCompositionAttribute...");
            backdropOk = setAcrylicViaCompositionAttribute();
        }

        // 4. Скруглённые углы (Win11+, non-fatal на Win10)
        setRoundedCorners();

        return backdropOk;
    }

    // ==================== JNA Structures & Interfaces ====================

    /** Win32 MARGINS structure для DwmExtendFrameIntoClientArea */
    @Structure.FieldOrder({"cxLeftWidth", "cxRightWidth", "cyTopHeight", "cyBottomHeight"})
    public static class MARGINS extends Structure {
        public int cxLeftWidth;
        public int cxRightWidth;
        public int cyTopHeight;
        public int cyBottomHeight;

        public MARGINS() { /* required by JNA */ }

        public MARGINS(int left, int right, int top, int bottom) {
            this.cxLeftWidth = left;
            this.cxRightWidth = right;
            this.cyTopHeight = top;
            this.cyBottomHeight = bottom;
        }
    }

    public enum DwmSystemBackdropType {
        NONE(1), MICA(2), ACRYLIC(3), MICA_ALT(4);
        final int value;
        DwmSystemBackdropType(int v) { this.value = v; }
    }

    /** ACCENT_POLICY для SetWindowCompositionAttribute */
    @Structure.FieldOrder({"accentState", "accentFlags", "gradientColor", "animationId"})
    public static class ACCENT_POLICY extends Structure {
        public int accentState;
        public int accentFlags;
        public int gradientColor; // ABGR
        public int animationId;
    }

    /** JNA-интерфейс к dwmapi.dll */
    public interface Dwm extends Library {
        Dwm INSTANCE = Native.load("dwmapi", Dwm.class);

        WinNT.HRESULT DwmSetWindowAttribute(
                WinDef.HWND hwnd, int dwAttribute,
                PointerType pvAttribute, int cbAttribute);

        WinNT.HRESULT DwmExtendFrameIntoClientArea(
                WinDef.HWND hwnd, MARGINS pMarInset);
    }

    /** JNA-интерфейс к user32.dll — SetWindowCompositionAttribute (Windows 10+) */
    public interface User32Ext extends Library {
        User32Ext INSTANCE = Native.load("user32", User32Ext.class);

        boolean SetWindowCompositionAttribute(WinDef.HWND hwnd, Pointer pData);
    }
}
