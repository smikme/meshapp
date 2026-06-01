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
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;

/**
 * Windows Mica/Acrylic control through the DWM API.
 * <p>
 * Windows 11 22H2+: {@code DWMWA_SYSTEMBACKDROP_TYPE} from dwmapi.dll provides
 * the system Acrylic/Mica backdrop. Windows 10 falls back to
 * {@code SetWindowCompositionAttribute} from user32.dll with
 * {@code ACCENT_ENABLE_ACRYLICBLURBEHIND}.
 * <p>
 * Together with StageStyle.TRANSPARENT this gives the app a custom title bar,
 * matching the macOS implementation, and a blurred backdrop.
 * <p>
 * Dragging and resizing are handled by RootPane through JavaFX EventFilters.
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
     * Reflection path: Window -> TkStage -> PlatformWindow -> getNativeHandle() -> HWND.
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
     * Initializes Windows 10 dark-mode support.
     * Call this once before creating the window.
     * <p>
     * On Windows 10, {@code DWMWA_USE_IMMERSIVE_DARK_MODE} is ignored unless
     * the undocumented uxtheme.dll ordinal 135 ({@code SetPreferredAppMode}) is
     * called first. On Windows 11 these calls are harmless.
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

            // SetPreferredAppMode(AllowDark) enables dark mode at process scope.
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
     * Extends the DWM frame over the entire client area.
     * MARGINS{-1,-1,-1,-1} creates a "sheet of glass": the DWM surface covers
     * the full client area and lets the backdrop show through it.
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

    /** Applies a system backdrop: Mica, Mica Alt, Acrylic, or None. Windows 11 22H2+. */
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
     * Enables or disables dark mode for the title bar.
     * <p>
     * Strategy, from preferred path to fallbacks:
     * 1. DWMWA_USE_IMMERSIVE_DARK_MODE (attribute 20) on Win10 20H1+ and Win11
     * 2. DWMWA_USE_IMMERSIVE_DARK_MODE (attribute 19) on Win10 1809-18985
     * 3. DWMWA_CAPTION_COLOR (attribute 35) on Win11 22000+, with an explicit caption color
     * <p>
     * Raw Pointer + Memory is used instead of BOOLByReference to guarantee that
     * DwmSetWindowAttribute receives the expected LPCVOID payload.
     */
    public boolean setDarkMode(boolean dark) {
        if (hwnd == null) { return false; }
        try {
            // 1. AllowDarkModeForWindow (ordinal 133) is per-window and must run before DWM attributes.
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

            // 2. DWM attributes: attr 20 for Win10 20H1+/Win11, attr 19 for older builds.
            Memory val = new Memory(4);
            val.setInt(0, dark ? 1 : 0);

            long hr20 = DwmRaw.INSTANCE.DwmSetWindowAttribute(
                    hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, val, 4).longValue();
            long hr19 = DwmRaw.INSTANCE.DwmSetWindowAttribute(
                    hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, val, 4).longValue();

            log.info("setDarkMode({}): attr20=0x{} attr19=0x{}",
                    dark, Long.toHexString(hr20), Long.toHexString(hr19));

            // 3. RefreshImmersiveColorPolicyState (ordinal 104) forces the system to refresh.
            if (uxRefreshImmersiveColorPolicy != null) {
                try {
                    uxRefreshImmersiveColorPolicy.invokeVoid(new Object[]{});
                } catch (Exception e) {
                    log.warn("RefreshImmersiveColorPolicyState failed: {}", e.getMessage());
                }
            }

            // 4. Fallback: explicit caption color on Win11 22000+, non-fatal on Win10.
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
     * Sets the caption bar color directly. COLORREF format: 0x00BBGGRR.
     * Available on Win11 22000+ and non-fatal on Win10.
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
     * Native DECORATED title bar without backdrop effects.
     * On Win11 the standard dark title bar visually resembles Mica, so when
     * effects are disabled we set an explicit solid caption color.
     */
    public void applyPlainDecoratedTitleBar(boolean isDark) {
        setDarkMode(isDark);
        setWindowBackdrop(DwmSystemBackdropType.NONE);
        setCaptionColor(isDark ? DARK_CAPTION_COLORREF : LIGHT_CAPTION_COLORREF);
    }

    /**
     * Forces the non-client area, including the title bar, to repaint.
     * <p>
     * A WM_NCACTIVATE deactivate/activate cycle makes Windows repaint the title
     * bar with the latest DWM attributes, including dark mode. SWP_FRAMECHANGED
     * alone is not enough on Win10: it recalculates the frame but does not repaint
     * the caption.
     */
    public void redrawFrame() {
        if (hwnd == null) { return; }
        try {
            // WM_NCACTIVATE deactivates, then activates the non-client area.
            // lParam = -1 prevents client-area updates; only the title bar is repainted.
            User32.INSTANCE.SendMessage(hwnd, WM_NCACTIVATE, new WinDef.WPARAM(0), new WinDef.LPARAM(-1));
            User32.INSTANCE.SendMessage(hwnd, WM_NCACTIVATE, new WinDef.WPARAM(1), new WinDef.LPARAM(-1));
        } catch (Exception e) {
            log.warn("redrawFrame failed", e);
        }
    }

    /** Enables rounded corners on Windows 11+. Non-fatal on Win10. */
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
     * Applies Acrylic blur through SetWindowCompositionAttribute (user32.dll).
     * This undocumented API works with layered windows created by StageStyle.TRANSPARENT.
     * <p>
     * ACCENT_POLICY: {AccentState, AccentFlags, GradientColor(ABGR), AnimationId}, 16 bytes.
     * WINDOWCOMPOSITIONATTRIBDATA: {Attribute(int), pad, pData(ptr), cbData(size_t)}.
 *
     * @param isDark dark theme uses a dark tint; light theme uses a light tint
     * @return true if Acrylic blur was applied successfully
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

            // WINDOWCOMPOSITIONATTRIBDATA layout depends on pointer width:
            // 64-bit: int(4) + pad(4) + ptr(8) + size_t(8) = 24 bytes.
            // 32-bit: int(4) + ptr(4) + size_t(4) = 12 bytes.
            int ps = Native.POINTER_SIZE;
            int offPData = (ps > 4) ? ps : 4;      // Align pData to pointer width.
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
     * Prepares the window for a blurred backdrop.
     * <p>
     * Strategy: Windows 11 22H2+ uses DWMWA_SYSTEMBACKDROP_TYPE for system
     * Acrylic; Windows 10 falls back to SetWindowCompositionAttribute with
     * ACCENT_ENABLE_ACRYLICBLURBEHIND.
     * <p>
     * This does not modify window styles such as WS_CAPTION. StageStyle.TRANSPARENT
     * provides the borderless window, while JavaFX RootPane handles drag and resize.
 *
     * @return true if the backdrop was applied successfully
     */
    public boolean prepareMicaWindow(boolean isDark) {
        if (hwnd == null) { return false; }

        // 1. Dark mode on Win10 1903+.
        setDarkMode(isDark);

        // 2. Backdrop: Win11 API (Mica), then Win10 fallback (Acrylic).
        boolean backdropOk = setWindowBackdrop(DwmSystemBackdropType.MICA);
        if (!backdropOk) {
            log.info("DWMWA_SYSTEMBACKDROP_TYPE недоступен, пробуем SetWindowCompositionAttribute...");
            backdropOk = setAcrylicViaCompositionAttribute(isDark);
        }

        // 3. Rounded corners on Win11+, non-fatal on Win10.
        setRoundedCorners();

        return backdropOk;
    }

    // ==================== JNA Structures & Interfaces ====================

    /** Win32 MARGINS structure for DwmExtendFrameIntoClientArea. */
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

    /** ACCENT_POLICY for SetWindowCompositionAttribute. */
    @Structure.FieldOrder({"accentState", "accentFlags", "gradientColor", "animationId"})
    public static class ACCENT_POLICY extends Structure {
        public int accentState;
        public int accentFlags;
        public int gradientColor; // ABGR
        public int animationId;
    }

    /** JNA interface for dwmapi.dll with typed params for backdrop, corners, and related attributes. */
    public interface Dwm extends Library {
        Dwm INSTANCE = Native.load("dwmapi", Dwm.class);

        WinNT.HRESULT DwmSetWindowAttribute(
                WinDef.HWND hwnd, int dwAttribute,
                PointerType pvAttribute, int cbAttribute);

        WinNT.HRESULT DwmExtendFrameIntoClientArea(
                WinDef.HWND hwnd, MARGINS pMarInset);
    }

    /** JNA interface for dwmapi.dll with raw Pointer params for dark mode and caption color. */
    public interface DwmRaw extends Library {
        DwmRaw INSTANCE = Native.load("dwmapi", DwmRaw.class);

        WinNT.HRESULT DwmSetWindowAttribute(
                WinDef.HWND hwnd, int dwAttribute,
                Pointer pvAttribute, int cbAttribute);
    }

    /** JNA interface for user32.dll SetWindowCompositionAttribute on Windows 10+. */
    public interface User32Ext extends Library {
        User32Ext INSTANCE = Native.load("user32", User32Ext.class);

        boolean SetWindowCompositionAttribute(WinDef.HWND hwnd, Pointer pData);
    }
}
