package com.meshtastic.client.components.chat;

import com.meshtastic.client.i18n.I18n;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntUnaryOperator;

/**
 * Coordinates debounced background spelling checks, underlines and context-menu actions.
 * Editor-facing methods run on the JavaFX application thread; dictionary work uses a
 * shared serial worker, and stale results are discarded when the draft changes.
 */
final class ChatSpellingSupport {
    /** Applies spelling corrections through the editor's existing validation and undo handling. */
    @FunctionalInterface
    interface Replace {
        /**
         * Replaces a misspelled range as one editor action.
         *
         * @param start inclusive UTF-16 offset
         * @param end exclusive UTF-16 offset
         * @param text replacement text
         * @return whether the editor accepted and applied the replacement
         */
        boolean replace(int start, int end, String text);
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("chat-spelling").factory());
    private static ChatSpellChecker checker;
    private static boolean unavailable;
    private final EmojiTextField field;
    private final TextFlow flow;
    private final IntUnaryOperator toFlowIndex;
    private final Replace replace;
    private final PauseTransition debounce = new PauseTransition(Duration.millis(400));
    private final List<Path> underlines = new ArrayList<>();
    private final Set<String> ignored = new HashSet<>();
    private List<ChatSpellChecker.Word> mistakes = List.of();
    private long generation;
    private boolean enabled = true;

    /**
     * Connects spelling support to an editor and its visual text flow.
     *
     * @param field editor retaining the original Unicode draft
     * @param flow flow containing text and embedded emoji nodes
     * @param toFlowIndex mapping from draft UTF-16 offsets to visual flow indices
     * @param replace validated, undoable range replacement callback
     */
    ChatSpellingSupport(EmojiTextField field, TextFlow flow, IntUnaryOperator toFlowIndex,
                        Replace replace) {
        this.field = field;
        this.flow = flow;
        this.toFlowIndex = toFlowIndex;
        this.replace = replace;
        debounce.setOnFinished(_ -> check());
        field.sceneProperty().addListener((_, _, _) -> textChanged());
    }

    /**
     * Invalidates pending results and schedules a fresh check for a visible, nonempty draft.
     */
    void textChanged() {
        generation++;
        debounce.stop();
        mistakes = List.of();
        clearUnderlines();
        if (enabled && field.getScene() != null && !field.getText().isBlank()) { debounce.playFromStart(); }
    }

    /**
     * Lazily loads dictionaries on the shared spelling worker.
     *
     * @return checker, or null when dictionary initialization failed
     */
    private static ChatSpellChecker checker() {
        if (checker == null && !unavailable) {
            try { checker = new ChatSpellChecker(); }
            catch (Exception error) {
                unavailable = true;
                LoggerFactory.getLogger(ChatSpellingSupport.class).warn("Cannot load offline spelling dictionaries", error);
            }
        }
        return checker;
    }

    /**
     * Checks a captured draft on the worker and applies results only if it is still current.
     */
    private void check() {
        long expected = generation;
        String draft = field.getText();
        CompletableFuture.supplyAsync(() -> {
            ChatSpellChecker service = checker();
            return service == null ? List.<ChatSpellChecker.Word>of() : service.check(draft);
        }, WORKER).thenAccept(result -> Platform.runLater(() -> {
            if (expected != generation || !draft.equals(field.getText()) || field.getScene() == null) { return; }
            mistakes = result.stream().filter(word -> !ignored.contains(word.text())).toList();
            field.requestLayout();
        })).exceptionally(error -> {
            LoggerFactory.getLogger(ChatSpellingSupport.class).warn("Spelling check failed", error);
            return null;
        });
    }

