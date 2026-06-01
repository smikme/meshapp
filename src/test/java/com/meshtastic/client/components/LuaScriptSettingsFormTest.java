package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.lua.LuaScript;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.ConnectionType;
import com.meshtastic.client.service.ConnectionManager;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class LuaScriptSettingsFormTest {

    @TempDir
    Path tempHome;

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
        TestEnvironmentSupport.resetSingletons();
        I18n.setLanguageTagForTests(previousLanguage);
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

    @Test
    void automationBotDoesNotRequireNodeChoice() {
        onFxThread(() -> {
            LuaScriptSettingsForm form = new LuaScriptSettingsForm(
                    script("!04c5b420", LuaScript.BotType.AUTOMATION_BOT, "@auto"));
            try {
                ComboBox<?> combo = nodeCombo(form);
                assertFalse(combo.isVisible());
                assertFalse(combo.isManaged());

                Object draft = buildDraft(form);
                assertNotNull(draft);
                assertEquals("", draftNodeId(draft));
            } finally {
                form.dispose();
            }
            return null;
        });
    }

    @Test
    void showsGuidAsReadOnlyProperty() {
        String guid = "123e4567-e89b-12d3-a456-426614174000";

        onFxThread(() -> {
            LuaScriptSettingsForm form = new LuaScriptSettingsForm(script(guid, "", LuaScript.BotType.AIR_BOT, ""));
            try {
                TextField field = guidField(form);

                assertTrue(field.isVisible());
                assertTrue(field.isManaged());
                assertFalse(field.isEditable());
                assertEquals(guid, field.getText());
            } finally {
                form.dispose();
            }
            return null;
        });
    }

    @Test
    void showsVersionAuthorAndBuildsDraftWithMultilineDescription() {
        String description = "Назначение:\nДлинное описание\n\nДетали:\nНесколько строк";
        String author = "MeshApp Team";

        onFxThread(() -> {
            LuaScriptSettingsForm form = new LuaScriptSettingsForm(
                    script("123e4567-e89b-12d3-a456-426614174000", "🚀", "",
                            LuaScript.BotType.AUTOMATION_BOT, "@auto", 4L, description, author));
            try {
                assertEquals("4", versionField(form).getText());
                assertEquals(author, authorField(form).getText());
                authorField(form).setText("  Store Author  ");

                TextArea area = descriptionArea(form);
                assertEquals(description, area.getText());
                area.setText(description + "\nФинальная строка");

                Object draft = buildDraft(form);

                assertNotNull(draft);
                assertEquals(description + "\nФинальная строка", draftDescription(draft));
                assertEquals("Store Author", draftAuthor(draft));
            } finally {
                form.dispose();
            }
            return null;
        });
    }


    @Test
    void buildsDraftWithEmojiIcon() {
        onFxThread(() -> {
            LuaScriptSettingsForm form = new LuaScriptSettingsForm(
                    script("123e4567-e89b-12d3-a456-426614174000", "🚀", "",
                            LuaScript.BotType.AUTOMATION_BOT, "@auto"));
            try {
                TextField field = iconField(form);
                assertEquals("🚀", field.getText());

                field.setText("🛰️");
                Object draft = buildDraft(form);

                assertNotNull(draft);
                assertEquals("🛰️", draftIcon(draft));
            } finally {
                form.dispose();
            }
            return null;
        });
    }

    @Test
    void iconFieldRejectsPlainText() {
        onFxThread(() -> {
            LuaScriptSettingsForm form = new LuaScriptSettingsForm(
                    script("123e4567-e89b-12d3-a456-426614174000", "🚀", "",
                            LuaScript.BotType.AUTOMATION_BOT, "@auto"));
            try {
                TextField field = iconField(form);

                field.selectAll();
                field.replaceSelection("bot");
                assertEquals("🚀", field.getText());
                assertEquals("Иконка должна быть одним emoji-символом", statusLabel(form).getText());

                field.selectAll();
                field.replaceSelection("🛰️");
                assertEquals("🛰️", field.getText());
                assertEquals("", statusLabel(form).getText());

                field.selectAll();
                field.replaceSelection("🤖🚀");
                assertEquals("🛰️", field.getText());
            } finally {
                form.dispose();
            }
            return null;
        });
    }

    @Test
    void buildDraftRejectsPlainTextIcon() {
        onFxThread(() -> {
            LuaScriptSettingsForm form = new LuaScriptSettingsForm(
                    script("123e4567-e89b-12d3-a456-426614174000", "🚀", "",
                            LuaScript.BotType.AUTOMATION_BOT, "@auto"));
            try {
                TextField field = iconField(form);
                field.setTextFormatter(null);
                field.setText("bot");

                Object draft = buildDraft(form);

                assertNull(draft);
                assertEquals("Иконка должна быть одним emoji-символом", statusLabel(form).getText());
            } finally {
                form.dispose();
            }
            return null;
        });
    }

    private static ConnectionEntry serialEntry(String name, String portName, String nodeId, boolean connected) {
        ConnectionEntry entry = new ConnectionEntry(name, portName, 115200, ConnectionType.SERIAL);
        entry.setNodeId(nodeId);
        entry.setConnected(connected);
        return entry;
    }

    private static LuaScript script(String nodeId) {
        return script(nodeId, LuaScript.BotType.AIR_BOT, "");
    }

    private static LuaScript script(String nodeId, LuaScript.BotType botType, String automationName) {
        return new LuaScript(1L, "Script", "", true, nodeId, botType,
                automationName, 0L, 0L, 0L, "NEW", null);
    }

    private static LuaScript script(String guid, String nodeId, LuaScript.BotType botType, String automationName) {
        return script(guid, LuaScript.DEFAULT_ICON, nodeId, botType, automationName);
    }

    private static LuaScript script(String guid, String icon, String nodeId,
                                    LuaScript.BotType botType, String automationName) {
        return script(guid, icon, nodeId, botType, automationName, LuaScript.DEFAULT_VERSION, "");
    }

    private static LuaScript script(String guid, String icon, String nodeId,
                                    LuaScript.BotType botType, String automationName,
                                    long version, String description) {
        return script(guid, icon, nodeId, botType, automationName, version, description, "");
    }

    private static LuaScript script(String guid, String icon, String nodeId,
                                    LuaScript.BotType botType, String automationName,
                                    long version, String description, String author) {
        return new LuaScript(1L, guid, icon, "Script", "", version, description, author, true, nodeId, botType,
                automationName, 0L, 0L, 0L, "NEW", null);
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

    private static TextField guidField(LuaScriptSettingsForm form) {
        try {
            Field field = LuaScriptSettingsForm.class.getDeclaredField("guidField");
            field.setAccessible(true);
            return (TextField) field.get(form);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read GUID field", e);
        }
    }

    private static TextField versionField(LuaScriptSettingsForm form) {
        try {
            Field field = LuaScriptSettingsForm.class.getDeclaredField("versionField");
            field.setAccessible(true);
            return (TextField) field.get(form);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read version field", e);
        }
    }

    private static TextArea descriptionArea(LuaScriptSettingsForm form) {
        try {
            Field field = LuaScriptSettingsForm.class.getDeclaredField("descriptionArea");
            field.setAccessible(true);
            return (TextArea) field.get(form);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read description area", e);
        }
    }

    private static TextField authorField(LuaScriptSettingsForm form) {
        try {
            Field field = LuaScriptSettingsForm.class.getDeclaredField("authorField");
            field.setAccessible(true);
            return (TextField) field.get(form);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read author field", e);
        }
    }

    private static TextField iconField(LuaScriptSettingsForm form) {
        try {
            Field field = LuaScriptSettingsForm.class.getDeclaredField("iconField");
            field.setAccessible(true);
            return (TextField) field.get(form);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read icon field", e);
        }
    }

    private static Label statusLabel(LuaScriptSettingsForm form) {
        try {
            Field field = LuaScriptSettingsForm.class.getDeclaredField("statusLabel");
            field.setAccessible(true);
            return (Label) field.get(form);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read status label", e);
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

    private static Object buildDraft(LuaScriptSettingsForm form) {
        try {
            Method method = LuaScriptSettingsForm.class.getDeclaredMethod("buildDraft");
            method.setAccessible(true);
            return method.invoke(form);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to build draft", e);
        }
    }

    private static String draftNodeId(Object draft) {
        try {
            Method method = draft.getClass().getDeclaredMethod("nodeId");
            method.setAccessible(true);
            return (String) method.invoke(draft);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read draft node id", e);
        }
    }

    private static String draftIcon(Object draft) {
        try {
            Method method = draft.getClass().getDeclaredMethod("icon");
            method.setAccessible(true);
            return (String) method.invoke(draft);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read draft icon", e);
        }
    }

    private static String draftDescription(Object draft) {
        try {
            Method method = draft.getClass().getDeclaredMethod("description");
            method.setAccessible(true);
            return (String) method.invoke(draft);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read draft description", e);
        }
    }

    private static String draftAuthor(Object draft) {
        try {
            Method method = draft.getClass().getDeclaredMethod("author");
            method.setAccessible(true);
            return (String) method.invoke(draft);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read draft author", e);
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
