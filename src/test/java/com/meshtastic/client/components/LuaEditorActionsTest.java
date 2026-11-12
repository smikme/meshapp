package com.meshtastic.client.components;

import com.meshtastic.client.TestEnvironmentSupport;
import com.meshtastic.client.i18n.I18n;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Lua editor clipboard commands, undo/redo, and context-menu behavior on
 * the JavaFX Application Thread, including read-only and autocomplete interactions.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class LuaEditorActionsTest {

    @BeforeAll
    static void startJavaFx() {
        TestEnvironmentSupport.ensureJavaFxStarted();
    }

    @Test
    void shortcutsCopyCutPasteAndSelectAllWithoutDuplicatingEdits() throws Exception {
        onFxThread(() -> {
            CodeArea area = editor("local привет = '😀'\nreturn привет");
            try {
                shortcut(area, KeyCode.A, false);
                assertEquals(area.getText(), area.getSelectedText());
                shortcut(area, KeyCode.C, false);
                String original = area.getText();
                assertEquals(original, Clipboard.getSystemClipboard().getString());

                shortcut(area, KeyCode.X, false);
                assertEquals("", area.getText());
                assertEquals(original, Clipboard.getSystemClipboard().getString());
                shortcut(area, KeyCode.V, false);
                assertEquals(original, area.getText());

                area.selectRange(0, 5);
                clipboard("global");
                shortcut(area, KeyCode.V, false);
                assertEquals(original.replace("local", "global"), area.getText());
            } finally {
                area.dispose();
            }
        });
    }

    @Test
    void insertDeleteAliasesUseTheSameClipboardCommands() throws Exception {
        onFxThread(() -> {
            CodeArea area = editor("one two");
            try {
                area.selectRange(0, 3);
                key(area, KeyCode.INSERT, false, true, false);
                assertEquals("one", Clipboard.getSystemClipboard().getString());
                key(area, KeyCode.DELETE, true, false, false);
                assertEquals(" two", area.getText());
                key(area, KeyCode.INSERT, true, false, false);
                assertEquals("one two", area.getText());
            } finally {
                area.dispose();
            }
        });
    }

    @Test
    void undoAndBothRedoShortcutsIgnoreSyntaxHighlighting() throws Exception {
        onFxThread(() -> {
            CodeArea area = editor("return 1");
            try {
                area.selectRange(7, 8);
                clipboard("42");
                shortcut(area, KeyCode.V, false);
                area.setStyle(0, 6, Collections.singleton("lua-keyword"));
                shortcut(area, KeyCode.Z, false);
                assertEquals("return 1", area.getText());
                shortcut(area, KeyCode.Z, true);
                assertEquals("return 42", area.getText());
                shortcut(area, KeyCode.Z, false);
                shortcut(area, KeyCode.Y, false);
                assertEquals("return 42", area.getText());
            } finally {
                area.dispose();
            }
        });
    }

    @Test
    void contextMenuPreservesSelectionAndUpdatesCommandAvailability() throws Exception {
        onFxThread(() -> {
            CodeArea area = editor("local x = 1");
            Stage stage = new Stage();
            try {
                stage.setScene(new Scene(new StackPane(area), 400, 200));
                stage.show();
                Clipboard.getSystemClipboard().clear();
                area.selectRange(0, 5);
                showContextMenu(area);
                ContextMenu menu = area.getContextMenu();
                assertTrue(menu.isShowing());
                assertEquals("local", area.getSelectedText());
                assertFalse(item(area, "copy").isDisable());
                assertFalse(item(area, "cut").isDisable());
                assertTrue(item(area, "paste").isDisable());
                assertTrue(item(area, "undo").isDisable());
                assertTrue(item(area, "redo").isDisable());

                item(area, "copy").fire();
                assertEquals("local", Clipboard.getSystemClipboard().getString());
                item(area, "delete").fire();
                assertEquals(" x = 1", area.getText());
                menu.hide();
                showContextMenu(area);
                assertTrue(item(area, "copy").isDisable());
                assertTrue(item(area, "delete").isDisable());
                assertFalse(item(area, "paste").isDisable());
                assertFalse(item(area, "undo").isDisable());
                item(area, "undo").fire();
                assertEquals("local x = 1", area.getText());
            } finally {
                area.getContextMenu().hide();
                stage.hide();
                area.dispose();
            }
        });
    }

    @Test
    void readOnlyEditorAllowsCopyAndSelectionButRejectsEdits() throws Exception {
        onFxThread(() -> {
            CodeArea area = editor("return 1");
            try {
                area.appendText("0");
                area.setEditable(false);
                shortcut(area, KeyCode.A, false);
                shortcut(area, KeyCode.C, false);
                assertEquals("return 10", Clipboard.getSystemClipboard().getString());
                shortcut(area, KeyCode.X, false);
                shortcut(area, KeyCode.V, false);
                shortcut(area, KeyCode.Z, false);
                item(area, "delete").fire();
                assertEquals("return 10", area.getText());
                assertTrue(item(area, "cut").isDisable());
                assertTrue(item(area, "paste").isDisable());
                assertTrue(item(area, "undo").isDisable());
            } finally {
                area.dispose();
            }
        });
    }

    @Test
    void editingClosesCompletionAfterTextListenersRunAndLeavesOtherShortcutsAlone() throws Exception {
        onFxThread(() -> {
            CodeArea area = new CodeArea("return ");
            AtomicInteger completionVisible = new AtomicInteger();
            AtomicInteger findRequests = new AtomicInteger();
            LuaEditorActions.install(area, () -> completionVisible.set(0));
            area.textProperty().addListener((obs, oldValue, newValue) -> completionVisible.set(1));
            area.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
                if (event.isShortcutDown() && event.getCode() == KeyCode.F) {
                    findRequests.incrementAndGet();
                }
            });
            try {
                area.moveTo(area.getLength());
                clipboard("mesh");
                shortcut(area, KeyCode.V, false);
                assertEquals("return mesh", area.getText());
                assertEquals(0, completionVisible.get());
                shortcut(area, KeyCode.F, false);
                assertEquals(1, findRequests.get());
            } finally {
                area.dispose();
            }
        });
    }

    private static CodeArea editor(String text) {
        CodeArea area = new CodeArea(text);
        LuaEditorActions.install(area, () -> {});
        return area;
    }

    private static MenuItem item(CodeArea area, String command) {
        return area.getContextMenu().getItems().stream()
                .filter(item -> I18n.t("common." + command).equals(item.getText()))
                .findFirst().orElseThrow();
    }

    private static void showContextMenu(CodeArea area) {
        Event.fireEvent(area, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                20, 20, 100, 100, false, null));
    }

    private static void clipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static void shortcut(CodeArea area, KeyCode code, boolean shift) {
        boolean mac = System.getProperty("os.name").startsWith("Mac");
        key(area, code, shift, !mac, mac);
    }

    private static void key(CodeArea area, KeyCode code, boolean shift, boolean control, boolean meta) {
        Event.fireEvent(area, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code,
                shift, control, false, meta));
    }

    private static void onFxThread(Runnable action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(action, null);
        Platform.runLater(task);
        task.get(10, TimeUnit.SECONDS);
    }
}
