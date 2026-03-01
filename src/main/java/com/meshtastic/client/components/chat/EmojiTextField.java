package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.components.EmojiTextFlow;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Кастомное однострочное текстовое поле с отображением эмодзи как изображений.
 *
 * <p>Внутренняя модель — обычная строка Unicode (для отправки по Meshtastic).
 * Визуально рендерится как HBox с Text + ImageView узлами.
 * Поддерживает: ввод текста, каретку, выделение, clipboard, навигацию стрелками.
 */
public class EmojiTextField extends StackPane {

    private static final double EMOJI_SIZE = 18;
    private static final double CARET_WIDTH = 1.5;

    private final HBox contentBox;
    private final Rectangle caret;
    private final Timeline blinkTimeline;
    private final Rectangle clipRect;
    private final Text promptNode;

    private final StringProperty text = new SimpleStringProperty("");
    private final ReadOnlyIntegerWrapper caretPosition = new ReadOnlyIntegerWrapper(0);

    private String promptText = "";
    private Consumer<Void> onAction;
    private boolean disabled = false;

    // Выделение
    private int selectionAnchor = -1;
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private final List<Rectangle> selectionRects = new ArrayList<>();

    // Сегменты для маппинга между текстом и визуальными узлами
    private List<VisualSegment> segments = new ArrayList<>();

    // Горизонтальный скроллинг
    private double scrollOffset = 0;

