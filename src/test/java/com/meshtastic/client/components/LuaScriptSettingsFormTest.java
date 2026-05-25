package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.service.ConnectionManager;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class LuaScriptSettingsFormTest {

    @TempDir
    Path tempHome;

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
        TestEnvironmentSupport.resetSingletons();
    }

    @Test
    void selectsActiveConnectionWhenScriptHasNoSavedNode() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry inactive = serialEntry("TTGO Serial", "/dev/tty-a", "!a0065360", false);
        ConnectionEntry active = serialEntry("COVOX BASE", "/dev/tty-b", "!04c5b420", true);
        manager.addEntry(inactive);
        manager.addEntry(active);
        manager.setSelectedConnectionId(active.getId());

        onFxThread(() -> {
            LuaScriptSettingsForm form = new LuaScriptSettingsForm(script(""));
            try {
                ComboBox<?> combo = nodeCombo(form);

                assertEquals("!04c5b420", selectedNodeId(combo));
                assertEquals("COVOX BASE (!04c5b420) · подключена", combo.getValue().toString());
                assertFalse(combo.getItems().stream().map(Object::toString).anyMatch(String::isBlank));
            } finally {
                form.dispose();
            }
            return null;
        });
    }

    @Test
    void nodeComboKeepsFieldHeightWhenPanelIsStretched() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry active = serialEntry("COVOX BASE", "/dev/tty-b", "!04c5b420", true);
        manager.addEntry(active);
        manager.setSelectedConnectionId(active.getId());

        onFxThread(() -> {
            LuaScriptSettingsForm form = new LuaScriptSettingsForm(script(""));
            try {
                StackPane root = new StackPane(form);
                new Scene(root, 390, 900);
                root.resize(390, 900);
                root.applyCss();
                root.layout();

                ComboBox<?> combo = nodeCombo(form);
                assertEquals(6, combo.getVisibleRowCount());
                assertTrue(combo.getHeight() <= 40.0,
                        "Node combo should remain a single-line field, actual height=" + combo.getHeight());
            } finally {
                form.dispose();
            }
            return null;
        });
    }

    @Test
    void selectsActiveNodeWhenItArrivesAfterFormOpen() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry inactive = serialEntry("TTGO Serial", "/dev/tty-a", "!a0065360", false);
        ConnectionEntry active = serialEntry("COVOX BASE", "/dev/tty-b", "", true);
        manager.addEntry(inactive);
        manager.addEntry(active);
        manager.setSelectedConnectionId(active.getId());

        LuaScriptSettingsForm form = onFxThread(() -> new LuaScriptSettingsForm(script("")));
        try {
            ComboBox<?> combo = onFxThread(() -> nodeCombo(form));
            assertEquals("!a0065360", selectedNodeId(combo));

            active.setNodeId("!04c5b420");
            fireConnectionChanged(manager);
            waitForFxEvents();

            assertEquals("!04c5b420", onFxThread(() -> selectedNodeId(nodeCombo(form))));
        } finally {
            onFxThread(() -> {
                form.dispose();
                return null;
            });
        }
    }

    @Test
    void keepsUserSelectedNodeAfterConnectionRefresh() {
        ConnectionManager manager = ConnectionManager.getInstance();
        ConnectionEntry inactive = serialEntry("TTGO Serial", "/dev/tty-a", "!a0065360", false);
        ConnectionEntry active = serialEntry("COVOX BASE", "/dev/tty-b", "!04c5b420", true);
        manager.addEntry(inactive);
        manager.addEntry(active);
        manager.setSelectedConnectionId(active.getId());

        LuaScriptSettingsForm form = onFxThread(() -> new LuaScriptSettingsForm(script("!04c5b420")));
        try {
            onFxThread(() -> {
                ComboBox<?> combo = nodeCombo(form);
                selectItemContaining(combo, "TTGO Serial");
                assertEquals("!a0065360", selectedNodeId(combo));
                return null;
            });

            fireConnectionChanged(manager);
            waitForFxEvents();

            assertEquals("!a0065360", onFxThread(() -> selectedNodeId(nodeCombo(form))));
        } finally {
            onFxThread(() -> {
                form.dispose();
                return null;
            });
        }
    }

    private static ConnectionEntry serialEntry(String name, String portName, String nodeId, boolean connected) {
        ConnectionEntry entry = new ConnectionEntry(name, portName, 115200, ConnectionType.SERIAL);
        entry.setNodeId(nodeId);
        entry.setConnected(connected);
        return entry;
    }

    private static LuaScript script(String nodeId) {
        return new LuaScript(1L, "Script", "", true, nodeId, LuaScript.BotType.AIR_BOT,
                "", 0L, 0L, 0L, "NEW", null);
    }

    private static ComboBox<?> nodeCombo(LuaScriptSettingsForm form) {
        try {
            Field field = LuaScriptSettingsForm.class.getDeclaredField("nodeCombo");
            field.setAccessible(true);
            return (ComboBox<?>) field.get(form);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read node combo", e);
        }
    }

    private static String selectedNodeId(ComboBox<?> combo) {
        Object value = combo.getValue();
        assertNotNull(value);
        try {
            Method method = value.getClass().getDeclaredMethod("nodeId");
            method.setAccessible(true);
            return (String) method.invoke(value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read selected node id", e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void selectItemContaining(ComboBox<?> combo, String text) {
        Object item = combo.getItems().stream()
                .filter(candidate -> candidate.toString().contains(text))
                .findFirst()
                .orElseThrow();
        ComboBox rawCombo = combo;
        rawCombo.getSelectionModel().select(item);
    }

    private static void fireConnectionChanged(ConnectionManager manager) {
        try {
            Method method = ConnectionManager.class.getDeclaredMethod("fireChanged");
            method.setAccessible(true);
            method.invoke(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to fire connection change", e);
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
}
