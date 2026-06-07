package com.meshtastic.client.forms;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.connection.ConnectionException;
import com.meshtastic.client.connection.ConnectionListener;
import com.meshtastic.client.connection.MeshtasticConnection;
import com.meshtastic.client.forms.settings.ConfigSavePolicy;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.ConfigTreeItem;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.protocol.ProtocolHandler;
import com.meshtastic.client.service.ConnectionManager;
import com.meshtastic.client.system.FormManager;
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
import org.meshtastic.proto.MeshProtos;
import org.meshtastic.proto.ModuleConfigProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class FormSettingTest {

    private static final Logger log = LoggerFactory.getLogger(
        FormSettingTest.class
    );

    @TempDir
    Path tempHome;

    private final List<ProtocolHandler> handlersToShutdown = new ArrayList<>();
    private String previousLanguage;

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @BeforeEach
    void setUp() {
        previousLanguage = I18n.getLanguageTag();
        I18n.setLanguageTagForTests(I18n.LANGUAGE_RU);
        TestEnvironmentSupport.setUserHome(tempHome);
        TestEnvironmentSupport.resetSingletons();
    }

    @AfterEach
    void tearDown() {
        for (ProtocolHandler handler : handlersToShutdown) {
            handler.shutdown();
        }
        TestEnvironmentSupport.resetSingletons();
        I18n.setLanguageTagForTests(previousLanguage);
    }

    @Test
    void shouldUseImplicitBleModuleSaveOnlyForSingleBleMqttSection() {
        ModuleConfigProtos.ModuleConfig mqttConfig = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setMqtt(ModuleConfigProtos.ModuleConfig.MQTTConfig.newBuilder().setEnabled(false).build())
                .build();

        assertTrue(ConfigSavePolicy.shouldUseImplicitBleModuleSave(
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

        assertFalse(ConfigSavePolicy.shouldUseImplicitBleModuleSave(
                ConnectionType.TCP, false, false, List.of(), List.of(serialConfig)));
        assertFalse(ConfigSavePolicy.shouldUseImplicitBleModuleSave(
                ConnectionType.BLE, true, false, List.of(), List.of(serialConfig)));
        assertFalse(ConfigSavePolicy.shouldUseImplicitBleModuleSave(
                ConnectionType.BLE, false, false, List.of(deviceConfig), List.of()));
        assertFalse(ConfigSavePolicy.shouldUseImplicitBleModuleSave(
                ConnectionType.BLE, false, false, List.of(), List.of(serialConfig)));
    }

    @Test
    void ownerInfoSaveRequiresReconnect() {
        ConfigProtos.Config deviceConfig = ConfigProtos.Config.newBuilder()
                .setDevice(ConfigProtos.Config.DeviceConfig.newBuilder().build())
                .build();
        ModuleConfigProtos.ModuleConfig serialConfig = ModuleConfigProtos.ModuleConfig.newBuilder()
                .setSerial(ModuleConfigProtos.ModuleConfig.SerialConfig.newBuilder().setEnabled(true).build())
                .build();

        assertTrue(ConfigSavePolicy.requiresReconnect(true, List.of(), List.of()));
        assertTrue(ConfigSavePolicy.requiresReconnect(false, List.of(deviceConfig), List.of()));
        assertTrue(ConfigSavePolicy.requiresReconnect(false, List.of(), List.of(serialConfig)));
        assertFalse(ConfigSavePolicy.requiresReconnect(false, List.of(), List.of()));
    }

    @Test
    void shouldSanitizeCacheDisplayTextWithoutRemovingValidEmoji() {
        assertEquals("Blue Goose 🪿86b8", FormSetting.sanitizeCacheDisplayText("Blue Goose 🪿86b8"));
        assertEquals("i͞oan͢n", FormSetting.sanitizeCacheDisplayText("i͞oan͢n"));
        assertEquals("Бердск ps27", FormSetting.sanitizeCacheDisplayText("Бердск ps27"));
        assertEquals("Röyksopp", FormSetting.sanitizeCacheDisplayText("Röyksopp"));
        assertEquals("AB", FormSetting.sanitizeCacheDisplayText("A\uD83DB"));
        assertEquals("", FormSetting.sanitizeCacheDisplayText(null));
    }

    @Test
    void shouldAddExtraSettleDelayBeforeCommitForAllTransports() {
        long tcpDelay = ConfigSavePolicy.interTaskDelayMs(
                ConnectionType.TCP, 0, 2);
        long bleDelay = ConfigSavePolicy.interTaskDelayMs(
                ConnectionType.BLE, 0, 2);

        assertEquals(1_200L, tcpDelay);
        assertEquals(1_350L, bleDelay);
    }

    @Test
    void tcpConfigSaveWaitsForNaturalRebootDisconnectBeforeFallbackHandoff() {
        long tcpDelay = ConfigSavePolicy.configSaveRebootHandoffDelayMs(
                ConnectionType.TCP);
        long bleDelay = ConfigSavePolicy.configSaveRebootHandoffDelayMs(
                ConnectionType.BLE);
        long tcpPowerDelay = ConfigSavePolicy.devicePowerActionHandoffDelayMs(
                ConnectionType.TCP);

        assertEquals(60_000L, tcpDelay);
        assertEquals(4_000L, bleDelay);
        assertEquals(1_000L, tcpPowerDelay);
    }

    @Test
    void shouldTreatCommitAckTimeoutAsExpectedReboot() {
        CompletableFuture<MeshProtos.Routing.Error> ackFuture = new CompletableFuture<>();
        ackFuture.completeExceptionally(new TimeoutException("commit timed out"));

        assertDoesNotThrow(() -> ConfigSavePolicy.waitForCommitAckOrExpectedReboot(
                ackFuture, "commitEditSettings", log));
    }

    @Test
    void shouldTreatCommitAckDisconnectCleanupAsExpectedReboot() {
        CompletableFuture<MeshProtos.Routing.Error> ackFuture = new CompletableFuture<>();
        ackFuture.completeExceptionally(new IllegalStateException("Packet ACK waiter aborted: DISCONNECTED"));

        assertDoesNotThrow(() -> ConfigSavePolicy.waitForCommitAckOrExpectedReboot(
                ackFuture, "commitEditSettings", log));
    }

    @Test
    void shouldStillFailCommitOnExplicitRoutingError() {
        CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                CompletableFuture.completedFuture(MeshProtos.Routing.Error.BAD_REQUEST);

        assertThrows(IllegalStateException.class, () ->
                ConfigSavePolicy.waitForCommitAckOrExpectedReboot(
                        ackFuture, "commitEditSettings", log));
    }

    @Test
    void shouldNotRequireConfigSaveAckForSerialTransport() {
        CompletableFuture<MeshProtos.Routing.Error> ackFuture = new CompletableFuture<>();

        assertDoesNotThrow(() -> ConfigSavePolicy.waitForTransportRequiredAck(
                ConnectionType.SERIAL, ackFuture, "beginEditSettings", log));
        assertFalse(ackFuture.isDone());
    }

    @Test
    void shouldRequireConfigSaveAckForBleTransport() {
        CompletableFuture<MeshProtos.Routing.Error> ackFuture =
                CompletableFuture.completedFuture(MeshProtos.Routing.Error.BAD_REQUEST);

        assertThrows(IllegalStateException.class, () ->
                ConfigSavePolicy.waitForTransportRequiredAck(
                        ConnectionType.BLE, ackFuture, "beginEditSettings", log));
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
        invoke(manager, "cleanupRuntimeState", entry.getId());
        invoke(manager, "fireChanged");
        waitForFxEvents();

        assertNull(onFxThread(() -> configTree(form).getRoot()));
        assertTrue(onFxThread(() -> saveButton(form).isDisable()));
        assertEquals("Нет подключения к радио", onFxThread(() -> statusLabel(form).getText()));
        assertTrue(onFxThread(() -> originalConfigs(form).isEmpty()));
        assertNull(onFxThread(() -> fullConfigRoot(form)));
    }

    @Test
    void reloadConfigTreeShowsLicensedOwnerSetting() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry entry = new ConnectionEntry("Test radio", "localhost", 4403);
        entry.setConnected(true);
        manager.addEntry(entry);

        DeviceState state = new DeviceState();
        state.setMyNodeNum(0x12345678);
        state.setOwnerInfo(MeshProtos.User.newBuilder()
                .setLongName("CALLSIGN")
                .setShortName("CS")
                .setIsLicensed(true)
                .build());
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

        ConfigTreeItem licensedItem = onFxThread(() ->
                findConfigTreeField(configTree(form).getRoot(), "owner_info", "is_licensed"));

        assertNotNull(licensedItem);
        assertEquals(Boolean.class, licensedItem.getValueType());
        assertEquals(Boolean.TRUE, licensedItem.getValue());
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
        Button confirmButton = onFxThread(() -> findButtonByText(
                panel,
                I18n.t("settings.databaseReset.confirm")));

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

    @Test
    void databaseResetConfirmationUsesEnglishLocalization() {
        I18n.setLanguageTagForTests(I18n.LANGUAGE_EN);

        FormSetting form = onFxThread(FormSetting::new);
        VBox panel = onFxThread(() -> (VBox) invokeReturning(
                form,
                "buildDatabaseResetConfirmationPanel",
                new Class<?>[] { Runnable.class },
                (Runnable) () -> {}));

        Button confirmButton = onFxThread(() -> findButtonByText(panel, "Delete Data"));

        assertNotNull(confirmButton);
    }

    @Test
    void saveConfigShouldUseCapturedStateAfterFormStateClears() {
        DeviceState actionState = new DeviceState();
        actionState.setMyNodeNum(0x12345678);
        ProtocolHandler actionHandler = track(new ProtocolHandler(new FakeConnection()));

        FormSetting form = onFxThread(FormSetting::new);
        TreeItem<ConfigTreeItem> root = ownerInfoRoot("Old long", "NEWL", "New long", "NEWS");

        onFxThread(() -> {
            writeField(form, "state", actionState);
            writeField(form, "handler", actionHandler);
            writeField(form, "fullConfigRoot", root);
            configTree(form).setRoot(root);
            return null;
        });

        onFxThread(() -> {
            invoke(form, "onSaveConfig");
            return null;
        });
        waitForFxEvents();

        assertEquals(1, ownerInfoListeners(actionState).size());
        assertEquals("Запрос session key...", onFxThread(() -> statusLabel(form).getText()));
        assertTrue(FormManager.isConfigSaveNavigationBlocked());

        onFxThread(() -> {
            writeField(form, "state", null);
            writeField(form, "handler", null);
            return null;
        });
        actionState.fireOwnerInfoListeners();
        waitForFxEvents();

        assertTrue(ownerInfoListeners(actionState).isEmpty());
        assertEquals("Отправлено секций: 1", onFxThread(() -> statusLabel(form).getText()));
        assertFalse(onFxThread(() -> saveButton(form).isDisable()));
        assertFalse(FormManager.isConfigSaveNavigationBlocked());
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

    private static ConfigTreeItem findConfigTreeField(TreeItem<ConfigTreeItem> root,
                                                      String configType,
                                                      String fieldName) {
        if (root == null) {
            return null;
        }
        ConfigTreeItem data = root.getValue();
        if (data != null
                && configType.equals(data.getConfigType())
                && fieldName.equals(data.getFieldName())) {
            return data;
        }
        for (TreeItem<ConfigTreeItem> child : root.getChildren()) {
            ConfigTreeItem found = findConfigTreeField(child, configType, fieldName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Runnable> ownerInfoListeners(DeviceState state) {
        return (List<Runnable>) readField(state, "ownerInfoListeners");
    }

    private static TreeItem<ConfigTreeItem> ownerInfoRoot(String originalLongName,
                                                          String originalShortName,
                                                          String updatedLongName,
                                                          String updatedShortName) {
        TreeItem<ConfigTreeItem> root = new TreeItem<>(new ConfigTreeItem("Root", "root", 0));
        TreeItem<ConfigTreeItem> ownerSection = new TreeItem<>(new ConfigTreeItem("Owner", "owner_info", 0));

        ConfigTreeItem longName = new ConfigTreeItem(
                "Long name", "long_name", originalLongName, String.class, null, null, "owner_info", 0);
        longName.setValue(updatedLongName);

        ConfigTreeItem shortName = new ConfigTreeItem(
                "Short name", "short_name", originalShortName, String.class, null, null, "owner_info", 0);
        shortName.setValue(updatedShortName);

        ownerSection.getChildren().add(new TreeItem<>(longName));
        ownerSection.getChildren().add(new TreeItem<>(shortName));
        root.getChildren().add(ownerSection);
        return root;
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

    private static void writeField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to write field " + fieldName, e);
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
