package com.meshtastic.client.components;

import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.utils.UnicodeTextUtils;
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
import javafx.scene.text.Font;

import java.util.List;
import java.util.function.Consumer;

/**
 * Popup emoji picker anchored to a button.
 * Contains search, category tabs, and an emoji grid.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class EmojiPicker {

    private static final double PICKER_WIDTH = 320;
    private static final double PICKER_HEIGHT = 400;
    private static final int GRID_COLUMNS = 8;
    private static final double CELL_SIZE = 36;
    // Use a clock emoji that has a local PNG asset, avoiding the fallback square.
    private static final String RECENT_CATEGORY_ICON = "\uD83D\uDD50";

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

        // Hide automatically when focus leaves the popup or the user clicks outside.
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);

        root = new VBox(6);
        root.setPrefSize(PICKER_WIDTH, PICKER_HEIGHT);
        root.setMaxSize(PICKER_WIDTH, PICKER_HEIGHT);
        root.setPadding(new Insets(8));
        root.getStyleClass().add("emoji-picker");

        // Search
        searchField = new TextField();
        searchField.setPromptText(I18n.t("emoji.search.placeholder"));
        searchField.getStyleClass().add("emoji-picker-search");
        searchField.textProperty().addListener((obs, old, val) -> onSearchChanged(val));
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hide();
                e.consume();
            }
        });

        // Category bar
        categoryBar = new HBox(2);
        categoryBar.setAlignment(Pos.CENTER);
        categoryBar.getStyleClass().add("emoji-picker-categories");

        // Emoji grid
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

    /** Shows or hides the picker relative to the given anchor node. */
    public void toggle(Node anchor) {
        if (popup.isShowing()) {
            hide();
        } else {
            show(anchor);
        }
    }

    /** Shows the picker above the button, or below it if there is no room. */
    public void show(Node anchor) {
        // Sync theme: Popup does not inherit style classes from the scene root.
        if (anchor.getScene() != null && anchor.getScene().getRoot() != null) {
            boolean isLight = anchor.getScene().getRoot().getStyleClass().contains("light-theme");
            if (isLight && !root.getStyleClass().contains("light-theme")) {
                root.getStyleClass().add("light-theme");
            } else if (!isLight) {
                root.getStyleClass().remove("light-theme");
            }
        }

        // Rebuild categories; the Recent button depends on current state.
        buildCategoryButtons();

        // Position above the button and left-aligned to it.
        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds == null) { return; }
        double x = bounds.getMinX();
        double y = bounds.getMinY() - PICKER_HEIGHT - 4;
        // If there is no room above, show it below.
        if (y < 0) {
            y = bounds.getMaxY() + 4;
        }

        popup.show(anchor, x, y);
        searchField.clear();
        searchField.requestFocus();
        showCategory(getDefaultCategory());
    }

    /** Hides the picker. */
    public void hide() {
        popup.hide();
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    // ==================== Internal Logic ====================

    /** Chooses the initial category: "recent" when available, otherwise "smileys". */
    private String getDefaultCategory() {
        List<String> recent = EmojiRecentStore.getRecent();
        return recent.isEmpty() ? "smileys" : "recent";
    }

    /** Builds the category buttons. */
    private void buildCategoryButtons() {
        categoryBar.getChildren().clear();

        // Recent button, when there is recent history.
        List<String> recent = EmojiRecentStore.getRecent();
        if (!recent.isEmpty()) {
            categoryBar.getChildren().add(
                    createCategoryButton("recent", RECENT_CATEGORY_ICON, I18n.t("emoji.category.recent")));
        }

        // Buttons for all other categories.
        for (EmojiData.Category cat : EmojiData.getCategories()) {
            categoryBar.getChildren().add(
                    createCategoryButton(cat.id(), cat.icon(), I18n.t(cat.labelKey())));
        }
    }

    private StackPane createCategoryButton(String id, String icon, String tooltipText) {
        StackPane btn = new StackPane();
        btn.getChildren().add(createEmojiGraphic(icon, 18));
        btn.getStyleClass().add("emoji-cat-btn");
        Tooltip.install(btn, new Tooltip(tooltipText));
        btn.setUserData(id);
        btn.setOnMouseClicked(e -> {
            searchField.clear();
            showCategory(id);
        });
        return btn;
    }

    /** Shows the emoji for the selected category in the grid. */
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

    /** Handles search-field input. */
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

    /** Creates one grid cell for an emoji. */
    private StackPane createEmojiCell(String emoji) {
        StackPane cell = new StackPane();
        cell.getChildren().add(createEmojiGraphic(emoji, 24));
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

    /** Highlights the active category. */
    private void updateCategoryHighlight() {
        for (Node node : categoryBar.getChildren()) {
            node.getStyleClass().remove("emoji-cat-btn-active");
            if (activeCategory != null && activeCategory.equals(node.getUserData())) {
                node.getStyleClass().add("emoji-cat-btn-active");
            }
        }
    }

    private Node createEmojiGraphic(String emoji, double size) {
        ImageView iv = EmojiImageCache.createImageView(emoji, size);
        if (iv != null) {
            return iv;
        }

        String safeEmoji = UnicodeTextUtils.sanitizeForJavaFxDisplay(emoji);
        Label fallback = new Label(safeEmoji.isEmpty() ? "□" : safeEmoji);
        fallback.setFont(Font.font(size));
        return fallback;
    }
}
