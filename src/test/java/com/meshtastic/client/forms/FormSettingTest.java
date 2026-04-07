package com.meshtastic.client.forms;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.proto.ModuleConfigProtos;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormSettingTest {

    @TempDir
    Path tempHome;

    private final List<ProtocolHandler> handlersToShutdown = new ArrayList<>();

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @BeforeEach
    void setUp() {
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void tearDown() {
        for (ProtocolHandler handler : handlersToShutdown) {
            handler.shutdown();
        }
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void shouldUseImplicitBleModuleSaveOnlyForSingleBleMqttSection() {
        ModuleConfigProtos.ModuleConfig mqttConfig = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setMqtt(ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder().setEnabled(false).build())
                .build();

        assertTrue(FormSetting.shouldUseImplicitBleModuleSave(
                ConnectionType.BLE, false, false, List.of(), List.of(mqttConfig)));
    }

    @Test
    void shouldKeepTransactionalSaveForOtherCases() {
        ModuleConfigProtos.ModuleConfig serialConfig = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setSerial(ModuleConfigProtos.ModuleConfig.SerialConfig.newBuilder().setEnabled(true).build())
                .build();
        ConfigProtos.Config deviceConfig = ConfigProtos.Config.newBuilder()
                .setDevice(ConfigProtos.Config.DeviceConfig.newBuilder().build())
                .build();

        assertFalse(FormSetting.shouldUseImplicitBleModuleSave(
                ConnectionType.TCP, false, false, List.of(), List.of(serialConfig)));
        assertFalse(FormSetting.shouldUseImplicitBleModuleSave(
                ConnectionType.BLE, true, false, List.of(), List.of(serialConfig)));
        assertFalse(FormSetting.shouldUseImplicitBleModuleSave(
                ConnectionType.BLE, false, false, List.of(deviceConfig), List.of()));
        assertFalse(FormSetting.shouldUseImplicitBleModuleSave(
                ConnectionType.BLE, false, false, List.of(), List.of(serialConfig)));
    }

    @Test
    void shouldAddExtraSettleDelayBeforeCommitForAllTransports() {
        FormSetting form = onFxThread(FormSetting::new);

        long tcpDelay = onFxThread(() -> (Long) invokeReturning(
                form,
                "getConfigSaveInterTaskDelayMs",
                new Class<?>[] { ConnectionType.class, int.class, int.class },
                ConnectionType.TCP, 0, 2));
        long bleDelay = onFxThread(() -> (Long) invokeReturning(
                form,
                "getConfigSaveInterTaskDelayMs",
                new Class<?>[] { ConnectionType.class, int.class, int.class },
                ConnectionType.BLE, 0, 2));

        assertEquals(1_200L, tcpDelay);
        assertEquals(1_350L, bleDelay);
    }

    @Test
    void clearsLoadedConfigWhenConnectionIsDisconnected() throws Exception {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("Test radio", "localhost", 4403);
        entry.setConnected(true);
        manager.addEntry(entry);

        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        state.addConfig(ConfigProtos.Config.newBuilder()
                .setDevice(ConfigProtos.Config.DeviceConfig.newBuilder().build())
                .build());
        deviceStates(manager).put(entry.getId(), state);
        protocolHandlers(manager).put(entry.getId(), track(new ProtocolHandler(new FakeConnection())));

        FormSetting form = onFxThread(() -> {
            FormSetting created = new FormSetting();
            created.formInit();
            created.formOpen();
            return created;
        });

        assertNotNull(onFxThread(() -> configTree(form).getRoot()));
        assertFalse(onFxThread(() -> saveButton(form).isDisable()));
        assertFalse(onFxThread(() -> originalConfigs(form).isEmpty()));

        entry.setConnected(false);
        invoke(manager, "cleanupConnection", entry.getId());
        invoke(manager, "fireChanged");
        waitForFxEvents();

        assertNull(onFxThread(() -> configTree(form).getRoot()));
        assertTrue(onFxThread(() -> saveButton(form).isDisable()));
        assertEquals("Нет подключения к радио", onFxThread(() -> statusLabel(form).getText()));
        assertTrue(onFxThread(() -> originalConfigs(form).isEmpty()));
        assertNull(onFxThread(() -> fullConfigRoot(form)));
    }

    @Test
    void databaseResetConfirmRequiresAcknowledgementBeforeConfirmIsEnabled() {
        AtomicBoolean confirmed = new AtomicBoolean(false);

        FormSetting form = onFxThread(FormSetting::new);
        VBox panel = onFxThread(() -> (VBox) invokeReturning(
                form,
                "buildDatabaseResetConfirmationPanel",
                new Class<?>[] { Runnable.class },
                (Runnable) () -> confirmed.set(true)));

        CheckBox acknowledgeCheckBox = onFxThread(() -> findFirst(panel, CheckBox.class));
        Button confirmButton = onFxThread(() -> findButtonByText(panel, "Удалить данные"));

        assertNotNull(acknowledgeCheckBox);
        assertNotNull(confirmButton);
        assertTrue(onFxThread(confirmButton::isDisable));

        onFxThread(() -> {
            confirmButton.fire();
            return null;
        });
        assertFalse(confirmed.get());

        onFxThread(() -> {
            acknowledgeCheckBox.setSelected(true);
            return null;
        });
        assertFalse(onFxThread(confirmButton::isDisable));

        onFxThread(() -> {
            confirmButton.fire();
            return null;
        });
        assertTrue(confirmed.get());
    }

    private ProtocolHandler track(ProtocolHandler handler) {
        handlersToShutdown.add(handler);
        return handler;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, DeviceState> deviceStates(ConnectionManager manager) {
        return (Map<String, DeviceState>) readField(manager, "deviceStates");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ProtocolHandler> protocolHandlers(ConnectionManager manager) {
        return (Map<String, ProtocolHandler>) readField(manager, "protocolHandlers");
    }

    @SuppressWarnings("unchecked")
    private static TreeTableView<ConfigTreeItem> configTree(FormSetting form) {
        return (TreeTableView<ConfigTreeItem>) readField(form, "configTree");
    }

    @SuppressWarnings("unchecked")
    private static List<ConfigProtos.Config> originalConfigs(FormSetting form) {
        return (List<ConfigProtos.Config>) readField(form, "originalConfigs");
    }

    @SuppressWarnings("unchecked")
    private static TreeItem<ConfigTreeItem> fullConfigRoot(FormSetting form) {
        return (TreeItem<ConfigTreeItem>) readField(form, "fullConfigRoot");
    }

    private static Button saveButton(FormSetting form) {
        return (Button) readField(form, "saveConfigBtn");
    }

    private static Label statusLabel(FormSetting form) {
        return (Label) readField(form, "configStatusLabel");
    }

    private static Button findButtonByText(Parent root, String text) {
        Button button = findFirst(root, Button.class);
        if (button != null && text.equals(button.getText())) {
            return button;
        }
        for (Node child : root.getChildrenUnmodifiable()) {
            if (child instanceof Button childButton && text.equals(childButton.getText())) {
                return childButton;
            }
            if (child instanceof Parent childParent) {
                Button nested = findButtonByText(childParent, text);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Node> T findFirst(Parent root, Class<T> type) {
        for (Node child : root.getChildrenUnmodifiable()) {
            if (type.isInstance(child)) {
                return (T) child;
            }
            if (child instanceof Parent childParent) {
                T nested = findFirst(childParent, type);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read field " + fieldName, e);
        }
    }

    private static void invoke(Object target, String methodName, Object... args) {
        try {
            Class<?>[] parameterTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                parameterTypes[i] = args[i].getClass();
            }
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke method " + methodName, e);
        }
    }

    private static Object invokeReturning(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke method " + methodName, e);
        }
    }

    private static void waitForFxEvents() {
        onFxThread(() -> null);
    }

    private static <T> T onFxThread(FxSupplier<T> supplier) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                result.set(supplier.get());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });

        await(latch);
        if (failure.get() != null) {
            throw new AssertionError("JavaFX task failed", failure.get());
        }
        return result.get();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for JavaFX task");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for JavaFX task", e);
        }
    }

    @FunctionalInterface
    private interface FxSupplier<T> {
        T get() throws Exception;
    }

    private static final class FakeConnection implements MeshtasticConnection {

        @Override
        public void connect() throws ConnectionException {
            // no-op
        }

        @Override
        public void disconnect() {
            // no-op
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void sendBytes(byte[] data) {
            // no-op
        }

        @Override
        public void setDataListener(Consumer<byte[]> listener) {
            // no-op
        }

        @Override
        public void setConnectionListener(ConnectionListener listener) {
            // no-op
        }
    }
}
