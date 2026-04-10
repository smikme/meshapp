package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.components.EmojiTextFlow;
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
 * Кастомное многострочное текстовое поле с отображением эмодзи как изображений.
 *
 * <p>Внутренняя модель — обычная строка Unicode (для отправки по Meshtastic).
 * Визуально рендерится как TextFlow с Text + ImageView узлами.
 * Текст переносится по словам, поле растёт вертикально (до MAX_HEIGHT).
 * Поддерживает: ввод текста, каретку, выделение, clipboard, навигацию стрелками.
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

    // Выделение
    private int selectionAnchor = -1;
    private int selectionStart = -1;
    private int selectionEnd = -1;
    private final List<Rectangle> selectionRects = new ArrayList<>();

    // Сегменты для маппинга между текстом и визуальными узлами
    private List<VisualSegment> segments = new ArrayList<>();

    // Вертикальный скролл (translateY при переполнении)
    private double verticalOffset = 0;
    // Отслеживание ширины для пересчёта высоты при изменении размера
    private double lastLayoutWidth = -1;

    // Отслеживание drag-жеста для выделения мышью
    private boolean dragging = false;
    private final EventHandler<MouseEvent> sceneDragHandler = this::handleSceneDrag;
    private final EventHandler<MouseEvent> sceneReleaseHandler = this::handleSceneRelease;

    /** Позиция каретки в 2D */
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

        caret = new Rectangle(CARET_WIDTH, 16);
        caret.getStyleClass().add("emoji-text-field-caret");
        // Цвет каретки задаётся через CSS (.emoji-text-field-caret)
        // для корректной работы при смене темы
        caret.setManaged(false);
        caret.setVisible(false);
        caret.setMouseTransparent(true);

        clipRect = new Rectangle();
        clipRect.widthProperty().bind(widthProperty());
        clipRect.heightProperty().bind(heightProperty());
        setClip(clipRect);

        getChildren().addAll(contentFlow, promptNode, caret);

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
                // Не снимать выделение, если открыто контекстное меню
                if (contextMenu == null || !contextMenu.isShowing()) {
                    clearSelection();
                }
                dragging = false;
                pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), false);
            }
        });

        // Обработчики ввода
        setOnKeyTyped(this::handleKeyTyped);
        setOnKeyPressed(this::handleKeyPressed);
        setOnMousePressed(this::handleMousePressed);
        buildContextMenu();

        // Drag-обработку вешаем на Scene — гарантирует получение MOUSE_DRAGGED
        // независимо от того, как JavaFX определяет drag target.
        // Нельзя вешать на компонент: consume() в MOUSE_PRESSED фильтре
        // может помешать JavaFX установить drag target.
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

        setMinHeight(MIN_HEIGHT);
        setPrefHeight(MIN_HEIGHT);
        setMaxHeight(MAX_HEIGHT);

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
        if (disabled || insertion == null || insertion.isEmpty()) { return; }
        String t = text.get();
        int idx = Math.min(Math.max(index, 0), t.length());
        text.set(t.substring(0, idx) + insertion + t.substring(idx));
        caretPosition.set(idx + insertion.length());
    }

    public void clear() {
        text.set("");
        caretPosition.set(0);
        clearSelection();
        verticalOffset = 0;
    }

    public void setPromptText(String prompt) {
        this.promptText = prompt != null ? prompt : "";
        promptNode.setText(this.promptText);
        updatePromptVisibility();
    }

    public void setOnAction(Consumer<Void> handler) {
        this.onAction = handler;
    }

    /**
     * Устанавливает внешний pre-handler для KEY_PRESSED.
     * Если predicate возвращает {@code true}, внутренняя логика поля
     * не выполняется и событие считается обработанным.
     */
    public void setKeyPressedInterceptor(Predicate<KeyEvent> interceptor) {
        this.keyPressedInterceptor = interceptor;
    }

    public void setFieldDisabled(boolean value) {
        this.disabled = value;
        super.setDisable(value);
    }

    /**
     * Устанавливает поставщик максимального количества байт UTF-8 для текста.
     * Используется в {@link #paste()} для обрезки буфера обмена по лимиту.
     */
    public void setMaxBytesSupplier(IntSupplier supplier) {
        this.maxBytesSupplier = supplier;
    }

    // === Обработчики ввода ===

    private void handleKeyTyped(KeyEvent e) {
        if (disabled) { return; }
        String ch = e.getCharacter();
        if (ch == null || ch.isEmpty() || Character.isISOControl(ch.charAt(0))
                || e.isControlDown() || e.isMetaDown()) {
            return;
        }
        deleteSelection();
        String t = text.get();
        int pos = caretPosition.get();
        text.set(t.substring(0, pos) + ch + t.substring(pos));
        // Если внешний listener (ChatInputBar) откатил текст — не двигать каретку
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
        if (disabled) { return; }

        // Закрыть контекстное меню при любом клике на поле ввода
        if (contextMenu != null && contextMenu.isShowing()) {
            contextMenu.hide();
        }

        requestFocus();
        int pos = hitTestCaret(e.getX(), e.getY());

        // Правый клик внутри выделения — сохранить выделение для контекстного меню
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

    /** Обработчик drag на уровне Scene — гарантированно получает все MOUSE_DRAGGED. */
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
            // Только визуальная очистка — anchor сохраняем для продолжения drag.
            // НЕЛЬЗЯ вызывать clearSelection() — он сбросит selectionAnchor = -1
            // и все последующие drag-события будут проигнорированы.
            selectionStart = -1;
            selectionEnd = -1;
            clearSelectionRects();
        }
        e.consume();
    }

    /** Сброс drag-состояния при отпускании кнопки мыши. */
    private void handleSceneRelease(MouseEvent ignored) {
        if (dragging) {
            dragging = false;
        }
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

    /** Переместить каретку на строку вверх (-1) или вниз (+1). */
    private void moveCaretVertically(int direction) {
        CaretPos cp = computeCaretPos(caretPosition.get());

        // Собираем реальные Y-позиции строк из layout (не из константы LINE_HEIGHT)
        List<Double> lineYs = new ArrayList<>();
        for (VisualSegment seg : segments) {
            double y = seg.node.getLayoutY();
            if (lineYs.isEmpty() || Math.abs(lineYs.getLast() - y) > 2) {
                lineYs.add(y);
            }
        }
        if (lineYs.isEmpty()) { return; }

        // Находим текущую строку каретки
        int currentLine = 0;
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < lineYs.size(); i++) {
            double dist = Math.abs(lineYs.get(i) - cp.y);
            if (dist < minDist) {
                minDist = dist;
                currentLine = i;
            }
        }

        // Целевая строка
        int targetLine = currentLine + direction;
        if (targetLine < 0 || targetLine >= lineYs.size()) { return; }

        // hitTestCaret ожидает экранные координаты, конвертирует:
        // adjustedY = clickY - PAD_TOP + verticalOffset
        // Нам нужно adjustedY = targetLineY + halfNodeH (середина строки)
        double nodeH = segments.getFirst().node.getBoundsInLocal().getHeight();
        double screenX = cp.x + PAD_LEFT;
        double screenY = lineYs.get(targetLine) + nodeH / 2 + PAD_TOP - verticalOffset;
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

    /** Предыдущая граница символа (с учётом суррогатных пар и ZWJ-последовательностей). */
    private int prevCharBoundary(String text, int pos) {
        if (pos <= 0) { return 0; }
        // Защита от позиции за пределами текста (может случиться при внешней обрезке)
        if (pos > text.length()) { return text.length(); }
        for (VisualSegment seg : segments) {
            if (seg.isEmoji && pos > seg.charStart && pos <= seg.charEnd) {
                return seg.charStart;
            }
        }
        int prev = pos - 1;
        if (prev > 0 && prev < text.length() && Character.isLowSurrogate(text.charAt(prev))) {
            prev--;
        }
        return prev;
    }

    /** Следующая граница символа. */
    private int nextCharBoundary(String text, int pos) {
        if (pos >= text.length()) { return text.length(); }
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

    // === Выделение ===

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
        text.set(t.substring(0, selectionStart) + t.substring(selectionEnd));
        caretPosition.set(selectionStart);
        clearSelection();
    }

    // === Clipboard ===

    private void copySelection() {
        if (!hasSelection()) { return; }
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
        if (clip == null || clip.isEmpty()) { return; }
        clip = clip.replace("\n", " ").replace("\r", "");

        // Обрезать буфер по лимиту байт, если задан
        if (maxBytesSupplier != null) {
            int maxBytes = maxBytesSupplier.getAsInt();
            String current = text.get();
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
        // Если внешний listener откатил текст — не двигать каретку
        if (text.get().equals(t)) {
            caretPosition.set(pos);
        } else {
            caretPosition.set(Math.min(pos + clip.length(), text.get().length()));
        }
    }

    /** Обрезает строку так, чтобы она укладывалась в limit байт UTF-8, не ломая символы. */
    private static String truncateToUtf8Bytes(String s, int limit) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= limit) { return s; }
        // Декодируем обратно — обрезанные многобайтовые символы станут U+FFFD
        return new String(bytes, 0, limit, StandardCharsets.UTF_8)
                .replace("\uFFFD", "");
    }

    private void buildContextMenu() {
        MenuItem cutItem = new MenuItem("Вырезать");
        cutItem.setOnAction(e -> cutSelection());

        MenuItem copyItem = new MenuItem("Копировать");
        copyItem.setOnAction(e -> copySelection());

        MenuItem pasteItem = new MenuItem("Вставить");
        pasteItem.setOnAction(e -> paste());

        MenuItem selectAllItem = new MenuItem("Выделить всё");
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

    // === Визуальное построение ===

    private void rebuild() {
        contentFlow.getChildren().clear();
        segments.clear();

        String t = text.get();
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
                ImageView iv = EmojiImageCache.createImageView(seg.text(), EMOJI_SIZE);
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
                // Разбиваем текст по словам для корректного переноса строк
                charIdx = splitTextIntoWords(seg.text(), charIdx);
            }
        }

        // Пересчитать layout и высоту
        updateHeight();
        updateCaretVisual();
        ensureCaretVisible();
    }

    /**
     * Разбивает текстовый сегмент на отдельные слова (с сохранением пробелов),
     * каждое слово — отдельный Text-узел в TextFlow для корректного переноса.
     * @return обновлённый charIdx
     */
    private int splitTextIntoWords(String content, int charIdx) {
        int i = 0;
        while (i < content.length()) {
            int wordStart = i;
            // Собираем слово + последующие пробелы в один узел
            while (i < content.length() && content.charAt(i) != ' ') {
                i++;
            }
            // Присоединяем пробелы после слова
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
            promptNode.setLayoutX(PAD_LEFT);
            promptNode.setLayoutY(MIN_HEIGHT / 2 + 4);
        }
    }

    /**
     * Пересчёт высоты поля по содержимому.
     * <p>
     * Для надёжного измерения используется Text-helper с wrappingWidth —
     * он корректно вычисляет высоту многострочного текста без зависимости
     * от layout-состояния TextFlow.
     * <p>
     * setMinHeight(target) необходим, чтобы родительский HBox выделил место:
     * setPrefHeight одного недостаточно — HBox может выдать только minHeight.
     */
    private void updateHeight() {
        double flowWidth = getWidth() - PAD_LEFT - PAD_RIGHT;
        if (flowWidth <= 0) { return; }

        String t = text.get();
        double contentH;
        if (t == null || t.isEmpty()) {
            contentH = LINE_HEIGHT;
        } else {
            // Измеряем высоту текста через Text с wrappingWidth —
            // надёжнее, чем читать позиции детей TextFlow
            Text helper = new Text(t);
            helper.setFont(Font.font("Roboto", 13));
            helper.setWrappingWidth(flowWidth);
            contentH = helper.getBoundsInLocal().getHeight();
        }

        double target = Math.max(MIN_HEIGHT, Math.min(contentH + PAD_TOP * 2, MAX_HEIGHT));
        if (Math.abs(getPrefHeight() - target) > 1) {
            setPrefHeight(target);
            setMinHeight(target);
        }

        // Layout contentFlow для позиционирования детей (каретка, выделение)
        contentFlow.setMinWidth(flowWidth);
        contentFlow.setPrefWidth(flowWidth);
        contentFlow.setMaxWidth(flowWidth);
        contentFlow.resize(flowWidth, Math.max(contentH, MAX_HEIGHT));
        contentFlow.requestLayout();
        contentFlow.layout();
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        double w = getWidth();
        double flowWidth = w - PAD_LEFT - PAD_RIGHT;

        if (flowWidth > 0) {
            contentFlow.setMinWidth(flowWidth);
            contentFlow.setPrefWidth(flowWidth);
            contentFlow.setMaxWidth(flowWidth);
            // Явно задать размер unmanaged TextFlow для корректного переноса строк
            contentFlow.resize(flowWidth, MAX_HEIGHT * 10);
            contentFlow.layout();
        }

        // Позиционируем contentFlow с учётом вертикального скролла
        contentFlow.setLayoutX(PAD_LEFT);
        contentFlow.setLayoutY(PAD_TOP - verticalOffset);

        // При изменении ширины пересчитать высоту (текст перетекает на другие строки)
        if (Math.abs(w - lastLayoutWidth) > 1) {
            lastLayoutWidth = w;
            updateHeight();
        }

        // Промпт
        if (promptNode.isVisible()) {
            promptNode.setLayoutX(PAD_LEFT);
            promptNode.setLayoutY(MIN_HEIGHT / 2 + 4);
        }

        updateCaretVisual();
        updateSelectionVisual();
    }

    // === Позиционирование каретки (2D) ===

    private void updateCaretVisual() {
        if (segments.isEmpty() && (text.get() == null || text.get().isEmpty())) {
            caret.setLayoutX(PAD_LEFT);
            caret.setLayoutY((Math.min(getHeight(), MIN_HEIGHT) - caret.getHeight()) / 2);
            return;
        }

        CaretPos cp = computeCaretPos(caretPosition.get());
        caret.setLayoutX(cp.x + PAD_LEFT);
        caret.setLayoutY(cp.y + PAD_TOP - verticalOffset);
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
                    int offset = pos - seg.charStart;
                    String sub = textNode.getText().substring(0, offset);
                    Text measure = new Text(sub);
                    measure.setFont(textNode.getFont());
                    return new CaretPos(nodeX + measure.getBoundsInLocal().getWidth(), nodeY);
                }
            }
        }

        // После последнего сегмента
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
     * Обеспечить видимость каретки (вертикальный скролл).
     * <p>
     * Используем getPrefHeight() (целевая высота), а не getHeight() (текущая),
     * потому что layout ещё не применил новую высоту после updateHeight().
     * Иначе ensureCaretVisible() видит старую высоту и ошибочно скроллит вверх.
     */
    private void ensureCaretVisible() {
        CaretPos cp = computeCaretPos(caretPosition.get());
        double caretY = cp.y;
        double viewH = getPrefHeight() - PAD_TOP * 2;

        if (viewH <= 0) { return; }

        // Каретка ниже видимой области
        if (caretY - verticalOffset + LINE_HEIGHT > viewH) {
            verticalOffset = caretY + LINE_HEIGHT - viewH;
            requestLayout();
        }
        // Каретка выше видимой области
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

    /** Определить позицию каретки по координатам клика. */
    private int hitTestCaret(double clickX, double clickY) {
        double adjustedX = clickX - PAD_LEFT;
        double adjustedY = clickY - PAD_TOP + verticalOffset;

        if (segments.isEmpty()) { return 0; }

        // Ищем сегмент на той же строке (по Y), затем по X
        VisualSegment bestOnLine = null;
        double bestDistOnLine = Double.MAX_VALUE;

        for (VisualSegment seg : segments) {
            Node node = seg.node;
            double nodeY = node.getLayoutY();
            double nodeH = node.getBoundsInLocal().getHeight();
            double nodeX = node.getLayoutX();
            double nodeW = node.getBoundsInLocal().getWidth();

            // Проверяем, попал ли клик на строку этого узла
            if (adjustedY >= nodeY && adjustedY < nodeY + nodeH) {
                // Прямое попадание по X
                if (adjustedX >= nodeX && adjustedX <= nodeX + nodeW) {
                    if (seg.isEmoji) {
                        double mid = nodeX + nodeW / 2;
                        return adjustedX < mid ? seg.charStart : seg.charEnd;
                    } else {
                        return findCharInTextNode(seg, adjustedX);
                    }
                }
                // На той же строке, но не на этом узле — запомним ближайший
                double dist = Math.min(Math.abs(adjustedX - nodeX),
                        Math.abs(adjustedX - (nodeX + nodeW)));
                if (dist < bestDistOnLine) {
                    bestDistOnLine = dist;
                    bestOnLine = seg;
                }
            }
        }

        // Нашли строку, но не попали точно в узел — привязка к краю ближайшего
        if (bestOnLine != null) {
            double nodeX = bestOnLine.node.getLayoutX();
            return adjustedX <= nodeX ? bestOnLine.charStart : bestOnLine.charEnd;
        }

        // Клик выше или ниже всего контента
        // Ищем ближайшую строку
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

        // Среди сегментов на ближайшей строке — найти по X
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

    /** Найти символ внутри текстового узла по X-координате. */
    private int findCharInTextNode(VisualSegment seg, double adjustedX) {
        Text textNode = (Text) seg.node;
        String content = textNode.getText();
        double nodeX = textNode.getLayoutX();
        double bestDist = Double.MAX_VALUE;
        int bestPos = seg.charStart;
        for (int i = 0; i <= content.length(); i++) {
            String sub = content.substring(0, i);
            Text measure = new Text(sub);
            measure.setFont(textNode.getFont());
            double charX = nodeX + measure.getBoundsInLocal().getWidth();
            double dist = Math.abs(charX - adjustedX);
            if (dist < bestDist) {
                bestDist = dist;
                bestPos = seg.charStart + i;
            }
        }
        return bestPos;
    }

    // === Выделение — визуал ===

    private void updateSelectionVisual() {
        clearSelectionRects();
        if (!hasSelection()) { return; }

        int insertIdx = getChildren().indexOf(contentFlow);

        for (VisualSegment seg : segments) {
            int overlapStart = Math.max(selectionStart, seg.charStart);
            int overlapEnd = Math.min(selectionEnd, seg.charEnd);
            if (overlapStart >= overlapEnd) { continue; }

            // Координаты напрямую из узла сегмента (не через computeCaretPos,
            // который на границе сегментов может вернуть позицию предыдущего)
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
                int startOff = overlapStart - seg.charStart;
                Text m1 = new Text(textNode.getText().substring(0, startOff));
                m1.setFont(textNode.getFont());
                x1 = nodeX + m1.getBoundsInLocal().getWidth();

                int endOff = overlapEnd - seg.charStart;
                Text m2 = new Text(textNode.getText().substring(0, endOff));
                m2.setFont(textNode.getFont());
                x2 = nodeX + m2.getBoundsInLocal().getWidth();
            }

            double w = Math.max(1, x2 - x1);
            Rectangle rect = new Rectangle(w, caret.getHeight());
            rect.setLayoutX(x1 + PAD_LEFT);
            rect.setLayoutY(nodeY + PAD_TOP - verticalOffset);
            rect.getStyleClass().add("emoji-text-field-selection");
            // Цвет выделения задаётся через CSS (.emoji-text-field-selection)
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
        return Math.max(0, Math.min(pos, text.get().length()));
    }

    /** Визуальный сегмент: связь между Node и диапазоном символов в тексте. */
    private record VisualSegment(Node node, int charStart, int charEnd, boolean isEmoji) {}
}
