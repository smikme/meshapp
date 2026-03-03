package com.meshtastic.client.components;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.List;
import java.util.function.Consumer;

/**
 * Всплывающий пикер эмодзи. Показывается как Popup привязанный к кнопке.
 * Содержит: поиск, категории, сетку эмодзи.
 */
public class EmojiPicker {

    private static final double PICKER_WIDTH = 320;
    private static final double PICKER_HEIGHT = 400;
    private static final int GRID_COLUMNS = 8;
    private static final double CELL_SIZE = 36;

    private final Popup popup;
    private final VBox root;
    private final TextField searchField;
    private final HBox categoryBar;
    private final ScrollPane gridScroll;
    private final FlowPane emojiGrid;
    private final Consumer<String> onEmojiSelected;

    private String activeCategory = null;

    public EmojiPicker(Consumer<String> onEmojiSelected) {
        this.onEmojiSelected = onEmojiSelected;
        this.popup = new Popup();

        // Автоматическое скрытие при потере фокуса / клике вне
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        root = new VBox(6);
        root.setPrefSize(PICKER_WIDTH, PICKER_HEIGHT);
        root.setMaxSize(PICKER_WIDTH, PICKER_HEIGHT);
        root.setPadding(new Insets(8));
        root.getStyleClass().add("emoji-picker");

        // Поиск
        searchField = new TextField();
        searchField.setPromptText("\uD83D\uDD0D Поиск emoji...");
        searchField.getStyleClass().add("emoji-picker-search");
        searchField.textProperty().addListener((obs, old, val) -> onSearchChanged(val));
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
                e.consume();
            }
        });

        // Панель категорий
        categoryBar = new HBox(2);
        categoryBar.setAlignment(Pos.CENTER);
        categoryBar.getStyleClass().add("emoji-picker-categories");

        // Сетка эмодзи
        emojiGrid = new FlowPane(2, 2);
        emojiGrid.setPrefWrapLength((int) (GRID_COLUMNS * (CELL_SIZE + 2)));
        emojiGrid.getStyleClass().add("emoji-picker-grid");

        gridScroll = new ScrollPane(emojiGrid);
        gridScroll.setFitToWidth(true);
        gridScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        gridScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        gridScroll.getStyleClass().add("emoji-picker-scroll");
        VBox.setVgrow(gridScroll, Priority.ALWAYS);

        root.getChildren().addAll(searchField, categoryBar, gridScroll);
        popup.getContent().add(root);
    }

    /** Показать/скрыть пикер относительно указанного узла (кнопки) */
    public void toggle(Node anchor) {
        if (popup.isShowing()) {
            hide();
        } else {
            show(anchor);
        }
    }

    /** Показать пикер над кнопкой (или под, если не влезает) */
    public void show(Node anchor) {
        // Синхронизировать тему: Popup не наследует styleClass с корня сцены
        if (anchor.getScene() != null && anchor.getScene().getRoot() != null) {
            boolean isLight = anchor.getScene().getRoot().getStyleClass().contains("light-theme");
            if (isLight && !root.getStyleClass().contains("light-theme")) {
                root.getStyleClass().add("light-theme");
            } else if (!isLight) {
                root.getStyleClass().remove("light-theme");
            }
        }

        // Перестроить категории (кнопка «Недавние» зависит от текущего состояния)
        buildCategoryButtons();

        // Позиция: над кнопкой, выровнено по левому краю
        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds == null) { return; }
        double x = bounds.getMinX();
        double y = bounds.getMinY() - PICKER_HEIGHT - 4;
        // Если не влезает сверху — показать снизу
        if (y < 0) {
            y = bounds.getMaxY() + 4;
        }

        popup.show(anchor, x, y);
        searchField.clear();
        searchField.requestFocus();
        showCategory(getDefaultCategory());
    }

    /** Скрыть пикер */
    public void hide() {
        popup.hide();
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    // ==================== Внутренняя логика ====================

    /** Определить стартовую категорию: "recent" если есть недавние, иначе "smileys" */
    private String getDefaultCategory() {
        List<String> recent = EmojiRecentStore.getRecent();
        return recent.isEmpty() ? "smileys" : "recent";
    }

    /** Построить кнопки категорий */
    private void buildCategoryButtons() {
        categoryBar.getChildren().clear();

        // Кнопка «Недавние» (если есть)
        List<String> recent = EmojiRecentStore.getRecent();
        if (!recent.isEmpty()) {
            categoryBar.getChildren().add(
                    createCategoryButton("recent", "\uD83D\uDD53", "Недавние"));
        }

        // Кнопки остальных категорий
        for (EmojiData.Category cat : EmojiData.getCategories()) {
            categoryBar.getChildren().add(
                    createCategoryButton(cat.id(), cat.icon(), cat.label()));
        }
    }

    private StackPane createCategoryButton(String id, String icon, String tooltipText) {
        StackPane btn = new StackPane();
        ImageView iv = EmojiImageCache.createImageView(icon, 18);
        if (iv != null) {
            btn.getChildren().add(iv);
        } else {
            btn.getChildren().add(new Label(icon));
        }
        btn.getStyleClass().add("emoji-cat-btn");
        Tooltip.install(btn, new Tooltip(tooltipText));
        btn.setUserData(id);
        btn.setOnMouseClicked(e -> {
            searchField.clear();
            showCategory(id);
        });
        return btn;
    }

    /** Показать эмодзи указанной категории в сетке */
    private void showCategory(String categoryId) {
        activeCategory = categoryId;
        emojiGrid.getChildren().clear();
        gridScroll.setVvalue(0);

        List<String> emojis;
        if ("recent".equals(categoryId)) {
            emojis = EmojiRecentStore.getRecent();
        } else {
            emojis = EmojiData.getCategories().stream()
                    .filter(c -> c.id().equals(categoryId))
                    .findFirst()
                    .map(EmojiData.Category::emojis)
                    .orElse(List.of());
        }

        for (String emoji : emojis) {
            emojiGrid.getChildren().add(createEmojiCell(emoji));
        }

        updateCategoryHighlight();
    }

    /** Реакция на ввод в поле поиска */
    private void onSearchChanged(String query) {
        if (query == null || query.trim().isEmpty()) {
            showCategory(activeCategory != null ? activeCategory : getDefaultCategory());
            return;
        }
        activeCategory = null;
        List<String> results = EmojiData.search(query.trim());
        emojiGrid.getChildren().clear();
        gridScroll.setVvalue(0);
        for (String emoji : results) {
            emojiGrid.getChildren().add(createEmojiCell(emoji));
        }
        updateCategoryHighlight();
    }

    /** Создать ячейку сетки для одного эмодзи */
    private StackPane createEmojiCell(String emoji) {
        StackPane cell = new StackPane();
        ImageView iv = EmojiImageCache.createImageView(emoji, 24);
        if (iv != null) {
            cell.getChildren().add(iv);
        } else {
            cell.getChildren().add(new Label(emoji));
        }
        cell.getStyleClass().add("emoji-cell");
        cell.setMinSize(CELL_SIZE, CELL_SIZE);
        cell.setMaxSize(CELL_SIZE, CELL_SIZE);
        cell.setAlignment(Pos.CENTER);
        cell.setCursor(javafx.scene.Cursor.HAND);
        cell.setOnMouseClicked(e -> {
            EmojiRecentStore.addRecent(emoji);
            onEmojiSelected.accept(emoji);
            hide();
        });
        return cell;
    }

    /** Подсветить активную категорию */
    private void updateCategoryHighlight() {
        for (Node node : categoryBar.getChildren()) {
            node.getStyleClass().remove("emoji-cat-btn-active");
            if (activeCategory != null && activeCategory.equals(node.getUserData())) {
                node.getStyleClass().add("emoji-cat-btn-active");
            }
        }
    }
}