    public EmojiTextField() {
        getStyleClass().add("emoji-text-field");
        setFocusTraversable(true);
        setCursor(Cursor.TEXT);

        contentBox = new HBox(0);
        contentBox.setAlignment(Pos.CENTER_LEFT);
        contentBox.setPadding(new Insets(0, 0, 0, 0));

        promptNode = new Text();
        promptNode.getStyleClass().add("emoji-text-field-prompt");
        promptNode.setManaged(false);

        caret = new Rectangle(CARET_WIDTH, 16);
        caret.getStyleClass().add("emoji-text-field-caret");
        caret.setFill(Color.web("#FFFFFF", 0.9));
        caret.setManaged(false);
        caret.setVisible(false);

        clipRect = new Rectangle();
        clipRect.widthProperty().bind(widthProperty());
        clipRect.heightProperty().bind(heightProperty());
        setClip(clipRect);

        getChildren().addAll(contentBox, promptNode, caret);
        StackPane.setAlignment(contentBox, Pos.CENTER_LEFT);

        // Мигание каретки
        blinkTimeline = new Timeline(
                new KeyFrame(Duration.ZERO, e -> caret.setVisible(true)),
                new KeyFrame(Duration.millis(530), e -> caret.setVisible(false)),
                new KeyFrame(Duration.millis(1060))
        );
        blinkTimeline.setCycleCount(Timeline.INDEFINITE);

        // Фокус
        focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (isFocused) {
                blinkTimeline.playFromStart();
                caret.setVisible(true);
                pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), true);
            } else {
                blinkTimeline.stop();
                caret.setVisible(false);
                clearSelection();
                pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), false);
            }
        });

        // Обработчики ввода
        setOnKeyTyped(this::handleKeyTyped);
        setOnKeyPressed(this::handleKeyPressed);
        setOnMousePressed(this::handleMousePressed);
        setOnMouseDragged(this::handleMouseDragged);

        // Обновление визуала при изменении текста
        text.addListener((obs, oldVal, newVal) -> {
            rebuild();
            updatePromptVisibility();
        });

        // Обновление каретки при её перемещении
        caretPosition.addListener((obs, oldVal, newVal) -> {
            updateCaretVisual();
            resetBlink();
        });

        setPrefHeight(36);
        setMinHeight(36);
        setMaxHeight(36);

        updatePromptVisibility();
    }

    // === Публичный API ===

    public String getText() {
        return text.get();
    }

    public void setText(String value) {
        text.set(value != null ? value : "");
        caretPosition.set(Math.min(caretPosition.get(), text.get().length()));
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
        if (disabled || insertion == null || insertion.isEmpty()) return;
        String t = text.get();
        int idx = Math.min(Math.max(index, 0), t.length());
        text.set(t.substring(0, idx) + insertion + t.substring(idx));
        caretPosition.set(idx + insertion.length());
    }

    public void clear() {
        text.set("");
        caretPosition.set(0);
        clearSelection();
    }

    public void setPromptText(String prompt) {
        this.promptText = prompt != null ? prompt : "";
        promptNode.setText(this.promptText);
        updatePromptVisibility();
    }

    public void setOnAction(Consumer<Void> handler) {
        this.onAction = handler;
    }

    public void setFieldDisabled(boolean value) {
        this.disabled = value;
        super.setDisable(value);
    }

    // === Обработчики ввода ===

    private void handleKeyTyped(KeyEvent e) {
        if (disabled) return;
        String ch = e.getCharacter();
        if (ch == null || ch.isEmpty() || ch.charAt(0) < ' '
                || e.isControlDown() || e.isMetaDown()) {
            return;
        }
        deleteSelection();
        String t = text.get();
        int pos = caretPosition.get();
        text.set(t.substring(0, pos) + ch + t.substring(pos));
        caretPosition.set(pos + ch.length());
        e.consume();
    }

    private void handleKeyPressed(KeyEvent e) {
        if (disabled) return;
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
                }
            }
            case C -> {
                if (shortcutDown) {
                    copySelection();
                    e.consume();
                }
            }
            case X -> {
                if (shortcutDown) {
                    cutSelection();
                    e.consume();
                }
            }
            case V -> {
                if (shortcutDown) {
                    paste();
                    e.consume();
                }
            }
            default -> {}
        }
    }

    private void handleMousePressed(MouseEvent e) {
        if (disabled) return;
        requestFocus();
        int pos = hitTestCaret(e.getX());
        clearSelection();
        caretPosition.set(pos);
        selectionAnchor = pos;
        e.consume();
    }

    private void handleMouseDragged(MouseEvent e) {
        if (disabled || selectionAnchor < 0) return;
        int pos = hitTestCaret(e.getX());
        caretPosition.set(pos);
        int start = Math.min(selectionAnchor, pos);
        int end = Math.max(selectionAnchor, pos);
        if (start != end) {
            setSelection(start, end);
        } else {
            clearSelection();
        }
        e.consume();
    }

    // === Навигация каретки ===

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

    /** Предыдущая граница символа (с учётом суррогатных пар и ZWJ-последовательностей). */
    private int prevCharBoundary(String text, int pos) {
        if (pos <= 0) return 0;
        // Проверяем, не является ли позиция серединой эмодзи-сегмента
        for (VisualSegment seg : segments) {
            if (seg.isEmoji && pos > seg.charStart && pos <= seg.charEnd) {
                return seg.charStart;
            }
        }
        // Стандартный откат: учёт суррогатных пар
        int prev = pos - 1;
        if (prev > 0 && Character.isLowSurrogate(text.charAt(prev))) {
            prev--;
        }
        return prev;
    }

    /** Следующая граница символа. */
    private int nextCharBoundary(String text, int pos) {
        if (pos >= text.length()) return text.length();
        // Проверяем, не является ли позиция внутри эмодзи-сегмента
        for (VisualSegment seg : segments) {
            if (seg.isEmoji && pos >= seg.charStart && pos < seg.charEnd) {
                return seg.charEnd;
            }
        }
        int next = pos + 1;
        if (next < text.length() && Character.isLowSurrogate(text.charAt(next))) {
            next++;
        }
        return next;
    }

    // === Удаление ===

    private void deleteBack() {
        String t = text.get();
        int pos = caretPosition.get();
        if (pos <= 0) return;
        int prev = prevCharBoundary(t, pos);
        text.set(t.substring(0, prev) + t.substring(pos));
        caretPosition.set(prev);
    }

    private void deleteForward() {
        String t = text.get();
        int pos = caretPosition.get();
        if (pos >= t.length()) return;
        int next = nextCharBoundary(t, pos);
        text.set(t.substring(0, pos) + t.substring(next));
    }

    // === Выделение ===

    private boolean hasSelection() {
        return selectionStart >= 0 && selectionEnd > selectionStart;
    }

    private void selectAll() {
        String t = text.get();
        if (t.isEmpty()) return;
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
        if (!hasSelection()) return;
        String t = text.get();
        text.set(t.substring(0, selectionStart) + t.substring(selectionEnd));
        caretPosition.set(selectionStart);
        clearSelection();
    }

    // === Clipboard ===

    private void copySelection() {
        if (!hasSelection()) return;
        String t = text.get();
        String selected = t.substring(selectionStart, selectionEnd);
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
        if (clip == null || clip.isEmpty()) return;
        // Убираем переносы строк
        clip = clip.replace("\n", " ").replace("\r", "");
        deleteSelection();
        String t = text.get();
        int pos = caretPosition.get();
        text.set(t.substring(0, pos) + clip + t.substring(pos));
        caretPosition.set(pos + clip.length());
    }

    // === Визуальное построение ===

    private void rebuild() {
        contentBox.getChildren().clear();
        segments.clear();

        String t = text.get();
        if (t == null || t.isEmpty()) {
            updateCaretVisual();
            return;
        }

        List<EmojiTextFlow.Segment> parsed = EmojiTextFlow.parseSegments(t);
        int charIdx = 0;
        for (EmojiTextFlow.Segment seg : parsed) {
            int segStart = charIdx;
            int segEnd = charIdx + seg.text().length();

            if (seg.isEmoji()) {
                ImageView iv = EmojiImageCache.createImageView(seg.text(), EMOJI_SIZE);
                if (iv != null) {
                    segments.add(new VisualSegment(iv, segStart, segEnd, true));
                    contentBox.getChildren().add(iv);
                } else {
                    Text textNode = createTextNode(seg.text());
                    segments.add(new VisualSegment(textNode, segStart, segEnd, false));
                    contentBox.getChildren().add(textNode);
                }
            } else {
                Text textNode = createTextNode(seg.text());
                segments.add(new VisualSegment(textNode, segStart, segEnd, false));
                contentBox.getChildren().add(textNode);
            }
            charIdx = segEnd;
        }

        // Обновить каретку после перестроения
        contentBox.applyCss();
        contentBox.layout();
        updateCaretVisual();
        ensureCaretVisible();
    }

    private Text createTextNode(String content) {
        Text t = new Text(content);
        t.getStyleClass().add("emoji-text-field-text");
        t.setFont(Font.font("Roboto", 13));
        return t;
    }

    private void updatePromptVisibility() {
        boolean showPrompt = (text.get() == null || text.get().isEmpty())
                && !promptText.isEmpty();
        promptNode.setVisible(showPrompt);
        if (showPrompt) {
            promptNode.setText(promptText);
            promptNode.setFont(Font.font("Roboto", 13));
            // Позиционируем промпт
            promptNode.setLayoutX(8);
            promptNode.setLayoutY(getHeight() / 2 + 4);
        }
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        double h = getHeight();
        double padLeft = 8;

        // Позиционируем contentBox с учётом скролла
        contentBox.setLayoutX(padLeft - scrollOffset);
        contentBox.setLayoutY(0);
        contentBox.setPrefHeight(h);
        contentBox.setMinHeight(h);
        contentBox.setMaxHeight(h);

        // Промпт
        if (promptNode.isVisible()) {
            promptNode.setLayoutX(padLeft);
            promptNode.setLayoutY(h / 2 + 4);
        }

        updateCaretVisual();
        updateSelectionVisual();
    }

    private void updateCaretVisual() {
        if (segments.isEmpty() && (text.get() == null || text.get().isEmpty())) {
            caret.setLayoutX(8);
            caret.setLayoutY((getHeight() - caret.getHeight()) / 2);
            return;
        }

        double x = computeCaretX(caretPosition.get());
        caret.setLayoutX(x - scrollOffset + 8);
        caret.setLayoutY((getHeight() - caret.getHeight()) / 2);
    }

    private double computeCaretX(int pos) {
        if (segments.isEmpty()) return 0;

        for (VisualSegment seg : segments) {
            if (pos >= seg.charStart && pos <= seg.charEnd) {
                Node node = seg.node;
                double nodeX = node.getLayoutX() + contentBox.getLayoutX()
                        - (8 - scrollOffset);

                if (seg.isEmoji) {
                    return pos <= seg.charStart
                            ? nodeX
                            : nodeX + node.getBoundsInLocal().getWidth();
                } else {
                    // Текстовый узел — вычислить X внутри текста
                    Text textNode = (Text) node;
                    int offset = pos - seg.charStart;
                    String sub = textNode.getText().substring(0, offset);
                    Text measure = new Text(sub);
                    measure.setFont(textNode.getFont());
                    return nodeX + measure.getBoundsInLocal().getWidth();
                }
            }
        }

        // После последнего сегмента
        if (!segments.isEmpty()) {
            VisualSegment last = segments.getLast();
            Node node = last.node;
            return node.getLayoutX() + contentBox.getLayoutX()
                    - (8 - scrollOffset) + node.getBoundsInLocal().getWidth();
        }
        return 0;
    }

    private void ensureCaretVisible() {
        double caretX = computeCaretX(caretPosition.get()) + 8 - scrollOffset;
        double viewWidth = getWidth() - 16;

        if (caretX > viewWidth) {
            scrollOffset += (caretX - viewWidth + 10);
            requestLayout();
        } else if (caretX < 0) {
            scrollOffset = Math.max(0, scrollOffset + caretX - 10);
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

    /** Определить позицию каретки по X-координате клика. */
    private int hitTestCaret(double clickX) {
        double adjustedX = clickX - 8 + scrollOffset;

        if (segments.isEmpty()) return 0;

        for (VisualSegment seg : segments) {
            Node node = seg.node;
            double nodeLeft = node.getLayoutX();
            double nodeRight = nodeLeft + node.getBoundsInLocal().getWidth();

            if (adjustedX >= nodeLeft && adjustedX <= nodeRight) {
                if (seg.isEmoji) {
                    double mid = (nodeLeft + nodeRight) / 2;
                    return adjustedX < mid ? seg.charStart : seg.charEnd;
                } else {
                    Text textNode = (Text) node;
                    String content = textNode.getText();
                    // Ищем ближайшую границу символа
                    double bestDist = Double.MAX_VALUE;
                    int bestPos = seg.charStart;
                    for (int i = 0; i <= content.length(); i++) {
                        String sub = content.substring(0, i);
                        Text measure = new Text(sub);
                        measure.setFont(textNode.getFont());
                        double charX = nodeLeft + measure.getBoundsInLocal().getWidth();
                        double dist = Math.abs(charX - adjustedX);
                        if (dist < bestDist) {
                            bestDist = dist;
                            bestPos = seg.charStart + i;
                        }
                    }
                    return bestPos;
                }
            }
        }

        // Клик за пределами текста — конец строки
        return text.get().length();
    }

    // === Выделение — визуал ===

    private void updateSelectionVisual() {
        clearSelectionRects();
        if (!hasSelection()) return;

        for (VisualSegment seg : segments) {
            int overlapStart = Math.max(selectionStart, seg.charStart);
            int overlapEnd = Math.min(selectionEnd, seg.charEnd);
            if (overlapStart >= overlapEnd) continue;

            double x1 = computeCaretX(overlapStart) + 8 - scrollOffset;
            double x2 = computeCaretX(overlapEnd) + 8 - scrollOffset;

            Rectangle rect = new Rectangle(x2 - x1, caret.getHeight());
            rect.setLayoutX(x1);
            rect.setLayoutY((getHeight() - caret.getHeight()) / 2);
            rect.getStyleClass().add("emoji-text-field-selection");
            rect.setFill(Color.web("#3390FF", 0.3));
            rect.setManaged(false);
            rect.setMouseTransparent(true);
            selectionRects.add(rect);
            getChildren().add(getChildren().indexOf(contentBox), rect);
        }
    }

    private void clearSelectionRects() {
        for (Rectangle rect : selectionRects) {
            getChildren().remove(rect);
        }
        selectionRects.clear();
    }

    private int clampCaret(int pos) {
        return Math.max(0, Math.min(pos, text.get().length()));
    }

    /** Визуальный сегмент: связь между Node и диапазоном символов в тексте. */
    private record VisualSegment(Node node, int charStart, int charEnd, boolean isEmoji) {}
}
