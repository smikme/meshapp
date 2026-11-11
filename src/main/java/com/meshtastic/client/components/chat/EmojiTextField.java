package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.themes.TypographyManager;
import com.meshtastic.client.utils.UnicodeTextUtils;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Path;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Custom multi-line text field that renders emoji as images.
 *
 * <p>The internal model is a regular Unicode string, suitable for sending
 * through Meshtastic. Visually, the field is rendered as a {@link TextFlow}
 * composed of {@link Text} and {@link ImageView} nodes. Text wraps by words,
 * the field grows vertically up to {@code MAX_HEIGHT}, and the control supports
 * text input, word and line navigation, selection, clipboard actions, undo/redo,
 * and offline spelling. All edits share the same UTF-8 limit and draft validation.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class EmojiTextField extends StackPane {

    private static final double EMOJI_SIZE = 18;
    private static final double CARET_WIDTH = 1.5;
    private static final double PAD_LEFT = 14;
    private static final double PAD_TOP = 8;
    private static final double PAD_RIGHT = 14;
    private static final double MIN_HEIGHT = 36;
    private static final double MAX_HEIGHT = 120;
    private static final double LINE_HEIGHT = 20;

    private final TextFlow contentFlow;
    private final Rectangle caret;
    private final Timeline blinkTimeline;
    private final Rectangle clipRect;
    private final Text promptNode;

    private final StringProperty text = new SimpleStringProperty("");
    private final ReadOnlyIntegerWrapper caretPosition = new ReadOnlyIntegerWrapper(0);

    private String promptText = "";
    private Consumer<Void> onAction;
    private Predicate<KeyEvent> keyPressedInterceptor;
    private boolean disabled = false;
    private IntSupplier maxBytesSupplier;
    private ToIntFunction<String> byteCounter = value -> value.getBytes(StandardCharsets.UTF_8).length;
    private ContextMenu contextMenu;
    private Predicate<String> editValidator = _ -> true;
    private final Deque<EditState> undoHistory = new ArrayDeque<>();
    private final Deque<EditState> redoHistory = new ArrayDeque<>();
    private boolean applyingEdit;
    private double preferredCaretX = Double.NaN;
    private boolean caretLeading = true;
    private final ChatSpellingSupport spelling;
    /**
     * Snapshot of a draft and its UTF-16 selection offsets.
     *
     * @param text original draft
     * @param caret caret position
     * @param anchor selection anchor, or minus one if absent
     * @param start selection start, or minus one if absent
     * @param end selection end, or minus one if absent
     */
    private record EditState(String text, int caret, int anchor, int start, int end) {}

    // Selection state.
    private int selectionAnchor = -1;
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private final List<Path> selectionRects = new ArrayList<>();

    // Segments map the backing text to visual nodes.
    private List<VisualSegment> segments = new ArrayList<>();

    // Vertical scrolling, implemented through translateY when content overflows.
    private double verticalOffset = 0;

    // Drag gesture tracking for mouse selection.
    private boolean dragging = false;
    private final EventHandler<MouseEvent> sceneDragHandler = this::handleSceneDrag;
    private final EventHandler<MouseEvent> sceneReleaseHandler = this::handleSceneRelease;

    /** Caret position in 2D coordinates. */
    private record CaretPos(double x, double y) {}

    /**
     * Creates an empty emoji editor with keyboard editing, history and offline spelling support.
     */
    public EmojiTextField() {
        getStyleClass().add("emoji-text-field");
        setFocusTraversable(true);
        setCursor(Cursor.TEXT);
        setPickOnBounds(true);

        contentFlow = new TextFlow();
        contentFlow.setManaged(false);
        contentFlow.setMouseTransparent(true);

        promptNode = new Text();
        promptNode.getStyleClass().add("emoji-text-field-prompt");
        promptNode.setManaged(false);
        promptNode.setMouseTransparent(true);

        caret = new Rectangle(CARET_WIDTH, scaledValue(16));
        caret.getStyleClass().add("emoji-text-field-caret");
        // The caret color comes from CSS (.emoji-text-field-caret), so theme
        // changes are applied correctly.
        caret.setManaged(false);
        caret.setVisible(false);
        caret.setMouseTransparent(true);

        clipRect = new Rectangle();
        clipRect.widthProperty().bind(widthProperty());
        clipRect.heightProperty().bind(heightProperty());
        setClip(clipRect);

        getChildren().addAll(contentFlow, promptNode, caret);

        // Caret blinking.
        blinkTimeline = new Timeline(
                new KeyFrame(Duration.ZERO, e -> caret.setVisible(true)),
                new KeyFrame(Duration.millis(530), e -> caret.setVisible(false)),
                new KeyFrame(Duration.millis(1060))
        );
        blinkTimeline.setCycleCount(Timeline.INDEFINITE);

        // Focus handling.
        focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                blinkTimeline.playFromStart();
                caret.setVisible(true);
                pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), true);
            } else {
                blinkTimeline.stop();
                caret.setVisible(false);
            // Keep the selection while the context menu is open.
                if (contextMenu == null || !contextMenu.isShowing()) {
                    clearSelection();
                }
                dragging = false;
                pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), false);
            }
        });

        // Input handlers.
        setOnKeyTyped(this::handleKeyTyped);
        setOnKeyPressed(this::handleKeyPressed);
        setOnMousePressed(this::handleMousePressed);
        buildContextMenu();
        spelling = new ChatSpellingSupport(this, contentFlow, this::toFlowIndex, this::replaceRange);

        // Scene-level drag handling guarantees MOUSE_DRAGGED delivery regardless
        // of the drag target chosen by JavaFX. Installing it on the component is
        // unsafe because consume() in the MOUSE_PRESSED filter may prevent JavaFX
        // from establishing the drag target.
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.removeEventFilter(MouseEvent.MOUSE_DRAGGED, sceneDragHandler);
                oldScene.removeEventFilter(MouseEvent.MOUSE_RELEASED, sceneReleaseHandler);
            }
            if (newScene != null) {
                newScene.addEventFilter(MouseEvent.MOUSE_DRAGGED, sceneDragHandler);
                newScene.addEventFilter(MouseEvent.MOUSE_RELEASED, sceneReleaseHandler);
            }
        });

        // Refresh visuals when text changes.
        text.addListener((obs, oldVal, newVal) -> {
            rebuild();
            updatePromptVisibility();
            spelling.textChanged();
        });

        // Refresh the caret when it moves.
        caretPosition.addListener((obs, oldVal, newVal) -> {
            updateCaretVisual();
            requestLayout();
            resetBlink();
        });

        TypographyManager.chatFontSizeProperty().addListener((obs, oldValue, newValue) ->
                applyTypography());

        setMinHeight(USE_PREF_SIZE);
        setMaxHeight(scaledMaxHeight());

        updatePromptVisibility();
    }

    private void applyTypography() {
        caret.setHeight(scaledValue(16));
        setMaxHeight(scaledMaxHeight());
        rebuild();
        updatePromptVisibility();
        requestLayout();
    }

    private double scaledValue(double baseValue) {
        return TypographyManager.scaleChat(baseValue);
    }

    private double currentFontSize() {
        return TypographyManager.getChatFontSize();
    }

    private double scaledPadLeft() {
        return scaledValue(PAD_LEFT);
    }

    private double scaledPadTop() {
        return scaledValue(PAD_TOP);
    }

    private double scaledPadRight() {
        return scaledValue(PAD_RIGHT);
    }

    private double scaledMinHeight() {
        return scaledValue(MIN_HEIGHT);
    }

    /**
     * Returns the minimum single-line height at the current chat font scale.
     *
     * @return height used to anchor adjacent input controls
     */
    double getSingleLineHeight() {
        return scaledMinHeight();
    }

    private double scaledMaxHeight() {
        return scaledValue(MAX_HEIGHT);
    }

    private double scaledLineHeight() {
        return scaledValue(LINE_HEIGHT);
    }

    // === Public API ===

    /**
     * Returns the original Unicode draft, including emoji sequences.
     *
     * @return non-null draft text
     */
    public String getText() {
        return text.get();
    }

    /**
     * Replaces the draft programmatically and clears user editing history.
     * This setter sanitizes text but bypasses edit validation; owner property listeners
     * may reject the replacement. Use {@link #replaceSelection(String)} for user edits.
     *
     * @param value new draft, or null to clear it
     */
    public void setText(String value) {
        caretLeading = true;
        String sanitized = UnicodeTextUtils.sanitize(value != null ? value : "");
        text.set(sanitized != null ? sanitized : "");
        caretPosition.set(clampCaret(Math.min(caretPosition.get(), text.get().length())));
        if (!applyingEdit) {
            undoHistory.clear();
            redoHistory.clear();
            clearSelection();
        }
    }

    /**
     * Exposes the original draft for observation and programmatic replacement.
     *
     * @return mutable draft property
     */
    public StringProperty textProperty() {
        return text;
    }

    /**
     * Returns the caret offset in the backing Unicode string.
     *
     * @return UTF-16 offset, not an index into the emoji-rendering flow
     */
    public int getCaretPosition() {
        return caretPosition.get();
    }

    /**
     * Exposes caret movement for input-panel integrations.
     *
     * @return read-only UTF-16 caret offset property
     */
    public ReadOnlyIntegerProperty caretPositionProperty() {
        return caretPosition.getReadOnlyProperty();
    }

    /**
     * Moves the caret to a valid code-point boundary and clears the selection.
     *
     * @param pos requested UTF-16 offset, clamped to the draft
     */
    public void positionCaret(int pos) {
        clearSelection();
        caretLeading = true;
        caretPosition.set(clampCaret(pos));
        preferredCaretX = Double.NaN;
    }

    /**
     * Inserts a validated, undoable edit without replacing the selected text.
     * Rejected edits and edits to a disabled field leave the draft unchanged.
     *
     * @param index requested UTF-16 insertion offset
     * @param insertion text to insert; null and empty strings are ignored
     */
    public void insertText(int index, String insertion) {
        if (disabled || insertion == null || insertion.isEmpty()) { return; }
        String safeInsertion = UnicodeTextUtils.sanitize(insertion);
        if (safeInsertion == null || safeInsertion.isEmpty()) { return; }
        int idx = clampCaret(index);
        replaceRange(idx, idx, safeInsertion);
    }

    /**
     * Clears the draft, selection, scrolling and editing history after send or reset.
     */
    public void clear() {
        text.set("");
        caretPosition.set(0);
        clearSelection();
        verticalOffset = 0;
        undoHistory.clear();
        redoHistory.clear();
    }

    /**
     * Sets the placeholder displayed for an empty draft.
     *
     * @param prompt placeholder text, or null to remove it
     */
    public void setPromptText(String prompt) {
        String sanitized = UnicodeTextUtils.sanitize(prompt != null ? prompt : "");
        this.promptText = sanitized != null ? sanitized : "";
        promptNode.setText(this.promptText);
        updatePromptVisibility();
    }

    /**
     * Sets the action invoked when Enter is handled as submission.
     *
     * @param handler submission callback, receiving null; null removes the callback
     */
    public void setOnAction(Consumer<Void> handler) {
        this.onAction = handler;
    }

    /**
     * Sets an external pre-handler for {@code KEY_PRESSED}.
     * If the predicate returns {@code true}, the field skips its internal logic
     * and treats the event as handled.
     *
     * @param interceptor external key handler, or null to use only built-in handling
     */
    public void setKeyPressedInterceptor(Predicate<KeyEvent> interceptor) {
        this.keyPressedInterceptor = interceptor;
    }

    /**
     * Enables or disables user edits together with the JavaFX node.
     *
     * @param value true to reject edits and history actions
     */
    public void setFieldDisabled(boolean value) {
        this.disabled = value;
        super.setDisable(value);
    }

    /**
     * Sets the supplier for the maximum allowed UTF-8 byte length.
     * Applied to typing, clipboard insertion, replacements and history restoration.
     * The configured byte counter determines the transmitted size.
     *
     * @param supplier current byte budget; null disables size validation
     */
    public void setMaxBytesSupplier(IntSupplier supplier) {
        this.maxBytesSupplier = supplier;
    }

    /**
     * Sets how complete candidate drafts are measured, including context-sensitive wire encoding.
     * The default counter uses unmodified UTF-8 bytes.
     *
     * @param counter non-null function returning the transmitted byte count
     * @throws NullPointerException if counter is null
     */
    public void setByteCounter(ToIntFunction<String> counter) {
        this.byteCounter = java.util.Objects.requireNonNull(counter);
    }

    /**
     * Sets additional validation for complete candidate drafts, including history restores.
     * The predicate can protect attachment URLs without modifying the candidate.
     *
     * @param validator acceptance predicate; null removes additional validation
     */
    public void setEditValidator(Predicate<String> validator) {
        editValidator = validator != null ? validator : _ -> true;
    }

    /**
     * Returns the selected portion of the backing Unicode draft.
     *
     * @return selected text, or an empty string when no selection exists
     */
    public String getSelectedText() {
        return hasSelection() ? text.get().substring(selectionStart, selectionEnd) : "";
    }

    /**
     * Replaces the current selection, or inserts at the caret, as one validated edit.
     *
     * @param insertion replacement text; null deletes a selection
     */
    public void replaceSelection(String insertion) {
        replaceRange(hasSelection() ? selectionStart : getCaretPosition(),
                hasSelection() ? selectionEnd : getCaretPosition(), insertion);
    }

    /**
     * Captures the draft and selection for an atomic undo or redo step.
     *
     * @return immutable editor snapshot
     */
    private EditState captureEditState() {
        return new EditState(text.get(), getCaretPosition(), selectionAnchor, selectionStart, selectionEnd);
    }

    /**
     * Checks the current byte budget and additional draft validator.
     * A draft already over budget may shrink even if it still exceeds the limit.
     *
     * @param candidate complete proposed draft
     * @return whether the edit may be applied
     */
    private boolean acceptsEdit(String candidate) {
        return (maxBytesSupplier == null || byteCounter.applyAsInt(candidate) <= maxBytesSupplier.getAsInt()
                || byteCounter.applyAsInt(candidate) < byteCounter.applyAsInt(text.get()))
                && editValidator.test(candidate);
    }

    /**
     * Applies a range replacement as one undoable edit if all current constraints pass.
     * The caret and selection are preserved when validation rejects the edit.
     *
     * @param start inclusive UTF-16 offset
     * @param end exclusive UTF-16 offset, not before start
     * @param insertion replacement text; null deletes the range
     * @return whether the draft was changed
     */
    boolean replaceRange(int start, int end, String insertion) {
        if (disabled) { return false; }
        start = clampCaret(start);
        end = clampCaret(end);
        String safe = UnicodeTextUtils.sanitize(insertion == null ? "" : insertion);
        String candidate = text.get().substring(0, start) + safe + text.get().substring(end);
        if (!acceptsEdit(candidate) || candidate.equals(text.get())) { return false; }
        EditState before = captureEditState();
        applyingEdit = true;
        try {
            text.set(candidate);
            if (!candidate.equals(text.get())) { return false; }
            clearSelection();
            caretLeading = true;
            caretPosition.set(start + safe.length());
            preferredCaretX = Double.NaN;
            undoHistory.push(before);
            if (undoHistory.size() > 100) { undoHistory.removeLast(); }
            redoHistory.clear();
            return true;
        } finally {
            applyingEdit = false;
        }
    }

    /**
     * Restores the previous edit when it satisfies current limits and draft validation.
     */
    public void undo() { restoreHistory(undoHistory, redoHistory); }
    /**
     * Reapplies an undone edit when it satisfies current limits and draft validation.
     */
    public void redo() { restoreHistory(redoHistory, undoHistory); }

    /**
     * Restores a validated snapshot and transfers the current state to the opposite history.
     *
     * @param source history containing the requested state
     * @param target history receiving the current state
     */
    private void restoreHistory(Deque<EditState> source, Deque<EditState> target) {
        if (disabled || source.isEmpty() || !acceptsEdit(source.peek().text())) { return; }
        EditState current = captureEditState();
        EditState previous = source.peek();
        applyingEdit = true;
        try {
            text.set(previous.text());
            if (!previous.text().equals(text.get())) { return; }
            source.pop();
            target.push(current);
            selectionAnchor = previous.anchor();
            selectionStart = previous.start();
            selectionEnd = previous.end();
            caretLeading = true;
            caretPosition.set(previous.caret());
            preferredCaretX = Double.NaN;
            requestLayout();
        } finally {
            applyingEdit = false;
        }
    }

    // === Input Handlers ===

    private void handleKeyTyped(KeyEvent e) {
        if (disabled) { return; }
        String ch = UnicodeTextUtils.sanitize(e.getCharacter());
        if (ch == null || ch.isEmpty() || Character.isISOControl(ch.charAt(0))
                || e.isMetaDown() || (e.isControlDown() && !e.isAltDown())) { return; }
        replaceSelection(ch);
        e.consume();
    }

    private void handleKeyPressed(KeyEvent e) {
        if (disabled) { return; }
        if (keyPressedInterceptor != null && keyPressedInterceptor.test(e)) {
            e.consume();
            return;
        }
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        boolean shortcut = mac ? e.isMetaDown() : e.isControlDown();
        boolean word = mac ? e.isAltDown() : e.isControlDown();
        boolean shift = e.isShiftDown();
        int pos = getCaretPosition();
        switch (e.getCode()) {
            case BACK_SPACE -> {
                if (hasSelection()) { deleteSelection(); }
                else { replaceRange(word ? previousWord(pos) : prevCharBoundary(text.get(), pos), pos, ""); }
            }
            case DELETE -> {
                if (shift && !word && !shortcut) { cutSelection(); }
                else if (hasSelection()) { deleteSelection(); }
                else { replaceRange(pos, word ? nextWordEnd(pos) : nextCharBoundary(text.get(), pos), ""); }
            }
            case INSERT -> {
                if (shift) { paste(); }
                else if (shortcut) { copySelection(); }
            }
            case LEFT, RIGHT -> {
                boolean left = e.getCode() == KeyCode.LEFT;
                if (mac && shortcut) {
                    moveToLineBoundary(!left, shift);
                    break;
                }
                int target;
                if (!shift && hasSelection() && !word) { target = left ? selectionStart : selectionEnd; }
                else if (word) { target = left ? previousWord(pos) : mac ? nextWordEnd(pos) : nextWord(pos); }
                else { target = left ? prevCharBoundary(text.get(), pos) : nextCharBoundary(text.get(), pos); }
                moveTo(target, shift);
            }
            case UP, DOWN -> {
                if (shortcut) { moveTo(e.getCode() == KeyCode.UP ? 0 : text.get().length(), shift); }
                else { moveVertically(e.getCode() == KeyCode.UP ? -1 : 1, shift); }
            }
            case PAGE_UP, PAGE_DOWN -> moveVertically(
                    (e.getCode() == KeyCode.PAGE_UP ? -1 : 1)
                            * Math.max(1, (int) (getHeight() / scaledLineHeight()) - 1), shift);
            case HOME, END -> {
                if (shortcut) { moveTo(e.getCode() == KeyCode.HOME ? 0 : text.get().length(), shift); }
                else { moveToLineBoundary(e.getCode() == KeyCode.END, shift); }
            }
            case ENTER -> { if (onAction != null) { onAction.accept(null); } }
            case A -> { if (shortcut) { selectAll(); } }
            case C -> { if (shortcut) { copySelection(); } }
            case X -> { if (shortcut) { cutSelection(); } }
            case V -> { if (shortcut) { paste(); } }
            case Z -> { if (shortcut) { if (shift) { redo(); } else { undo(); } } }
            case Y -> { if (shortcut) { redo(); } }
            default -> { if (!isTextProducingKeyPress(e)) { return; } }
        }
        e.consume();
    }

    private boolean isTextProducingKeyPress(KeyEvent e) {
        if (e.isControlDown() || e.isMetaDown()) {
            return false;
        }

        KeyCode code = e.getCode();
        if (code == null || code == KeyCode.UNDEFINED
                || code.isFunctionKey()
                || code.isMediaKey()
                || code.isModifierKey()
                || code.isNavigationKey()
                || code.isArrowKey()) {
            return false;
        }

        if (code.isLetterKey()
                || code.isDigitKey()
                || code.isKeypadKey()
                || code.isWhitespaceKey()) {
            return true;
        }

        String keyText = e.getText();
        return keyText != null
                && !keyText.isEmpty()
                && keyText.codePoints().noneMatch(Character::isISOControl);
    }

    private void handleMousePressed(MouseEvent e) {
        if (disabled) { return; }

            // Close the context menu on any click inside the input field.
        if (contextMenu != null && contextMenu.isShowing()) {
            contextMenu.hide();
        }

        requestFocus();
        caretLeading = true;
        int pos = hitTestCaret(e.getX(), e.getY());

                // Right-clicking inside the selection keeps it for the context menu.
        if (e.isSecondaryButtonDown() && hasSelection()
                && pos >= selectionStart && pos <= selectionEnd) {
            e.consume();
            return;
        }

        if (e.isPrimaryButtonDown() && e.getClickCount() >= 3) {
            selectAll();
            e.consume();
            return;
        }
        if (e.isPrimaryButtonDown() && e.getClickCount() == 2) {
            int start = pos;
            int end = pos;
            while (start > 0 && isWordAt(prevCharBoundary(text.get(), start))) {
                start = prevCharBoundary(text.get(), start);
            }
            while (end < text.get().length() && isWordAt(end)) {
                end = nextCharBoundary(text.get(), end);
            }
            if (start == end) { end = nextCharBoundary(text.get(), start); }
            selectionAnchor = start;
            setSelection(start, end);
            caretPosition.set(end);
            e.consume();
            return;
        }
        if (e.isShiftDown() && e.isPrimaryButtonDown()) {
            moveTo(pos, true);
            dragging = true;
            e.consume();
            return;
        }

        clearSelection();
        caretPosition.set(pos);
        selectionAnchor = pos;
        dragging = e.isPrimaryButtonDown();
        e.consume();
    }

    /** Scene-level drag handler; it reliably receives every {@code MOUSE_DRAGGED}. */
    private void handleSceneDrag(MouseEvent e) {
        if (!dragging || disabled || selectionAnchor < 0) { return; }
        Point2D local = sceneToLocal(e.getSceneX(), e.getSceneY());
        if (local == null) { return; }
        int pos = hitTestCaret(local.getX(), local.getY());
        caretPosition.set(pos);
        int start = Math.min(selectionAnchor, pos);
        int end = Math.max(selectionAnchor, pos);
        if (start != end) {
            setSelection(start, end);
        } else {
        // Visual cleanup only: preserve the anchor so dragging can continue.
        // Do not call clearSelection(), because it resets selectionAnchor to -1
        // and causes all subsequent drag events to be ignored.
            selectionStart = -1;
            selectionEnd = -1;
            clearSelectionRects();
        }
        e.consume();
    }

    /** Resets drag state when the mouse button is released. */
    private void handleSceneRelease(MouseEvent ignored) {
        if (dragging) {
            dragging = false;
        }
    }

    // === Caret Navigation ===

    private void moveTo(int target, boolean extend) {
        caretLeading = true;
        if (extend) {
            if (selectionAnchor < 0) { selectionAnchor = getCaretPosition(); }
            caretPosition.set(clampCaret(target));
            selectionStart = Math.min(selectionAnchor, getCaretPosition());
            selectionEnd = Math.max(selectionAnchor, getCaretPosition());
            updateSelectionVisual();
        } else {
            clearSelection();
            caretPosition.set(clampCaret(target));
        }
        preferredCaretX = Double.NaN;
        requestLayout();
    }

    private boolean isWordAt(int index) {
        if (index < 0 || index >= text.get().length()) { return false; }
        int cp = text.get().codePointAt(index);
        return Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK || cp == '_';
    }

    private int previousWord(int pos) {
        int index = pos;
        while (index > 0 && !isWordAt(prevCharBoundary(text.get(), index))) {
            index = prevCharBoundary(text.get(), index);
        }
        while (index > 0 && isWordAt(prevCharBoundary(text.get(), index))) {
            index = prevCharBoundary(text.get(), index);
        }
        return index;
    }

    private int nextWord(int pos) {
        int index = nextWordEnd(pos);
        while (index < text.get().length() && !isWordAt(index)) {
            index = nextCharBoundary(text.get(), index);
        }
        return index;
    }

    private int nextWordEnd(int pos) {
        int index = pos;
        while (index < text.get().length() && !isWordAt(index)) {
            index = nextCharBoundary(text.get(), index);
        }
        while (index < text.get().length() && isWordAt(index)) {
            index = nextCharBoundary(text.get(), index);
        }
        return index;
    }

    private void moveToLineBoundary(boolean end, boolean extend) {
        layoutContent();
        CaretPos pos = computeCaretPos(getCaretPosition());
        var hit = contentFlow.hitTest(new Point2D(end ? contentFlow.getWidth() : 0,
                pos.y + caret.getHeight() / 2));
        moveTo(fromFlowIndex(hit.getInsertionIndex()), extend);
        caretLeading = hit.isLeading();
    }

    private void moveVertically(int lines, boolean extend) {
        layoutContent();
        CaretPos pos = computeCaretPos(getCaretPosition());
        double desiredX = Double.isNaN(preferredCaretX) ? pos.x : preferredCaretX;
        Bounds shape = caretBounds(getCaretPosition());
        double lineHeight = Math.max(1, shape.getHeight());
        var hit = contentFlow.hitTest(new Point2D(desiredX,
                pos.y + lineHeight / 2 + lines * lineHeight));
        moveTo(fromFlowIndex(hit.getInsertionIndex()), extend);
        caretLeading = hit.isLeading();
        preferredCaretX = desiredX;
    }


    /** Previous character boundary, respecting surrogate pairs and ZWJ sequences. */
    private int prevCharBoundary(String text, int pos) {
        if (pos <= 0) { return 0; }
        // Guard against positions outside the text, which can happen after external trimming.
        if (pos > text.length()) { return text.length(); }
        for (VisualSegment seg : segments) {
            if (seg.isEmoji && pos > seg.charStart && pos <= seg.charEnd) {
                return seg.charStart;
            }
        }
        return UnicodeTextUtils.previousCodePointBoundary(text, pos);
    }

    /** Next character boundary. */
    private int nextCharBoundary(String text, int pos) {
        if (pos >= text.length()) { return text.length(); }
        for (VisualSegment seg : segments) {
            if (seg.isEmoji && pos >= seg.charStart && pos < seg.charEnd) {
                return seg.charEnd;
            }
        }
        return UnicodeTextUtils.nextCodePointBoundary(text, pos);
    }

    // === Selection ===

    private boolean hasSelection() {
        return selectionStart >= 0 && selectionEnd > selectionStart;
    }

    private void selectAll() {
        String t = text.get();
        if (t.isEmpty()) { return; }
        selectionAnchor = 0;
        setSelection(0, t.length());
        caretPosition.set(t.length());
    }

    private void setSelection(int start, int end) {
        selectionStart = start;
        selectionEnd = end;
        updateSelectionVisual();
    }

    private void clearSelection() {
        selectionStart = -1;
        selectionEnd = -1;
        selectionAnchor = -1;
        clearSelectionRects();
    }

    private void deleteSelection() {
        if (hasSelection()) { replaceRange(selectionStart, selectionEnd, ""); }
    }

    // === Clipboard ===

    private void copySelection() {
        if (!hasSelection()) { return; }
        String t = text.get();
        int start = clampCaret(selectionStart);
        int end = clampCaret(selectionEnd);
        String selected = t.substring(start, end);
        ClipboardContent cc = new ClipboardContent();
        cc.putString(selected);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    private void cutSelection() {
        copySelection();
        deleteSelection();
    }

    private void paste() {
        String clip = Clipboard.getSystemClipboard().getString();
        if (clip == null || clip.isEmpty()) { return; }
        clip = UnicodeTextUtils.sanitize(clip.replace("\n", " ").replace("\r", ""));
        if (clip == null || clip.isEmpty()) { return; }

        // Measure the entire candidate: links and commands can change the encoding of surrounding text.
        if (maxBytesSupplier != null) {
            String current = text.get();
            String before = current.substring(0, hasSelection() ? selectionStart : getCaretPosition());
            String after = current.substring(hasSelection() ? selectionEnd : getCaretPosition());
            int budget = Math.max(maxBytesSupplier.getAsInt(), byteCounter.applyAsInt(current) - 1);
            // Every Unicode code point needs at least one byte. Bound work even for a huge clipboard.
            int maxPoints = Math.max(0, budget - before.codePointCount(0, before.length())
                    - after.codePointCount(0, after.length()));
            int end = clip.offsetByCodePoints(0, Math.min(maxPoints, clip.codePointCount(0, clip.length())));
            while (end > 0 && byteCounter.applyAsInt(before + clip.substring(0, end) + after) > budget) {
                end = clip.offsetByCodePoints(end, -1);
            }
            clip = clip.substring(0, end);
            if (clip.isEmpty()) { return; }
        }

        replaceSelection(clip);
    }


    private void buildContextMenu() {
        MenuItem undoItem = new MenuItem(I18n.t("common.undo"));
        undoItem.setOnAction(_ -> undo());
        MenuItem redoItem = new MenuItem(I18n.t("common.redo"));
        redoItem.setOnAction(_ -> redo());
        MenuItem cutItem = new MenuItem(I18n.t("common.cut"));
        cutItem.setOnAction(e -> cutSelection());

        MenuItem copyItem = new MenuItem(I18n.t("common.copy"));
        copyItem.setOnAction(e -> copySelection());

        MenuItem pasteItem = new MenuItem(I18n.t("common.paste"));
        pasteItem.setOnAction(e -> paste());

        MenuItem selectAllItem = new MenuItem(I18n.t("common.selectAll"));
        selectAllItem.setOnAction(e -> selectAll());

        List<MenuItem> baseItems = List.of(undoItem, redoItem, new SeparatorMenuItem(), cutItem, copyItem, pasteItem,
                new SeparatorMenuItem(), selectAllItem);
        contextMenu = new ContextMenu();

        contextMenu.setOnShowing(e -> {
            boolean hasSel = hasSelection();
            boolean hasClip = Clipboard.getSystemClipboard().hasString();
            undoItem.setDisable(disabled || undoHistory.isEmpty() || !acceptsEdit(undoHistory.peek().text()));
            redoItem.setDisable(disabled || redoHistory.isEmpty() || !acceptsEdit(redoHistory.peek().text()));
            cutItem.setDisable(!hasSel);
            copyItem.setDisable(!hasSel);
            pasteItem.setDisable(!hasClip);
            selectAllItem.setDisable(text.get() == null || text.get().isEmpty());
        });

        setOnContextMenuRequested(e -> {
            if (disabled) { return; }
            contextMenu.getItems().setAll(baseItems);
            int index = e.isKeyboardTrigger() ? getCaretPosition() : hitTestCaret(e.getX(), e.getY());
            spelling.populateMenu(contextMenu, index);
            contextMenu.show(this, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    // === Visual Layout ===

    private void rebuild() {
        contentFlow.getChildren().clear();
        segments.clear();

        String t = UnicodeTextUtils.sanitize(text.get());
        if (t == null) {
            t = "";
        }
        if (!t.equals(text.get())) {
            text.set(t);
            return;
        }
        if (t == null || t.isEmpty()) {
            requestLayout();
            updateCaretVisual();
            return;
        }

        List<EmojiTextFlow.Segment> parsed = EmojiTextFlow.parseSegments(t);
        int charIdx = 0;
        for (EmojiTextFlow.Segment seg : parsed) {
            if (seg.isEmoji()) {
                int segStart = charIdx;
                int segEnd = charIdx + seg.text().length();
                ImageView iv = EmojiImageCache.createImageView(seg.text(), scaledValue(EMOJI_SIZE));
                if (iv != null) {
                    segments.add(new VisualSegment(iv, segStart, segEnd, true));
                    contentFlow.getChildren().add(iv);
                } else {
                    Text textNode = createTextNode(seg.text());
                    segments.add(new VisualSegment(textNode, segStart, segEnd, false));
                    contentFlow.getChildren().add(textNode);
                }
                charIdx = segEnd;
            } else {
        // Split text into words so wrapping stays natural.
                charIdx = splitTextIntoWords(seg.text(), charIdx);
            }
        }

        // Invalidate the preferred height even though contentFlow is unmanaged.
        requestLayout();
        layoutContent();
        updateCaretVisual();
    }

    /**
     * Splits a text segment into words while preserving spaces.
     * Each word becomes a separate {@link Text} node in the {@link TextFlow}
     * so wrapping behaves correctly.
     *
     * @return updated {@code charIdx}
     */
    private int splitTextIntoWords(String content, int charIdx) {
        int i = 0;
        while (i < content.length()) {
            int wordStart = i;
            // Put the word and its following spaces into one node.
            while (i < content.length() && content.charAt(i) != ' ') {
                i++;
            }
            // Attach spaces after the word.
            while (i < content.length() && content.charAt(i) == ' ') {
                i++;
            }
            String word = content.substring(wordStart, i);
            Text textNode = createTextNode(word);
            int segStart = charIdx;
            int segEnd = charIdx + word.length();
            segments.add(new VisualSegment(textNode, segStart, segEnd, false));
            contentFlow.getChildren().add(textNode);
            charIdx = segEnd;
        }
        return charIdx;
    }

    private Text createTextNode(String content) {
        Text t = new Text(UnicodeTextUtils.sanitize(content));
        t.getStyleClass().add("emoji-text-field-text");
        t.setFont(Font.font("Roboto", currentFontSize()));
        return t;
    }

    private void updatePromptVisibility() {
        boolean showPrompt = (text.get() == null || text.get().isEmpty())
                && !promptText.isEmpty();
        promptNode.setVisible(showPrompt);
        if (showPrompt) {
            promptNode.setText(promptText);
            promptNode.setFont(Font.font("Roboto", currentFontSize()));
            promptNode.setLayoutX(scaledPadLeft());
            promptNode.setLayoutY(scaledMinHeight() / 2 + scaledValue(4));
        }
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.HORIZONTAL;
    }

    @Override
    protected double computePrefWidth(double height) {
        return scaledPadLeft() + contentFlow.prefWidth(-1) + scaledPadRight();
    }

    @Override
    protected double computePrefHeight(double width) {
        double flowWidth = (width < 0 ? getWidth() : width) - scaledPadLeft() - scaledPadRight();
        if (flowWidth <= 0) { return scaledMinHeight(); }
        // Report the wrapped height before parents allocate space, including emoji images.
        double contentH = Math.max(scaledLineHeight(), contentFlow.prefHeight(flowWidth));
        return Math.max(scaledMinHeight(),
                Math.min(contentH + scaledPadTop() * 2, scaledMaxHeight()));
    }

    private void layoutContent() {
        double flowWidth = getWidth() - scaledPadLeft() - scaledPadRight();
        if (flowWidth <= 0) { return; }
        // Explicitly size the unmanaged TextFlow so caret and selection positions are current.
        contentFlow.resize(flowWidth, contentFlow.prefHeight(flowWidth));
        contentFlow.layout();
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        layoutContent();
        ensureCaretVisible();

        // Position contentFlow with the current vertical scroll offset.
        contentFlow.setLayoutX(scaledPadLeft());
        contentFlow.setLayoutY(scaledPadTop() - verticalOffset);

        // Prompt.
        if (promptNode.isVisible()) {
            promptNode.setLayoutX(scaledPadLeft());
            promptNode.setLayoutY(scaledMinHeight() / 2 + scaledValue(4));
        }

        updateCaretVisual();
        updateSelectionVisual();
        spelling.layout(scaledPadLeft(), scaledPadTop() - verticalOffset);
    }

    // === Caret Positioning (2D) ===

    private void updateCaretVisual() {
        if (segments.isEmpty() && (text.get() == null || text.get().isEmpty())) {
            caret.setLayoutX(scaledPadLeft());
            caret.setLayoutY((Math.min(getHeight(), scaledMinHeight()) - caret.getHeight()) / 2);
            return;
        }

        CaretPos cp = computeCaretPos(caretPosition.get());
        caret.setLayoutX(cp.x + scaledPadLeft());
        caret.setLayoutY(cp.y + scaledPadTop() - verticalOffset);
    }

    private Bounds caretBounds(int pos) {
        int index = toFlowIndex(pos);
        boolean leading = pos != getCaretPosition() || caretLeading || index == 0;
        return new Path(contentFlow.caretShape(leading ? index : index - 1, leading)).getLayoutBounds();
    }

    private CaretPos computeCaretPos(int pos) {
        if (segments.isEmpty()) { return new CaretPos(0, 0); }
        Bounds bounds = caretBounds(pos);
        return new CaretPos(bounds.getMinX(), bounds.getMinY());
    }

    // TextFlow counts each embedded emoji image as one character.
    private int toFlowIndex(int textIndex) {
        int visual = 0;
        for (VisualSegment seg : segments) {
            if (textIndex <= seg.charStart) { return visual; }
            int length = seg.isEmoji ? 1 : seg.charEnd - seg.charStart;
            if (textIndex < seg.charEnd) { return visual + (seg.isEmoji ? 0 : textIndex - seg.charStart); }
            visual += length;
        }
        return visual;
    }

    private int fromFlowIndex(int visualIndex) {
        int visual = 0;
        for (VisualSegment seg : segments) {
            int length = seg.isEmoji ? 1 : seg.charEnd - seg.charStart;
            if (visualIndex < visual + length) {
                return clampCaret(seg.charStart + (seg.isEmoji ? 0 : Math.max(0, visualIndex - visual)));
            }
            visual += length;
        }
        return text.get().length();
    }

    /**
     * Keeps the caret visible by adjusting vertical scroll.
     * Called after layout assigns the field's height and reflows the text.
     */
    private void ensureCaretVisible() {
        CaretPos cp = computeCaretPos(caretPosition.get());
        double caretY = cp.y;
        double viewH = getHeight() - scaledPadTop() * 2;

        if (viewH <= 0) { return; }

        double maxOffset = Math.max(0, contentFlow.getHeight() - viewH);
        verticalOffset = Math.min(verticalOffset, maxOffset);

        // Caret is below the visible area.
        if (caretY - verticalOffset + scaledLineHeight() > viewH) {
            verticalOffset = caretY + scaledLineHeight() - viewH;
        }
        // Caret is above the visible area.
        else if (caretY - verticalOffset < 0) {
            verticalOffset = Math.max(0, caretY);
        }
    }

    private void resetBlink() {
        if (isFocused()) {
            blinkTimeline.playFromStart();
            caret.setVisible(true);
        }
    }

    // === Hit testing (2D) ===

    /** Resolves the caret using JavaFX's shaped text, including wrapped words. */
    private int hitTestCaret(double clickX, double clickY) {
        layoutContent();
        return fromFlowIndex(contentFlow.hitTest(new Point2D(clickX - scaledPadLeft(),
                clickY - scaledPadTop() + verticalOffset)).getInsertionIndex());
    }

    // === Selection Visuals ===

    private void updateSelectionVisual() {
        clearSelectionRects();
        if (!hasSelection()) { return; }
        Path selection = new Path(contentFlow.rangeShape(toFlowIndex(selectionStart), toFlowIndex(selectionEnd)));
        selection.getStyleClass().add("emoji-text-field-selection");
        selection.setStroke(null);
        selection.setManaged(false);
        selection.setMouseTransparent(true);
        selection.setLayoutX(scaledPadLeft());
        selection.setLayoutY(scaledPadTop() - verticalOffset);
        selectionRects.add(selection);
        getChildren().add(getChildren().indexOf(contentFlow), selection);
    }

    private void clearSelectionRects() {
        getChildren().removeAll(selectionRects);
        selectionRects.clear();
    }


    private int clampCaret(int pos) {
        String current = UnicodeTextUtils.sanitize(text.get());
        return UnicodeTextUtils.clampToCodePointBoundary(current != null ? current : "", pos);
    }

    /** Visual segment linking a node to a character range in the backing text. */
    private record VisualSegment(Node node, int charStart, int charEnd, boolean isEmoji) {}
}
