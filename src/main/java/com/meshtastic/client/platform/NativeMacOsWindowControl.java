package com.meshtastic.client.platform;

import com.meshtastic.client.connection.ble.macos.ObjCRuntime;
import com.sun.jna.*;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * macOS: NSVisualEffectView через JNA + Objective-C runtime (libobjc.dylib).
 * Добавляет vibrancy-эффект (frosted glass blur) за JavaFX контентом окна.
 *
 * На Apple Silicon (arm64) нельзя использовать objc_msgSend с varargs через JNA —
 * нужен com.sun.jna.Function.invoke() с фиксированными типами аргументов.
 *
 * Для установки размера NSVisualEffectView используем Auto Layout constraints
 * вместо initWithFrame: (передача CGRect struct через JNA на arm64 проблематична).
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class NativeMacOsWindowControl {

    private static final Logger log = LoggerFactory.getLogger(NativeMacOsWindowControl.class);
    private static final String MINIATURIZE_OBSERVER_CLASS_NAME = "MeshAppTrayMiniaturizeObserver";
    private static final String MINIATURIZE_NOTIFICATION_NAME = "NSWindowDidMiniaturizeNotification";

    private static final NativeLibrary OBJC = NativeLibrary.getInstance("objc");
    private static final Function OBJC_MSG_SEND = OBJC.getFunction("objc_msgSend");
    private static final Function GET_CLASS = OBJC.getFunction("objc_getClass");
    private static final Function SEL_REGISTER = OBJC.getFunction("sel_registerName");

    private static final Map<Long, Runnable> MINIATURIZE_HANDLERS = new ConcurrentHashMap<>();

    private static long miniaturizeObserverClass;
    private static Callback miniaturizeCallback;

    private final long nsWindow;
    private final long nsView;

    static {
        initMiniaturizeObserverClass();
    }

    public NativeMacOsWindowControl(Window window) {
        this.nsWindow = extractNsWindow(window);
        this.nsView = extractNsView(window);
    }

    public long getNativeWindowHandle() {
        return nsWindow;
    }

    public long getNativeViewHandle() {
        return nsView;
    }

    /**
     * Рефлексия: Window → TkStage → PlatformWindow → getNativeHandle() → NSWindow pointer
     */
    private static long extractNsWindow(Window window) {
        try {
            Object platformWindow = extractPlatformWindow(window);
            if (platformWindow == null) {
                return 0;
            }

            Method getNativeHandle = platformWindow.getClass().getMethod("getNativeHandle");
            getNativeHandle.setAccessible(true);
            return (long) getNativeHandle.invoke(platformWindow);
        } catch (Exception e) {
            log.error("Не удалось получить NSWindow", e);
            return 0;
        }
    }

    private static long extractNsView(Window window) {
        try {
            Object platformWindow = extractPlatformWindow(window);
            if (platformWindow == null) {
                return 0;
            }

            Method getView = platformWindow.getClass().getMethod("getView");
            getView.setAccessible(true);
            Object glassView = getView.invoke(platformWindow);
            if (glassView == null) {
                return 0;
            }

            Method getNativeView = glassView.getClass().getMethod("getNativeView");
            getNativeView.setAccessible(true);
            return (long) getNativeView.invoke(glassView);
        } catch (Exception e) {
            log.error("Не удалось получить NSView JavaFX", e);
            return 0;
        }
    }

    private static Object extractPlatformWindow(Window window) throws Exception {
        Method getPeer = Window.class.getDeclaredMethod("getPeer");
        getPeer.setAccessible(true);
        Object tkStage = getPeer.invoke(window);
        if (tkStage == null) {
            return null;
        }

        Method getPlatformWindow = tkStage.getClass().getDeclaredMethod("getPlatformWindow");
        getPlatformWindow.setAccessible(true);
        return getPlatformWindow.invoke(tkStage);
    }

    /**
     * Делает окно видимым в Cmd+Tab (App Switcher).
     * JavaFX StageStyle.TRANSPARENT создаёт NSWindow с NSWindowStyleMaskBorderless (0).
     * Добавляем Titled + FullSizeContentView + Resizable для нативного вида и ресайза.
     * Кастомный resize из RootPane отключается на macOS — ресайз обрабатывает macOS нативно.
     */
    public void makeVisibleInAppSwitcher() {
        if (nsWindow == 0) { return; }
        long pool = ObjCRuntime.createAutoreleasePool();
        try {
            long currentMask = msgSend(nsWindow, "styleMask");
            long titled = 1L;                // NSWindowStyleMaskTitled
            long resizable = 1L << 3;        // NSWindowStyleMaskResizable
            long fullSizeContent = 1L << 15; // NSWindowStyleMaskFullSizeContentView

            long newMask = currentMask | titled | resizable | fullSizeContent;
            msgSendLong(nsWindow, "setStyleMask:", newMask);

            // Скрыть нативный titlebar
            msgSendBool(nsWindow, "setTitlebarAppearsTransparent:", true);
            msgSendLong(nsWindow, "setTitleVisibility:", 1L); // NSWindowTitleHidden

            // Полностью скрыть NSTitlebarContainerView — убирает нативные кнопки,
            // иконку документа и весь titlebar контейнер (у нас свой titlebar в RootPane)
            hideTitlebarContainer();

            // Установить title для NSWindow (AltTab и другие app-switcher'ы используют его)
            long nsTitle = createNSString("MeshApp");
            try {
                msgSendId(nsWindow, "setTitle:", nsTitle);
            } finally {
                ObjCRuntime.release(nsTitle);
            }

            // Отключить нативное перемещение — drag реализован в RootPane
            msgSendBool(nsWindow, "setMovable:", false);

            // collectionBehavior: добавить Managed + ParticipatesInCycle
            long behavior = msgSend(nsWindow, "collectionBehavior");
            long managed = 1L << 2;        // NSWindowCollectionBehaviorManaged
            long participates = 1L << 5;    // NSWindowCollectionBehaviorParticipatesInCycle
            msgSendLong(nsWindow, "setCollectionBehavior:", behavior | managed | participates);

            log.info("NSWindow styleMask обновлён для App Switcher: {} → {} collectionBehavior: {} → {}",
                    currentMask, newMask, behavior, behavior | managed | participates);
        } catch (Throwable t) {
            log.error("Не удалось настроить styleMask для App Switcher", t);
        } finally {
            ObjCRuntime.drainAutoreleasePool(pool);
        }
    }

    /**
     * Подход:
     * 1. Создаём NSVisualEffectView с init (zero frame).
     * 2. Добавляем как subview в contentView позади JavaFX layer.
     * 3. Используем Auto Layout constraints чтобы view заполнил весь contentView.
     *    Это надёжнее чем initWithFrame/autoresizingMask, т.к. не требует
     *    передачи CGRect struct через JNA на arm64.
     */
    public boolean applyVisualEffect(boolean darkMode) {
        if (nsWindow == 0) { return false; }
        long pool = ObjCRuntime.createAutoreleasePool();
        long vev = 0;
        try {
            // Получить contentView окна
            long contentView = msgSend(nsWindow, "contentView");
            if (contentView == 0) { return false; }

            // Разрешить прозрачность окна
            msgSendBool(nsWindow, "setOpaque:", false);

            // [nsWindow setBackgroundColor:[NSColor clearColor]]
            long nsColorClass = cls("NSColor");
            long clearColor = msgSend(nsColorClass, "clearColor");
            msgSendId(nsWindow, "setBackgroundColor:", clearColor);

            // Сделать contentView layer-backed
            msgSendBool(contentView, "setWantsLayer:", true);

            // Создать NSVisualEffectView
            long vevClass = cls("NSVisualEffectView");
            vev = msgSend(vevClass, "alloc");
            vev = msgSend(vev, "init");

            // material = NSVisualEffectMaterialHUDWindow (13) — выраженный frosted glass
            msgSendLong(vev, "setMaterial:", 13L);

            // blendingMode = NSVisualEffectBlendingModeBehindWindow (0)
            msgSendLong(vev, "setBlendingMode:", 0L);

            // state = NSVisualEffectStateActive (1) — всегда активен, даже когда окно не в фокусе
            msgSendLong(vev, "setState:", 1L);

            // Установить appearance (dark/light)
            setAppearanceOnView(vev, darkMode);

            // Отключить autoresizing mask → используем Auto Layout
            // setTranslatesAutoresizingMaskIntoConstraints:NO
            msgSendBool(vev, "setTranslatesAutoresizingMaskIntoConstraints:", false);

            // Вставить за всеми subview: [contentView addSubview:vev positioned:NSWindowBelow relativeTo:nil]
            msgSendAddSubview(contentView, vev);

            // Auto Layout: привязать все 4 стороны к contentView
            pinToSuperview(contentView, vev);

            // Тень окна
            msgSendBool(nsWindow, "setHasShadow:", true);

            // Скруглённые углы — только на NSVisualEffectView (визуальный эффект),
            // НЕ на contentView, чтобы masksToBounds не обрезал hit-test зону по краям
            msgSendBool(vev, "setWantsLayer:", true);
            long vevLayer = msgSend(vev, "layer");
            if (vevLayer != 0) {
                setDoubleProperty(vevLayer, "cornerRadius", 10.0);
                msgSendBool(vevLayer, "setMasksToBounds:", true);
            }

            log.info("NSVisualEffectView применён (material=HUDWindow, blur behind window, cornerRadius=10)");
            return true;
        } catch (Throwable t) {
            log.error("Не удалось применить NSVisualEffectView", t);
            return false;
        } finally {
            ObjCRuntime.release(vev);
            ObjCRuntime.drainAutoreleasePool(pool);
        }
    }

    /**
     * Auto Layout: привязать view ко всем 4 сторонам superview с отступом 0.
     * Эквивалент:
     *   [view.leadingAnchor constraintEqualToAnchor:superview.leadingAnchor].active = YES
     *   [view.trailingAnchor constraintEqualToAnchor:superview.trailingAnchor].active = YES
     *   [view.topAnchor constraintEqualToAnchor:superview.topAnchor].active = YES
     *   [view.bottomAnchor constraintEqualToAnchor:superview.bottomAnchor].active = YES
     */
    private static void pinToSuperview(long superview, long view) {
        String[] anchorNames = {"leadingAnchor", "trailingAnchor", "topAnchor", "bottomAnchor"};
        for (String anchor : anchorNames) {
            long viewAnchor = msgSend(view, anchor);
            long superAnchor = msgSend(superview, anchor);
            long constraint = msgSendId(viewAnchor, "constraintEqualToAnchor:", superAnchor);
            msgSendBool(constraint, "setActive:", true);
        }
    }

    /**
     * Обновить appearance на NSVisualEffectView при смене темы.
     * Находит существующий NSVisualEffectView среди subviews contentView.
     */
    public void updateVisualEffectAppearance(boolean dark) {
        if (nsWindow == 0) { return; }
        try {
            long contentView = msgSend(nsWindow, "contentView");
            if (contentView == 0) { return; }

            // Перебрать subviews, найти NSVisualEffectView
            long subviews = msgSend(contentView, "subviews");
            long count = msgSend(subviews, "count");
            long vevClass = cls("NSVisualEffectView");

            for (long i = 0; i < count; i++) {
                long subview = msgSendId(subviews, "objectAtIndex:", i);
                // isKindOfClass: NSVisualEffectView
                long isVev = OBJC_MSG_SEND.invokeLong(new Object[]{subview, sel("isKindOfClass:"), vevClass});
                if (isVev != 0) {
                    setAppearanceOnView(subview, dark);
                    log.info("NSVisualEffectView appearance обновлён: {}", dark ? "DarkAqua" : "Aqua");
                    break;
                }
            }
        } catch (Throwable t) {
            log.error("Не удалось обновить appearance NSVisualEffectView", t);
        }
    }

    /** Переключить NSAppearance на окне (DarkAqua / Aqua) */
    public void setDarkMode(boolean dark) {
        if (nsWindow == 0) { return; }
        try {
            setAppearanceOnView(nsWindow, dark);
        } catch (Throwable t) {
            log.error("Не удалось установить appearance", t);
        }
    }

    /**
     * Скрыть окно из window list, не вводя его предварительно в native miniaturized-state.
     */
    public void hideToTray() {
        if (nsWindow == 0) { return; }
        try {
            msgSendId(nsWindow, "orderOut:", 0L);
        } catch (Throwable t) {
            log.error("Не удалось скрыть NSWindow в tray-state", t);
        }
    }

    /**
     * Увести уже miniaturized окно в tray-state, чтобы оно не оставалось в Dock.
     */
    public void hideMiniaturizedToTray() {
        if (nsWindow == 0) { return; }
        try {
            msgSendId(nsWindow, "deminiaturize:", 0L);
            msgSendId(nsWindow, "orderOut:", 0L);
        } catch (Throwable t) {
            log.error("Не удалось перевести miniaturized NSWindow в tray-state", t);
        }
    }

    /**
     * Вернуть ранее скрытое через orderOut окно на экран.
     */
    public void restoreFromTray() {
        if (nsWindow == 0) { return; }
        try {
            msgSendId(nsWindow, "deminiaturize:", 0L);
            makeKeyAndOrderFront();
        } catch (Throwable t) {
            log.error("Не удалось восстановить NSWindow из tray", t);
        }
    }

    /**
     * Поднять окно наверх и сделать его key window, чтобы оно сразу принимало клавиатурный ввод.
     */
    public void makeKeyAndOrderFront() {
        if (nsWindow == 0) { return; }
        try {
            msgSendId(nsWindow, "makeKeyAndOrderFront:", 0L);
            msgSend(nsWindow, "orderFrontRegardless");
        } catch (Throwable t) {
            log.error("Не удалось перевести NSWindow в key state", t);
        }
    }

    /**
     * Передать first responder в JavaFX Glass view, чтобы аппаратный keyDown не оставался на самом NSWindow.
     */
    public void focusTextInputView() {
        if (nsWindow == 0 || nsView == 0) {
            return;
        }
        try {
            if (msgSendBoolWithId(nsWindow, "makeFirstResponder:", nsView)) {
                return;
            }

            long contentView = msgSend(nsWindow, "contentView");
            if (contentView != 0) {
                msgSendBoolWithId(nsWindow, "makeFirstResponder:", contentView);
            }
        } catch (Throwable t) {
            log.error("Не удалось передать first responder в JavaFX view", t);
        }
    }

    /**
     * Нативный перехват минимизации окна macOS.
     * Нужен для DECORATED окна, где native traffic-light minimise может не дойти до JavaFX iconifiedProperty.
     */
    public long installMiniaturizeObserver(Runnable onMiniaturize) {
        if (nsWindow == 0 || onMiniaturize == null) {
            return 0;
        }

        long observer = 0;
        boolean installed = false;
        try {
            observer = ObjCRuntime.allocInitClass(miniaturizeObserverClass);
            if (observer == 0) {
                return 0;
            }

            MINIATURIZE_HANDLERS.put(observer, onMiniaturize);

            long center = msgSend(cls("NSNotificationCenter"), "defaultCenter");
            long name = createNSString(MINIATURIZE_NOTIFICATION_NAME);
            try {
                OBJC_MSG_SEND.invokeLong(new Object[]{
                        center,
                        sel("addObserver:selector:name:object:"),
                        observer,
                        sel("windowDidMiniaturize:"),
                        name,
                        nsWindow
                });
                installed = true;
                return observer;
            } finally {
                ObjCRuntime.release(name);
            }
        } catch (Throwable t) {
            log.error("Не удалось установить observer минимизации NSWindow", t);
            return 0;
        } finally {
            if (!installed && observer != 0) {
                MINIATURIZE_HANDLERS.remove(observer);
                ObjCRuntime.release(observer);
            }
        }
    }

    public static void removeMiniaturizeObserver(long observer) {
        if (observer == 0) {
            return;
        }

        MINIATURIZE_HANDLERS.remove(observer);
        try {
            long center = msgSend(cls("NSNotificationCenter"), "defaultCenter");
            OBJC_MSG_SEND.invokeLong(new Object[]{
                    center,
                    sel("removeObserver:"),
                    observer
            });
        } catch (Throwable t) {
            log.warn("Не удалось удалить observer минимизации NSWindow", t);
        } finally {
            ObjCRuntime.release(observer);
        }
    }

    /**
     * Скрыть NSTitlebarContainerView — контейнер нативных кнопок и иконки в titlebar.
     * Ищем его среди subviews contentView.superview (themeFrame).
     */
    private void hideTitlebarContainer() {
        try {
            long contentView = msgSend(nsWindow, "contentView");
            if (contentView == 0) { return; }
            long themeFrame = msgSend(contentView, "superview");
            if (themeFrame == 0) { return; }

            long subviews = msgSend(themeFrame, "subviews");
            long count = msgSend(subviews, "count");
            long targetClassName = createNSString("NSTitlebarContainerView");

            try {
                for (long i = 0; i < count; i++) {
                    long subview = msgSendId(subviews, "objectAtIndex:", i);
                    long className = msgSend(subview, "className");
                    long isEqual = OBJC_MSG_SEND.invokeLong(new Object[]{
                            className, sel("isEqualToString:"), targetClassName});
                    if (isEqual != 0) {
                        msgSendBool(subview, "setHidden:", true);
                        log.info("NSTitlebarContainerView скрыт");
                        break;
                    }
                }
            } finally {
                ObjCRuntime.release(targetClassName);
            }
        } catch (Throwable t) {
            log.warn("Не удалось скрыть NSTitlebarContainerView", t);
        }
    }

    private void setAppearanceOnView(long view, boolean dark) {
        long pool = ObjCRuntime.createAutoreleasePool();
        String name = dark ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua";
        long nsString = createNSString(name);
        try {
            long nsAppearanceClass = cls("NSAppearance");
            long appearance = msgSendId(nsAppearanceClass, "appearanceNamed:", nsString);
            msgSendId(view, "setAppearance:", appearance);
        } finally {
            ObjCRuntime.release(nsString);
            ObjCRuntime.drainAutoreleasePool(pool);
        }
    }

    // ====== Low-level helpers — фиксированные сигнатуры для arm64 ABI ======

    private static long cls(String name) {
        return GET_CLASS.invokeLong(new Object[]{name});
    }

    private static long sel(String name) {
        return SEL_REGISTER.invokeLong(new Object[]{name});
    }

    /** objc_msgSend(receiver, selector) → id (long) */
    private static long msgSend(long receiver, String selectorName) {
        return OBJC_MSG_SEND.invokeLong(new Object[]{receiver, sel(selectorName)});
    }

    /** objc_msgSend(receiver, selector, id) → id — для методов с одним object-аргументом */
    private static long msgSendId(long receiver, String selectorName, long arg) {
        return OBJC_MSG_SEND.invokeLong(new Object[]{receiver, sel(selectorName), arg});
    }

    /** objc_msgSend(receiver, selector, id) → bool */
    private static boolean msgSendBoolWithId(long receiver, String selectorName, long arg) {
        return OBJC_MSG_SEND.invokeLong(new Object[]{receiver, sel(selectorName), arg}) != 0;
    }

    /** objc_msgSend(receiver, selector, long) — для setMaterial:, setState: и т.д. */
    private static void msgSendLong(long receiver, String selectorName, long arg) {
        OBJC_MSG_SEND.invokeLong(new Object[]{receiver, sel(selectorName), arg});
    }

    /** objc_msgSend(receiver, selector, bool) — для setWantsLayer:, setActive: и т.д. */
    private static void msgSendBool(long receiver, String selectorName, boolean arg) {
        OBJC_MSG_SEND.invokeLong(new Object[]{receiver, sel(selectorName), arg ? 1L : 0L});
    }

    /**
     * Установка CGFloat свойства через KVC: [receiver setValue:@(value) forKey:key].
     * Используем NSNumber numberWithInteger: чтобы обойти проблемы arm64 ABI с float/double.
     * CALayer cornerRadius принимает NSNumber и конвертирует в CGFloat.
     */
    private static void setDoubleProperty(long receiver, String key, double value) {
        long pool = ObjCRuntime.createAutoreleasePool();
        long nsKey = 0;
        long nsNumberClass = cls("NSNumber");
        try {
            // numberWithInteger: принимает long — надёжно на arm64
            long nsNumber = msgSendId(nsNumberClass, "numberWithInteger:", (long) value);
            nsKey = createNSString(key);
            OBJC_MSG_SEND.invokeLong(new Object[]{receiver, sel("setValue:forKey:"), nsNumber, nsKey});
        } finally {
            ObjCRuntime.release(nsKey);
            ObjCRuntime.drainAutoreleasePool(pool);
        }
    }

    /** [contentView addSubview:vev positioned:NSWindowBelow(-1) relativeTo:nil(0)] */
    private static void msgSendAddSubview(long contentView, long subview) {
        OBJC_MSG_SEND.invokeLong(new Object[]{
                contentView, sel("addSubview:positioned:relativeTo:"),
                subview, -1L, Pointer.NULL
        });
    }

    private static long createNSString(String javaString) {
        long nsStringClass = cls("NSString");
        long alloc = msgSend(nsStringClass, "alloc");
        return OBJC_MSG_SEND.invokeLong(new Object[]{
                alloc, sel("initWithUTF8String:"), javaString
        });
    }

    private static synchronized void initMiniaturizeObserverClass() {
        if (miniaturizeObserverClass != 0) {
            return;
        }

        miniaturizeCallback = (MiniaturizeObserverCallback) (self, cmd, notification) -> {
            Runnable handler = MINIATURIZE_HANDLERS.get(self);
            if (handler != null) {
                handler.run();
            }
        };

        miniaturizeObserverClass = ObjCRuntime.createClass(MINIATURIZE_OBSERVER_CLASS_NAME, "NSObject");
        ObjCRuntime.addMethod(miniaturizeObserverClass, "windowDidMiniaturize:", miniaturizeCallback, "v@:@");
        ObjCRuntime.registerClass(miniaturizeObserverClass);
    }

    private interface MiniaturizeObserverCallback extends Callback {
        void invoke(long self, long cmd, long notification);
    }
}
