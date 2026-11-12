package com.meshtastic.client.components;

import com.meshtastic.client.i18n.I18n;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.fxmisc.richtext.CodeArea;

/**
 * Installs standard editing commands for the Lua editor's context menu and keyboard shortcuts.
 * <p>
 * Menu actions and keyboard commands share availability checks based on the editor's
 * editable state, selection, undo history, and system clipboard. Recognized shortcuts
 * are consumed before RichTextFX handles them to avoid applying an edit twice.
 * Pasted content uses the clipboard's plain text; syntax highlighting is managed by
 * the editor rather than imported from the source application.
 * <p>
 * Installation and command handling must run on the JavaFX Application Thread.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class LuaEditorActions {

    private final CodeArea area;
    private final Runnable hideCompletion;
    private final MenuItem undo;
    private final MenuItem redo;
    private final MenuItem cut;
    private final MenuItem copy;
    private final MenuItem paste;
    private final MenuItem delete;
    private final MenuItem selectAll;

    /**
     * Attaches a localized context menu and editing shortcut filter to the editor.
     * <p>
     * Call once per editor. This replaces the editor's existing context menu and adds
     * an event filter without replacing other keyboard handlers.
     *
     * @param area editor receiving the commands
     * @param hideCompletion callback that dismisses autocomplete when the menu opens
     *                       or after an enabled command finishes
     */
    static void install(CodeArea area, Runnable hideCompletion) {
        new LuaEditorActions(area, hideCompletion);
    }

    /**
     * Creates the shared commands and registers the menu and keyboard handlers.
     *
     * @param area editor receiving the commands
     * @param hideCompletion callback that dismisses the editor's autocomplete overlay
     */
    private LuaEditorActions(CodeArea area, Runnable hideCompletion) {
        this.area = area;
        this.hideCompletion = hideCompletion;
        undo = item("common.undo", shortcut(KeyCode.Z), area::undo);
        redo = item("common.redo", new KeyCodeCombination(KeyCode.Z,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN), area::redo);
        cut = item("common.cut", shortcut(KeyCode.X), area::cut);
        copy = item("common.copy", shortcut(KeyCode.C), area::copy);
        paste = item("common.paste", shortcut(KeyCode.V), this::pastePlainText);
        delete = item("common.delete", new KeyCodeCombination(KeyCode.DELETE),
                () -> area.replaceSelection(""));
        selectAll = item("common.selectAll", shortcut(KeyCode.A), area::selectAll);

        ContextMenu menu = new ContextMenu(undo, redo, new SeparatorMenuItem(),
                cut, copy, paste, delete, new SeparatorMenuItem(), selectAll);
        menu.setOnShowing(_ -> {
            hideCompletion.run();
            refreshAvailability();
        });
        // Use RichTextFX's context-menu property so its mouse handling preserves selection
        // and dismisses the menu when editing resumes.
        area.setContextMenu(menu);
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPressed);
    }

    /**
     * Creates a localized command that rechecks availability before executing.
     * <p>
     * Enabled commands focus the editor, request scrolling to the caret, and dismiss
     * autocomplete after text listeners have processed any changes.
     *
     * @param key localization key for the menu label
     * @param accelerator shortcut displayed beside the menu label
     * @param action operation to execute when the command is enabled
     * @return menu item used by both the context menu and shortcut filter
     */
    private MenuItem item(String key, KeyCombination accelerator, Runnable action) {
        MenuItem item = new MenuItem(I18n.t(key));
        item.setAccelerator(accelerator);
        item.setOnAction(_ -> {
            refreshAvailability();
            if (item.isDisable()) {
                return;
            }
            area.requestFocus();
            action.run();
            area.requestFollowCaret();
            // Text changes can open autocomplete synchronously; close it after the command.
            hideCompletion.run();
        });
        return item;
    }

    /**
     * Replaces the selection with the clipboard's plain text when present.
     * <p>
     * RichTextFX's styled paste casts clipboard data to {@code byte[]}, but JavaFX 27
     * can return a {@link java.nio.ByteBuffer} for that serialized payload. Reading
     * only text avoids the incompatible cast and leaves highlighting to the editor.
     */
    private void pastePlainText() {
        String text = Clipboard.getSystemClipboard().getString();
        if (text != null) {
            area.replaceSelection(text);
        }
    }

    /**
     * Updates command availability from the current editor and clipboard state.
     * Read-only editors retain copy and select-all commands while rejecting edits.
     */
    private void refreshAvailability() {
        boolean disabled = area.isDisabled();
        boolean readOnly = disabled || !area.isEditable();
        boolean hasSelection = area.getSelection().getLength() > 0;
        undo.setDisable(readOnly || !area.isUndoAvailable());
        redo.setDisable(readOnly || !area.isRedoAvailable());
        cut.setDisable(readOnly || !hasSelection);
        copy.setDisable(disabled || !hasSelection);
        paste.setDisable(readOnly || !Clipboard.getSystemClipboard().hasString());
        delete.setDisable(readOnly || !hasSelection);
        selectAll.setDisable(disabled || area.getLength() == 0);
    }

    /**
     * Dispatches recognized shortcuts through their shared menu commands.
     * <p>
     * Recognized events are consumed even when the command is disabled, preventing
     * a fallback RichTextFX handler from executing it. Unrelated keys are left alone.
     *
     * @param event key-pressed event captured by the editor
     */
    private void handleKeyPressed(KeyEvent event) {
        MenuItem command = commandFor(event);
        if (command == null) {
            return;
        }
        command.fire();
        // Prevent the built-in RichTextFX handler from performing the command twice.
        event.consume();
    }

    /**
     * Resolves platform shortcuts, Insert/Delete aliases, and dedicated clipboard keys.
     * <p>
     * The platform shortcut modifier is Ctrl on Windows/Linux and Command on macOS.
     * Alt combinations are left to other handlers, including AltGr text input.
     *
     * @param event key event whose code and modifiers determine the command
     * @return matching command, or {@code null} when this helper does not handle the key
     */
    private MenuItem commandFor(KeyEvent event) {
        if (event.isAltDown()) {
            return null;
        }
        // Handle editing commands during capture, consistently with the IDE's Save/Find shortcuts.
        if (event.isShortcutDown()) {
            if (event.isShiftDown()) {
                return event.getCode() == KeyCode.Z ? redo : null;
            }
            return switch (event.getCode()) {
                case Z -> undo;
                case Y -> redo;
                case X -> cut;
                case C -> copy;
                case V -> paste;
                case A -> selectAll;
                case INSERT -> copy;
                default -> null;
            };
        }
        if (event.isControlDown() && !event.isMetaDown() && !event.isShiftDown()
                && event.getCode() == KeyCode.INSERT) {
            return copy;
        }
        if (event.isControlDown() || event.isMetaDown()) {
            return null;
        }
        if (event.isShiftDown()) {
            return switch (event.getCode()) {
                case INSERT -> paste;
                case DELETE -> cut;
                default -> null;
            };
        }
        return switch (event.getCode()) {
            case CUT -> cut;
            case COPY -> copy;
            case PASTE -> paste;
            default -> null;
        };
    }

    /**
     * Creates an accelerator using the platform's standard shortcut modifier.
     *
     * @param code main key in the combination
     * @return Ctrl or Command combination for the supplied key
     */
    private static KeyCombination shortcut(KeyCode code) {
        return new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN);
    }
}
