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
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Custom multi-line text field that renders emoji as images.
 *
 * <p>The internal model is a regular Unicode string, suitable for sending
 * through Meshtastic. Visually, the field is rendered as a {@link TextFlow}
 * composed of {@link Text} and {@link ImageView} nodes. Text wraps by words,
 * the field grows vertically up to {@code MAX_HEIGHT}, and the control supports
 * text input, caret movement, selection, clipboard actions, and arrow-key navigation.
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
    private ContextMenu contextMenu;

    // Selection state.
    private int selectionAnchor = -1;
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private final List<Rectangle> selectionRects = new ArrayList<>();

    // Segments map the backing text to visual nodes.
    private List<VisualSegment> segments = new ArrayList<>();

    // Vertical scrolling, implemented through translateY when content overflows.
    private double verticalOffset = 0;
    // Width tracking for height recalculation after resizing.
    private double lastLayoutWidth = -1;

    // Drag gesture tracking for mouse selection.
    private boolean dragging = false;
    private final EventHandler<MouseEvent> sceneDragHandler = this::handleSceneDrag;
    private final EventHandler<MouseEvent> sceneReleaseHandler = this::handleSceneRelease;

    /** Caret position in 2D coordinates. */
    private record CaretPos(double x, double y) {}

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
        });

        // Refresh the caret when it moves.
        caretPosition.addListener((obs, oldVal, newVal) -> {
            updateCaretVisual();
            resetBlink();
        });

        TypographyManager.chatFontSizeProperty().addListener((obs, oldValue, newValue) ->
                applyTypography());

        setMinHeight(scaledMinHeight());
        setPrefHeight(scaledMinHeight());
        setMaxHeight(scaledMaxHeight());

        updatePromptVisibility();
    }

    private void applyTypography() {
        caret.setHeight(scaledValue(16));
        setMinHeight(scaledMinHeight());
        setPrefHeight(Math.max(getPrefHeight(), scaledMinHeight()));
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

    private double scaledMaxHeight() {
        return scaledValue(MAX_HEIGHT);
    }

    private double scaledLineHeight() {
        return scaledValue(LINE_HEIGHT);
    }

    // === Public API ===

    public String getText() {
        return text.get();
    }

    public void setText(String value) {
        String sanitized = UnicodeTextUtils.sanitize(value != null ? value : "");
        text.set(sanitized != null ? sanitized : "");
        caretPosition.set(clampCaret(Math.min(caretPosition.get(), text.get().length())));
    }

    public StringProperty textProperty() {
        return text;
    }

    public int getCaretPosition() {
        return caretPosition.get();
    }

    public ReadOnlyIntegerProperty caretPositionProperty() {
        return caretPosition.getReadOnlyProperty();
    }

    public void positionCaret(int pos) {
        caretPosition.set(clampCaret(pos));
    }

    public void insertText(int index, String insertion) {
        if (disabled || insertion == null || insertion.isEmpty()) { return; }
        String safeInsertion = UnicodeTextUtils.sanitize(insertion);
        if (safeInsertion == null || safeInsertion.isEmpty()) { return; }
        String t = text.get();
        int idx = clampCaret(index);
        text.set(t.substring(0, idx) + safeInsertion + t.substring(idx));
        caretPosition.set(idx + safeInsertion.length());
    }

    public void clear() {
        text.set("");
        caretPosition.set(0);
        clearSelection();
        verticalOffset = 0;
    }

    public void setPromptText(String prompt) {
        String sanitized = UnicodeTextUtils.sanitize(prompt != null ? prompt : "");
        this.promptText = sanitized != null ? sanitized : "";
        promptNode.setText(this.promptText);
        updatePromptVisibility();
    }

    public void setOnAction(Consumer<Void> handler) {
        this.onAction = handler;
    }

    /**
     * Sets an external pre-handler for {@code KEY_PRESSED}.
     * If the predicate returns {@code true}, the field skips its internal logic
     * and treats the event as handled.
     */
    public void setKeyPressedInterceptor(Predicate<KeyEvent> interceptor) {
        this.keyPressedInterceptor = interceptor;
    }

    public void setFieldDisabled(boolean value) {
        this.disabled = value;
        super.setDisable(value);
    }

    /**
     * Sets the supplier for the maximum allowed UTF-8 byte length.
     * Used by {@link #paste()} to trim clipboard text to the limit.
     */
    public void setMaxBytesSupplier(IntSupplier supplier) {
        this.maxBytesSupplier = supplier;
    }

    // === Input Handlers ===

    private void handleKeyTyped(KeyEvent e) {
        if (disabled) { return; }
        String ch = UnicodeTextUtils.sanitize(e.getCharacter());
        if (ch == null || ch.isEmpty() || Character.isISOControl(ch.charAt(0))
                || e.isControlDown() || e.isMetaDown()) {
            return;
        }
        deleteSelection();
        String t = text.get();
        int pos = caretPosition.get();
        text.set(t.substring(0, pos) + ch + t.substring(pos));
            // If an external listener (ChatInputBar) rolled the text back, keep the caret in place.
        if (text.get().equals(t)) {
            caretPosition.set(pos);
        } else {
            caretPosition.set(Math.min(pos + ch.length(), text.get().length()));
        }
        e.consume();
    }

    private void handleKeyPressed(KeyEvent e) {
        if (disabled) { return; }
        if (keyPressedInterceptor != null && keyPressedInterceptor.test(e)) {
            e.consume();
            return;
        }
        boolean shift = e.isShiftDown();
        boolean shortcutDown = e.isMetaDown() || e.isControlDown();

        switch (e.getCode()) {
            case BACK_SPACE -> {
                if (hasSelection()) {
                    deleteSelection();
                } else {
                    deleteBack();
                }
                e.consume();
            }
            case DELETE -> {
                if (hasSelection()) {
                    deleteSelection();
                } else {
                    deleteForward();
                }
                e.consume();
            }
            case LEFT -> {
                if (shift) {
                    moveCaretWithSelection(-1);
                } else {
                    if (hasSelection()) {
                        caretPosition.set(selectionStart);
                        clearSelection();
                    } else {
                        moveCaret(-1);
                    }
                }
                e.consume();
            }
            case RIGHT -> {
                if (shift) {
                    moveCaretWithSelection(1);
                } else {
                    if (hasSelection()) {
                        caretPosition.set(selectionEnd);
                        clearSelection();
                    } else {
                        moveCaret(1);
                    }
                }
                e.consume();
            }
            case UP -> {
                if (shift) {
                    moveCaretVerticalWithSelection(-1);
                } else {
                    clearSelection();
                    moveCaretVertically(-1);
                }
                e.consume();
            }
            case DOWN -> {
                if (shift) {
                    moveCaretVerticalWithSelection(1);
                } else {
                    clearSelection();
                    moveCaretVertically(1);
                }
                e.consume();
            }
            case HOME -> {
                if (shift) {
                    extendSelectionTo(0);
                } else {
                    clearSelection();
                    caretPosition.set(0);
                }
                e.consume();
            }
            case END -> {
                if (shift) {
                    extendSelectionTo(text.get().length());
                } else {
                    clearSelection();
                    caretPosition.set(text.get().length());
                }
                e.consume();
            }
            case ENTER -> {
                if (onAction != null) {
                    onAction.accept(null);
                }
                e.consume();
            }
            case A -> {
                if (shortcutDown) {
                    selectAll();
                    e.consume();
                } else if (isTextProducingKeyPress(e)) {
                    e.consume();
                }
            }
            case C -> {
                if (shortcutDown) {
                    copySelection();
                    e.consume();
                } else if (isTextProducingKeyPress(e)) {
                    e.consume();
                }
            }
            case X -> {
                if (shortcutDown) {
                    cutSelection();
                    e.consume();
                } else if (isTextProducingKeyPress(e)) {
                    e.consume();
                }
            }
            case V -> {
                if (shortcutDown) {
                    paste();
                    e.consume();
                } else if (isTextProducingKeyPress(e)) {
                    e.consume();
                }
            }
            default -> {
                if (isTextProducingKeyPress(e)) {
            // Paste is handled in KEY_TYPED. KEY_PRESSED must still be consumed;
            // otherwise macOS/JavaFX 25 plays the invalid-input sound for this
            // custom field, which is not a TextInputControl.
                    e.consume();
                }
            }
        }
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
        int pos = hitTestCaret(e.getX(), e.getY());

                // Right-clicking inside the selection keeps it for the context menu.
        if (e.isSecondaryButtonDown() && hasSelection()
                && pos >= selectionStart && pos <= selectionEnd) {
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

    private void moveCaret(int direction) {
        String t = text.get();
        int pos = caretPosition.get();
        if (direction < 0 && pos > 0) {
            caretPosition.set(prevCharBoundary(t, pos));
        } else if (direction > 0 && pos < t.length()) {
            caretPosition.set(nextCharBoundary(t, pos));
        }
    }

    private void moveCaretWithSelection(int direction) {
        if (selectionAnchor < 0) {
            selectionAnchor = caretPosition.get();
        }
        moveCaret(direction);
        int pos = caretPosition.get();
        int start = Math.min(selectionAnchor, pos);
        int end = Math.max(selectionAnchor, pos);
        if (start != end) {
            setSelection(start, end);
        } else {
            clearSelection();
        }
    }

    /** Moves the caret one visual line up (-1) or down (+1). */
    private void moveCaretVertically(int direction) {
        CaretPos cp = computeCaretPos(caretPosition.get());

        // Use actual line Y positions from layout rather than the LINE_HEIGHT constant.
        List<Double> lineYs = new ArrayList<>();
        for (VisualSegment seg : segments) {
            double y = seg.node.getLayoutY();
            if (lineYs.isEmpty() || Math.abs(lineYs.getLast() - y) > 2) {
                lineYs.add(y);
            }
        }
        if (lineYs.isEmpty()) { return; }

        // Find the caret's current line.
        int currentLine = 0;
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < lineYs.size(); i++) {
            double dist = Math.abs(lineYs.get(i) - cp.y);
            if (dist < minDist) {
                minDist = dist;
                currentLine = i;
            }
        }

        // Target line.
        int targetLine = currentLine + direction;
        if (targetLine < 0 || targetLine >= lineYs.size()) { return; }

        // hitTestCaret expects screen coordinates, so convert them.
        // adjustedY = clickY - PAD_TOP + verticalOffset
        // adjustedY should point to the middle of the target line.
        double nodeH = segments.getFirst().node.getBoundsInLocal().getHeight();
        double screenX = cp.x + scaledPadLeft();
        double screenY = lineYs.get(targetLine) + nodeH / 2 + scaledPadTop() - verticalOffset;
        int newPos = hitTestCaret(screenX, screenY);
        caretPosition.set(newPos);
    }

    private void moveCaretVerticalWithSelection(int direction) {
        if (selectionAnchor < 0) {
            selectionAnchor = caretPosition.get();
        }
        moveCaretVertically(direction);
        int pos = caretPosition.get();
        int start = Math.min(selectionAnchor, pos);
        int end = Math.max(selectionAnchor, pos);
        if (start != end) {
            setSelection(start, end);
        } else {
            clearSelection();
        }
    }

    private void extendSelectionTo(int target) {
        if (selectionAnchor < 0) {
            selectionAnchor = caretPosition.get();
        }
        caretPosition.set(target);
        int start = Math.min(selectionAnchor, target);
        int end = Math.max(selectionAnchor, target);
        if (start != end) {
            setSelection(start, end);
        } else {
            clearSelection();
        }
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

    // === Deletion ===

    private void deleteBack() {
        String t = text.get();
        int pos = caretPosition.get();
        if (pos <= 0) { return; }
        int prev = prevCharBoundary(t, pos);
        text.set(t.substring(0, prev) + t.substring(pos));
        caretPosition.set(prev);
    }

    private void deleteForward() {
        String t = text.get();
        int pos = caretPosition.get();
        if (pos >= t.length()) { return; }
        int next = nextCharBoundary(t, pos);
        text.set(t.substring(0, pos) + t.substring(next));
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
        if (!hasSelection()) { return; }
        String t = text.get();
        int start = clampCaret(selectionStart);
        int end = clampCaret(selectionEnd);
        text.set(t.substring(0, start) + t.substring(end));
        caretPosition.set(start);
        clearSelection();
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

            // Trim clipboard text to the byte limit when one is configured.
        if (maxBytesSupplier != null) {
            int maxBytes = maxBytesSupplier.getAsInt();
            String current = text.get();
            int selectionStart = clampCaret(this.selectionStart);
            int selectionEnd = clampCaret(this.selectionEnd);
            int selBytes = hasSelection()
                    ? current.substring(selectionStart, selectionEnd)
                             .getBytes(StandardCharsets.UTF_8).length
                    : 0;
            int usedBytes = current.getBytes(StandardCharsets.UTF_8).length - selBytes;
            int availBytes = maxBytes - usedBytes;
            if (availBytes <= 0) { return; }
            clip = truncateToUtf8Bytes(clip, availBytes);
            if (clip.isEmpty()) { return; }
        }

        deleteSelection();
        String t = text.get();
        int pos = caretPosition.get();
        text.set(t.substring(0, pos) + clip + t.substring(pos));
            // If an external listener rolled the text back, keep the caret in place.
        if (text.get().equals(t)) {
            caretPosition.set(pos);
        } else {
            caretPosition.set(Math.min(pos + clip.length(), text.get().length()));
        }
    }

    /** Trims a string to fit within the UTF-8 byte limit without splitting characters. */
    private static String truncateToUtf8Bytes(String s, int limit) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) { return s; }
        // Decode back; incomplete multibyte characters become U+FFFD.
        return new String(bytes, 0, limit, StandardCharsets.UTF_8)
                .replace("\uFFFD", "");
    }

    private void buildContextMenu() {
        MenuItem cutItem = new MenuItem(I18n.t("common.cut"));
        cutItem.setOnAction(e -> cutSelection());

        MenuItem copyItem = new MenuItem(I18n.t("common.copy"));
        copyItem.setOnAction(e -> copySelection());

        MenuItem pasteItem = new MenuItem(I18n.t("common.paste"));
        pasteItem.setOnAction(e -> paste());

        MenuItem selectAllItem = new MenuItem(I18n.t("common.selectAll"));
        selectAllItem.setOnAction(e -> selectAll());

        contextMenu = new ContextMenu(cutItem, copyItem, pasteItem,
                new SeparatorMenuItem(), selectAllItem);

        contextMenu.setOnShowing(e -> {
            boolean hasSel = hasSelection();
            boolean hasClip = Clipboard.getSystemClipboard().hasString();
            cutItem.setDisable(!hasSel);
            copyItem.setDisable(!hasSel);
            pasteItem.setDisable(!hasClip);
            selectAllItem.setDisable(text.get() == null || text.get().isEmpty());
        });

        setOnContextMenuRequested(e -> {
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
            updateHeight();
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

        // Recalculate layout and height.
        updateHeight();
        updateCaretVisual();
        ensureCaretVisible();
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

    /**
     * Recalculates the field height from its content.
     * <p>
     * A helper {@link Text} node with {@code wrappingWidth} is used for reliable
     * measurement: it computes multi-line text height without depending on the
     * current layout state of the {@link TextFlow}.
     * <p>
     * {@code setMinHeight(target)} is required so the parent HBox
     * reserves space. {@code setPrefHeight} alone is not enough because HBox may
     * allocate only the minimum height.
     */
    private void updateHeight() {
        double flowWidth = getWidth() - scaledPadLeft() - scaledPadRight();
        if (flowWidth <= 0) { return; }

        String t = text.get();
        double contentH;
        if (t == null || t.isEmpty()) {
            contentH = scaledLineHeight();
        } else {
        // Measure text height with a Text node and wrappingWidth; this is more
        // reliable than reading TextFlow child positions.
            Text helper = new Text(UnicodeTextUtils.sanitize(t));
            helper.setFont(Font.font("Roboto", currentFontSize()));
            helper.setWrappingWidth(flowWidth);
            contentH = helper.getBoundsInLocal().getHeight();
        }

        double target = Math.max(scaledMinHeight(),
                Math.min(contentH + scaledPadTop() * 2, scaledMaxHeight()));
        if (Math.abs(getPrefHeight() - target) > 1) {
            setPrefHeight(target);
            setMinHeight(target);
        }

        // Lay out contentFlow so children such as the caret and selection can be positioned.
        contentFlow.setMinWidth(flowWidth);
        contentFlow.setPrefWidth(flowWidth);
        contentFlow.setMaxWidth(flowWidth);
        contentFlow.resize(flowWidth, Math.max(contentH, scaledMaxHeight()));
        contentFlow.requestLayout();
        contentFlow.layout();
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        double w = getWidth();
        double flowWidth = w - scaledPadLeft() - scaledPadRight();

        if (flowWidth > 0) {
            contentFlow.setMinWidth(flowWidth);
            contentFlow.setPrefWidth(flowWidth);
            contentFlow.setMaxWidth(flowWidth);
        // Explicitly size the unmanaged TextFlow so line wrapping is correct.
            contentFlow.resize(flowWidth, scaledMaxHeight() * 10);
            contentFlow.layout();
        }

        // Position contentFlow with the current vertical scroll offset.
        contentFlow.setLayoutX(scaledPadLeft());
        contentFlow.setLayoutY(scaledPadTop() - verticalOffset);

        // Width changes reflow text, so recalculate height.
        if (Math.abs(w - lastLayoutWidth) > 1) {
            lastLayoutWidth = w;
            updateHeight();
        }

        // Prompt.
        if (promptNode.isVisible()) {
            promptNode.setLayoutX(scaledPadLeft());
            promptNode.setLayoutY(scaledMinHeight() / 2 + scaledValue(4));
        }

        updateCaretVisual();
        updateSelectionVisual();
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

    private CaretPos computeCaretPos(int pos) {
        if (segments.isEmpty()) { return new CaretPos(0, 0); }

        for (VisualSegment seg : segments) {
            if (pos >= seg.charStart && pos <= seg.charEnd) {
                Node node = seg.node;
                double nodeX = node.getLayoutX();
                double nodeY = node.getLayoutY();

                if (seg.isEmoji) {
                    if (pos <= seg.charStart) {
                        return new CaretPos(nodeX, nodeY);
                    } else {
                        return new CaretPos(nodeX + node.getBoundsInLocal().getWidth(), nodeY);
                    }
                } else {
                    Text textNode = (Text) node;
                    int offset = UnicodeTextUtils.clampToCodePointBoundary(
                            textNode.getText(),
                            pos - seg.charStart
                    );
                    String sub = textNode.getText().substring(0, offset);
                    Text measure = new Text(sub);
                    measure.setFont(textNode.getFont());
                    return new CaretPos(nodeX + measure.getBoundsInLocal().getWidth(), nodeY);
                }
            }
        }

        // After the last segment.
        if (!segments.isEmpty()) {
            VisualSegment last = segments.getLast();
            Node node = last.node;
            return new CaretPos(
                    node.getLayoutX() + node.getBoundsInLocal().getWidth(),
                    node.getLayoutY()
            );
        }
        return new CaretPos(0, 0);
    }

    /**
     * Keeps the caret visible by adjusting vertical scroll.
     * <p>
     * Uses {@code getPrefHeight()} (the target height) rather than
     * {@code getHeight()} (the current height), because layout may not have
     * applied the new height after {@code updateHeight()}. Otherwise
     * {@code ensureCaretVisible()} sees the stale height and scrolls upward by mistake.
     */
    private void ensureCaretVisible() {
        CaretPos cp = computeCaretPos(caretPosition.get());
        double caretY = cp.y;
        double viewH = getPrefHeight() - scaledPadTop() * 2;

        if (viewH <= 0) { return; }

        // Caret is below the visible area.
        if (caretY - verticalOffset + scaledLineHeight() > viewH) {
            verticalOffset = caretY + scaledLineHeight() - viewH;
            requestLayout();
        }
        // Caret is above the visible area.
        else if (caretY - verticalOffset < 0) {
            verticalOffset = Math.max(0, caretY);
            requestLayout();
        }
    }

    private void resetBlink() {
        if (isFocused()) {
            blinkTimeline.playFromStart();
            caret.setVisible(true);
        }
        ensureCaretVisible();
    }

    // === Hit testing (2D) ===

    /** Resolves the caret position from click coordinates. */
    private int hitTestCaret(double clickX, double clickY) {
        double adjustedX = clickX - scaledPadLeft();
        double adjustedY = clickY - scaledPadTop() + verticalOffset;

        if (segments.isEmpty()) { return 0; }

        // Find a segment on the same visual line by Y, then by X.
        VisualSegment bestOnLine = null;
        double bestDistOnLine = Double.MAX_VALUE;

        for (VisualSegment seg : segments) {
            Node node = seg.node;
            double nodeY = node.getLayoutY();
            double nodeH = node.getBoundsInLocal().getHeight();
            double nodeX = node.getLayoutX();
            double nodeW = node.getBoundsInLocal().getWidth();

            // Check whether the click landed on this node's line.
            if (adjustedY >= nodeY && adjustedY < nodeY + nodeH) {
                // Direct X hit.
                if (adjustedX >= nodeX && adjustedX <= nodeX + nodeW) {
                    if (seg.isEmoji) {
                        double mid = nodeX + nodeW / 2;
                        return adjustedX < mid ? seg.charStart : seg.charEnd;
                    } else {
                        return findCharInTextNode(seg, adjustedX);
                    }
                }
            // Same line but outside this node; remember the nearest segment.
                double dist = Math.min(Math.abs(adjustedX - nodeX),
                        Math.abs(adjustedX - (nodeX + nodeW)));
                if (dist < bestDistOnLine) {
                    bestDistOnLine = dist;
                    bestOnLine = seg;
                }
            }
        }

            // Found the line without hitting a node exactly; snap to the nearest edge.
        if (bestOnLine != null) {
            double nodeX = bestOnLine.node.getLayoutX();
            return adjustedX <= nodeX ? bestOnLine.charStart : bestOnLine.charEnd;
        }

        // Click above or below all content; find the nearest line.
        double closestLineDist = Double.MAX_VALUE;
        double closestLineY = 0;
        for (VisualSegment seg : segments) {
            double nodeY = seg.node.getLayoutY();
            double dist = Math.abs(adjustedY - nodeY);
            if (dist < closestLineDist) {
                closestLineDist = dist;
                closestLineY = nodeY;
            }
        }

        // Among segments on the nearest line, resolve by X.
        VisualSegment bestNear = null;
        double bestNearDist = Double.MAX_VALUE;
        for (VisualSegment seg : segments) {
            if (Math.abs(seg.node.getLayoutY() - closestLineY) < 1) {
                double nodeX = seg.node.getLayoutX();
                double nodeW = seg.node.getBoundsInLocal().getWidth();
                if (adjustedX >= nodeX && adjustedX <= nodeX + nodeW) {
                    if (seg.isEmoji) {
                        return adjustedX < nodeX + nodeW / 2 ? seg.charStart : seg.charEnd;
                    } else {
                        return findCharInTextNode(seg, adjustedX);
                    }
                }
                double dist = Math.min(Math.abs(adjustedX - nodeX),
                        Math.abs(adjustedX - (nodeX + nodeW)));
                if (dist < bestNearDist) {
                    bestNearDist = dist;
                    bestNear = seg;
                }
            }
        }

        if (bestNear != null) {
            double nodeX = bestNear.node.getLayoutX();
            return adjustedX <= nodeX ? bestNear.charStart : bestNear.charEnd;
        }

        return text.get().length();
    }

    /** Finds the character inside a text node by X coordinate. */
    private int findCharInTextNode(VisualSegment seg, double adjustedX) {
        Text textNode = (Text) seg.node;
        String content = textNode.getText();
        double nodeX = textNode.getLayoutX();
        double bestDist = Double.MAX_VALUE;
        int bestPos = seg.charStart;
        for (int i = 0; ; i = UnicodeTextUtils.nextCodePointBoundary(content, i)) {
            String sub = content.substring(0, i);
            Text measure = new Text(sub);
            measure.setFont(textNode.getFont());
            double charX = nodeX + measure.getBoundsInLocal().getWidth();
            double dist = Math.abs(charX - adjustedX);
            if (dist < bestDist) {
                bestDist = dist;
                bestPos = seg.charStart + i;
            }
            if (i >= content.length()) {
                break;
            }
        }
        return bestPos;
    }

    // === Selection Visuals ===

    private void updateSelectionVisual() {
        clearSelectionRects();
        if (!hasSelection()) { return; }

        int insertIdx = getChildren().indexOf(contentFlow);

        for (VisualSegment seg : segments) {
            int overlapStart = Math.max(selectionStart, seg.charStart);
            int overlapEnd = Math.min(selectionEnd, seg.charEnd);
            if (overlapStart >= overlapEnd) { continue; }

            // Read coordinates directly from the segment node. computeCaretPos()
            // can return the previous segment's position at segment boundaries.
            Node node = seg.node;
            double nodeX = node.getLayoutX();
            double nodeY = node.getLayoutY();
            double x1;
            double x2;

            if (seg.isEmoji) {
                double nodeW = node.getBoundsInLocal().getWidth();
                x1 = overlapStart <= seg.charStart ? nodeX : nodeX + nodeW;
                x2 = overlapEnd >= seg.charEnd ? nodeX + nodeW : nodeX;
            } else {
                Text textNode = (Text) node;
                int startOff = UnicodeTextUtils.clampToCodePointBoundary(
                        textNode.getText(),
                        overlapStart - seg.charStart
                );
                Text m1 = new Text(textNode.getText().substring(0, startOff));
                m1.setFont(textNode.getFont());
                x1 = nodeX + m1.getBoundsInLocal().getWidth();

                int endOff = UnicodeTextUtils.clampToCodePointBoundary(
                        textNode.getText(),
                        overlapEnd - seg.charStart
                );
                Text m2 = new Text(textNode.getText().substring(0, endOff));
                m2.setFont(textNode.getFont());
                x2 = nodeX + m2.getBoundsInLocal().getWidth();
            }

            double w = Math.max(1, x2 - x1);
            Rectangle rect = new Rectangle(w, caret.getHeight());
            rect.setLayoutX(x1 + scaledPadLeft());
            rect.setLayoutY(nodeY + scaledPadTop() - verticalOffset);
            rect.getStyleClass().add("emoji-text-field-selection");
        // The selection color comes from CSS (.emoji-text-field-selection).
            rect.setManaged(false);
            rect.setMouseTransparent(true);
            selectionRects.add(rect);
            getChildren().add(insertIdx, rect);
            insertIdx++;
        }
    }

    private void clearSelectionRects() {
        for (Rectangle rect : selectionRects) {
            getChildren().remove(rect);
        }
        selectionRects.clear();
    }

    private int clampCaret(int pos) {
        String current = UnicodeTextUtils.sanitize(text.get());
        return UnicodeTextUtils.clampToCodePointBoundary(current != null ? current : "", pos);
    }

    /** Visual segment linking a node to a character range in the backing text. */
    private record VisualSegment(Node node, int charStart, int charEnd, boolean isEmoji) {}
}
