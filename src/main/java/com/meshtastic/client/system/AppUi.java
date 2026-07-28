package com.meshtastic.client.system;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Small bridge from protocol/services into the active presentation layer.
 * <p>
 * Core runtime code must not depend directly on JavaFX. Desktop and terminal
 * launchers install their own implementation before opening connections.
 */
public final class AppUi {

    private static final AtomicReference<Bridge> BRIDGE = new AtomicReference<>(new NoOpBridge());

    private AppUi() {}

    public enum StatusType {
        INFO,
        SUCCESS,
        WARNING,
        ERROR
    }

    public interface Bridge {
        default void runLater(Runnable action) {
            if (action != null) {
                action.run();
            }
        }

        default void showStatus(StatusType type, String message) {
        }

        default void setChatUnreadDot(boolean show) {
        }

        default void updateHeader(String shortName, String longName, String nodeId) {
        }

        default void requestBlePasskey(long requestId,
                                       String deviceAddress,
                                       IntConsumer onSubmit,
                                       Runnable onCancel) {
            if (onCancel != null) {
                onCancel.run();
            }
        }

        default void dismissBlePasskey(long requestId) {
        }

        default boolean isPrimaryWindowFocused() {
            return false;
        }

        default void addPrimaryWindowFocusListener(Consumer<Boolean> listener) {
        }

        default boolean isTerminal() {
            return false;
        }
    }

    public static void install(Bridge bridge) {
        BRIDGE.set(Objects.requireNonNullElseGet(bridge, NoOpBridge::new));
    }

    public static void reset() {
        BRIDGE.set(new NoOpBridge());
    }

    public static void runLater(Runnable action) {
        BRIDGE.get().runLater(action);
    }

    public static void showStatus(StatusType type, String message) {
        BRIDGE.get().showStatus(type != null ? type : StatusType.INFO, message);
    }

    public static void setChatUnreadDot(boolean show) {
        BRIDGE.get().setChatUnreadDot(show);
    }

    public static void updateHeader(String shortName, String longName, String nodeId) {
        BRIDGE.get().updateHeader(shortName, longName, nodeId);
    }

    public static void requestBlePasskey(long requestId,
                                         String deviceAddress,
                                         IntConsumer onSubmit,
                                         Runnable onCancel) {
        BRIDGE.get().requestBlePasskey(requestId, deviceAddress, onSubmit, onCancel);
    }

    public static void dismissBlePasskey(long requestId) {
        BRIDGE.get().dismissBlePasskey(requestId);
    }

    public static boolean isPrimaryWindowFocused() {
        return BRIDGE.get().isPrimaryWindowFocused();
    }

    public static void addPrimaryWindowFocusListener(Consumer<Boolean> listener) {
        BRIDGE.get().addPrimaryWindowFocusListener(listener);
    }

    public static boolean isTerminal() {
        return BRIDGE.get().isTerminal();
    }

    private static final class NoOpBridge implements Bridge {
    }
}