    /**
     * Adds spelling controls and asynchronously loads alternatives for the selected mistake.
     *
     * @param menu editor context menu to extend
     * @param index UTF-16 draft offset under the context-menu invocation
     */
    void populateMenu(ContextMenu menu, int index) {
        CheckMenuItem toggle = new CheckMenuItem(I18n.t("chat.spelling.enabled"));
        toggle.setSelected(enabled);
        toggle.setOnAction(_ -> { enabled = toggle.isSelected(); textChanged(); });
        menu.getItems().addAll(new SeparatorMenuItem(), toggle);
        ChatSpellChecker.Word word = mistakes.stream()
                .filter(candidate -> index >= candidate.start() && index <= candidate.end()).findFirst().orElse(null);
        if (!enabled || word == null) { return; }
        String draft = field.getText();
        MenuItem pending = new MenuItem(I18n.t("chat.spelling.checking"));
        pending.setDisable(true);
        MenuItem ignore = new MenuItem(I18n.t("chat.spelling.ignore"));
        ignore.setOnAction(_ -> { ignored.add(word.text()); textChanged(); });
        menu.getItems().addAll(0, List.of(pending, ignore, new SeparatorMenuItem()));
        CompletableFuture.supplyAsync(() -> checker().suggestions(word.text()), WORKER)
                .thenAccept(suggestions -> Platform.runLater(() -> {
                    if (!menu.isShowing() || !draft.equals(field.getText()) || !menu.getItems().contains(pending)) { return; }
                    int position = menu.getItems().indexOf(pending);
                    menu.getItems().remove(pending);
                    if (suggestions.isEmpty()) {
                        MenuItem none = new MenuItem(I18n.t("chat.spelling.noSuggestions"));
                        none.setDisable(true);
                        menu.getItems().add(position, none);
                    } else {
                        for (String suggestion : suggestions) {
                            MenuItem item = new MenuItem(suggestion);
                            item.setOnAction(_ -> {
                                if (draft.equals(field.getText())) { replace.replace(word.start(), word.end(), suggestion); }
                                field.requestFocus();
                            });
                            menu.getItems().add(position++, item);
                        }
                    }
                })).exceptionally(_ -> {
                    Platform.runLater(() -> pending.setText(I18n.t("chat.spelling.noSuggestions")));
                    return null;
                });
    }

    /**
     * Rebuilds underlines from the laid-out text flow, including wrapped words.
     *
     * @param x text-flow origin in editor coordinates
     * @param y text-flow origin in editor coordinates
     */
    void layout(double x, double y) {
        clearUnderlines();
        for (ChatSpellChecker.Word word : mistakes) {
            PathElement[] range = flow.getRangeShape(toFlowIndex.applyAsInt(word.start()), toFlowIndex.applyAsInt(word.end()), false);
            // Each wrapped fragment of getRangeShape is a separate closed rectangle.
            double left = Double.POSITIVE_INFINITY, right = 0, bottom = 0;
            for (int i = 0; i <= range.length; i++) {
                PathElement element = i == range.length ? null : range[i];
                if (element == null || element instanceof MoveTo) {
                    if (Double.isFinite(left)) { addUnderline(left, right, bottom, x, y); }
                    left = Double.POSITIVE_INFINITY;
                    right = bottom = 0;
                }
                if (element instanceof MoveTo move) {
                    left = right = move.getX(); bottom = move.getY();
                } else if (element instanceof LineTo line) {
                    left = Math.min(left, line.getX());
                    right = Math.max(right, line.getX());
                    bottom = Math.max(bottom, line.getY());
                }
            }
        }
    }

    /**
     * Draws a wavy underline beneath one visual fragment of a misspelled word.
     *
     * @param left fragment left edge in flow coordinates
     * @param right fragment right edge in flow coordinates
     * @param bottom fragment bottom edge in flow coordinates
     * @param x text-flow origin in editor coordinates
     * @param y text-flow origin in editor coordinates
     */
    private void addUnderline(double left, double right, double bottom, double x, double y) {
        Path path = new Path(new MoveTo(left, bottom));
        for (double pos = left + 2; pos <= right; pos += 2) {
            path.getElements().add(new LineTo(pos, bottom + (((int) ((pos - left) / 2) % 2 == 0) ? 0 : -1.5)));
        }
        path.getStyleClass().add("chat-spelling-underline");
        path.setManaged(false);
        path.setMouseTransparent(true);
        path.setLayoutX(x);
        path.setLayoutY(y);
        underlines.add(path);
        field.getChildren().add(path);
    }

    /**
     * Removes all currently rendered spelling underlines from the editor.
     */
    private void clearUnderlines() {
        field.getChildren().removeAll(underlines);
        underlines.clear();
    }
}
