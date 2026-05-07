package com.meshtastic.client.platform;

import com.sun.jna.Function;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.PointerType;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinUser;
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
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class NativeWinWindowControl {

    private static final Logger log = LoggerFactory.getLogger(NativeWinWindowControl.class);
    private final WinDef.HWND hwnd;

    // DWM attribute constants
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;       // Win10 20H1+ / Win11
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19;   // Win10 1809–18985
    private static final int DWMWA_WINDOW_CORNER_PREFERENCE = 33;      // Win11+
    private static final int DWMWA_CAPTION_COLOR = 35;                 // Win11 22000+

    private static final int DARK_CAPTION_COLORREF = 0x00202020;
    private static final int LIGHT_CAPTION_COLORREF = 0x00F3F3F3;
    private static final int DWMWA_SYSTEMBACKDROP_TYPE = 38;           // Win11 22H2+
    private static final int DWMWCP_DOROUND = 2;

    // Win32 message constants
    private static final int WM_NCACTIVATE = 0x0086;

    // SetWindowCompositionAttribute constants (Windows 10 fallback)
    private static final int WCA_ACCENT_POLICY = 19;
    private static final int ACCENT_ENABLE_ACRYLICBLURBEHIND = 4;

    // UxTheme undocumented ordinals (Win10 dark title bar support)
    private static final int APP_MODE_ALLOW_DARK = 1;
    private static volatile boolean uxThemeInitialized = false;
    private static Function uxSetPreferredAppMode;        // ordinal 135
    private static Function uxAllowDarkModeForWindow;     // ordinal 133
    private static Function uxRefreshImmersiveColorPolicy; // ordinal 104

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

    // ==================== UxTheme Dark Mode (Win10) ====================

    /**
     * Инициализация поддержки тёмного режима на Windows 10.
     * Вызвать один раз до создания окна.
     * <p>
     * На Win10 {@code DWMWA_USE_IMMERSIVE_DARK_MODE} игнорируется без предварительного
     * вызова undocumented uxtheme.dll ordinal 135 ({@code SetPreferredAppMode}).
     * На Win11 эти вызовы безвредны (non-fatal).
     */
    public static void initDarkModeSupport() {
        if (uxThemeInitialized) { return; }
        uxThemeInitialized = true;

        try {
            NativeLibrary uxTheme = NativeLibrary.getInstance("uxtheme");
            uxSetPreferredAppMode = resolveUxThemeOrdinal(uxTheme, "#135", "SetPreferredAppMode");
            uxAllowDarkModeForWindow = resolveUxThemeOrdinal(uxTheme, "#133", "AllowDarkModeForWindow");
            uxRefreshImmersiveColorPolicy = resolveUxThemeOrdinal(
                    uxTheme, "#104", "RefreshImmersiveColorPolicyState");

            // SetPreferredAppMode(AllowDark) — разрешить тёмный режим на уровне процесса
            if (uxSetPreferredAppMode != null) {
                int result = uxSetPreferredAppMode.invokeInt(new Object[]{APP_MODE_ALLOW_DARK});
                log.info("UxTheme: SetPreferredAppMode(AllowDark) = {}", result);
            } else {
                log.debug("UxTheme SetPreferredAppMode unavailable; continuing without process-level dark mode init");
            }
        } catch (Throwable t) {
            log.warn("UxTheme dark mode init unavailable: {}", t.getMessage());
            uxSetPreferredAppMode = null;
            uxAllowDarkModeForWindow = null;
            uxRefreshImmersiveColorPolicy = null;
        }
    }

    private static Function resolveUxThemeOrdinal(NativeLibrary uxTheme, String ordinal, String functionName) {
        try {
            return uxTheme.getFunction(ordinal);
        } catch (Throwable t) {
            log.debug("UxTheme {} ({}) unavailable: {}", functionName, ordinal, t.getMessage());
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

    /**
     * Тёмный режим title bar.
     * <p>
     * Стратегия (от надёжного к фоллбэкам):
     * 1. DWMWA_USE_IMMERSIVE_DARK_MODE (attr 20) — Win10 20H1+, Win11
     * 2. DWMWA_USE_IMMERSIVE_DARK_MODE (attr 19) — Win10 1809–18985
     * 3. DWMWA_CAPTION_COLOR (attr 35) — Win11 22000+ (явный цвет caption)
     * <p>
     * Используем raw Pointer + Memory вместо BOOLByReference чтобы гарантировать
     * корректную передачу LPCVOID в DwmSetWindowAttribute.
     */
    public boolean setDarkMode(boolean dark) {
        if (hwnd == null) { return false; }
        try {
            // 1. AllowDarkModeForWindow (ordinal 133) — per-window, до DWM-атрибутов
            if (uxAllowDarkModeForWindow != null) {
                try {
                    long hwndVal = Pointer.nativeValue(hwnd.getPointer());
                    int res = uxAllowDarkModeForWindow.invokeInt(
                            new Object[]{new Pointer(hwndVal), dark ? 1 : 0});
                    log.info("AllowDarkModeForWindow({}): {}", dark, res);
                } catch (Exception e) {
                    log.warn("AllowDarkModeForWindow failed: {}", e.getMessage());
                }
            }

            // 2. DWM атрибуты (attr 20 для Win10 20H1+/Win11, attr 19 для старых билдов)
            Memory val = new Memory(4);
            val.setInt(0, dark ? 1 : 0);

            long hr20 = DwmRaw.INSTANCE.DwmSetWindowAttribute(
                    hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, val, 4).longValue();
            long hr19 = DwmRaw.INSTANCE.DwmSetWindowAttribute(
                    hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, val, 4).longValue();

            log.info("setDarkMode({}): attr20=0x{} attr19=0x{}",
                    dark, Long.toHexString(hr20), Long.toHexString(hr19));

            // 3. RefreshImmersiveColorPolicyState (ordinal 104) — принудительное обновление
            if (uxRefreshImmersiveColorPolicy != null) {
                try {
                    uxRefreshImmersiveColorPolicy.invokeVoid(new Object[]{});
                } catch (Exception e) {
                    log.warn("RefreshImmersiveColorPolicyState failed: {}", e.getMessage());
                }
            }

            // 4. Fallback: явный цвет caption (Win11 22000+, non-fatal на Win10)
            if (hr20 != 0 && hr19 != 0) {
                setCaptionColor(dark ? DARK_CAPTION_COLORREF : LIGHT_CAPTION_COLORREF);
            }

            return hr20 == 0 || hr19 == 0;
        } catch (Exception e) {
            log.error("Не удалось установить dark mode", e);
            return false;
        }
    }

    /**
     * Установить цвет caption bar напрямую. COLORREF формат: 0x00BBGGRR.
     * Win11 22000+, non-fatal на Win10.
     */
    public boolean setCaptionColor(int colorRef) {
        if (hwnd == null) { return false; }
        try {
            Memory val = new Memory(4);
            val.setInt(0, colorRef);
            long hr = DwmRaw.INSTANCE.DwmSetWindowAttribute(
                    hwnd, DWMWA_CAPTION_COLOR, val, 4).longValue();
            log.info("setCaptionColor(0x{}): HRESULT=0x{}",
                    Integer.toHexString(colorRef), Long.toHexString(hr));
            return hr == 0;
        } catch (Exception e) {
            log.warn("setCaptionColor недоступен", e);
            return false;
        }
    }

    /**
     * Нативный DECORATED title bar без backdrop-эффектов.
     * На Win11 стандартный dark title bar визуально похож на Mica, поэтому
     * при выключенных эффектах задаём явный solid caption color.
     */
    public void applyPlainDecoratedTitleBar(boolean isDark) {
        setDarkMode(isDark);
        setWindowBackdrop(DwmSystemBackdropType.NONE);
        setCaptionColor(isDark ? DARK_CAPTION_COLORREF : LIGHT_CAPTION_COLORREF);
    }

    /**
     * Принудительная перерисовка non-client area (title bar).
     * <p>
     * WM_NCACTIVATE deactivate→activate заставляет Windows полностью перерисовать
     * title bar с новыми DWM-атрибутами (dark mode). SWP_FRAMECHANGED одного
     * недостаточно на Win10 — он пересчитывает рамку, но не перерисовывает caption.
     */
    public void redrawFrame() {
        if (hwnd == null) { return; }
        try {
            // WM_NCACTIVATE: деактивировать → активировать non-client area
            // lParam = -1 предотвращает обновление client area (только title bar)
            User32.INSTANCE.SendMessage(hwnd, WM_NCACTIVATE, new WinDef.WPARAM(0), new WinDef.LPARAM(-1));
            User32.INSTANCE.SendMessage(hwnd, WM_NCACTIVATE, new WinDef.WPARAM(1), new WinDef.LPARAM(-1));
        } catch (Exception e) {
            log.warn("redrawFrame failed", e);
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
     * @param isDark тёмная тема — тинт тёмный, иначе светлый
     * @return true если acrylic blur успешно применён
     */
    public boolean setAcrylicViaCompositionAttribute(boolean isDark) {
        if (hwnd == null) { return false; }
        try {
            ACCENT_POLICY accent = new ACCENT_POLICY();
            accent.accentState = ACCENT_ENABLE_ACRYLICBLURBEHIND;
            accent.accentFlags = 2;
            // ABGR: dark theme → semi-opaque black, light theme → near-transparent
            accent.gradientColor = isDark ? 0xCC000000 : 0x01000000;
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

        // 1. Dark mode (Win10 1903+)
        setDarkMode(isDark);

        // 2. Backdrop: Win11 API (Mica) → Win10 fallback (Acrylic)
        boolean backdropOk = setWindowBackdrop(DwmSystemBackdropType.MICA);
        if (!backdropOk) {
            log.info("DWMWA_SYSTEMBACKDROP_TYPE недоступен, пробуем SetWindowCompositionAttribute...");
            backdropOk = setAcrylicViaCompositionAttribute(isDark);
        }

        // 3. Скруглённые углы (Win11+, non-fatal на Win10)
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

    /** JNA-интерфейс к dwmapi.dll (typed params — для backdrop, corners и пр.) */
    public interface Dwm extends Library {
        Dwm INSTANCE = Native.load("dwmapi", Dwm.class);

        WinNT.HRESULT DwmSetWindowAttribute(
                WinDef.HWND hwnd, int dwAttribute,
                PointerType pvAttribute, int cbAttribute);

        WinNT.HRESULT DwmExtendFrameIntoClientArea(
                WinDef.HWND hwnd, MARGINS pMarInset);
    }

    /** JNA-интерфейс к dwmapi.dll (raw Pointer — для dark mode, caption color) */
    public interface DwmRaw extends Library {
        DwmRaw INSTANCE = Native.load("dwmapi", DwmRaw.class);

        WinNT.HRESULT DwmSetWindowAttribute(
                WinDef.HWND hwnd, int dwAttribute,
                Pointer pvAttribute, int cbAttribute);
    }

    /** JNA-интерфейс к user32.dll — SetWindowCompositionAttribute (Windows 10+) */
    public interface User32Ext extends Library {
        User32Ext INSTANCE = Native.load("user32", User32Ext.class);

        boolean SetWindowCompositionAttribute(WinDef.HWND hwnd, Pointer pData);
    }
}
