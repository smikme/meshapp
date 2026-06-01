package com.meshtastic.client.forms;

import com.meshtastic.client.components.chat.ChatBotCommandHelper;
import com.meshtastic.client.model.NodeData;

import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Dedicated node lookup popup used by the message sender filter.
 *
 * <p>The component is deliberately unaware of full-text message search. It reads
 * the current field text, builds matching node suggestions through
 * {@link ChatBotCommandHelper}, owns popup selection and keyboard navigation,
 * and reports only the chosen {@link NodeData} to the outer controller.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class FormChatMessageSearchNodeLookup {

    private final TextField searchField;
    private final Supplier<List<NodeData>> nodeSupplier;
    private final Consumer<NodeData> nodeSelected;
    private final BooleanSupplier active;
    private final Runnable cancelLookup;
    private final ContextMenu nodeMenu = new ContextMenu();

    private List<NodeData> currentMatches = List.of();
    private List<CustomMenuItem> currentItems = List.of();
    private int selectedIndex = -1;

    /**
     * Pair of a matched node and a prepared popup menu row.
     *
     * @param node node selected when the row is activated
     * @param item JavaFX menu item containing the suggestion text
     */
    private record SuggestionRow(NodeData node, CustomMenuItem item) {}

    /**
     * Creates the popup component and registers menu key handling.
     *
     * @param searchField field containing node filter text
     * @param nodeSupplier source of suggestion candidates
     * @param nodeSelected callback invoked when a node is selected
     * @param active whether node-pick mode is active
     * @param cancelLookup callback that cancels node-pick mode
     */
    FormChatMessageSearchNodeLookup(TextField searchField,
                                    Supplier<List<NodeData>> nodeSupplier,
                                    Consumer<NodeData> nodeSelected,
                                    BooleanSupplier active,
                                    Runnable cancelLookup) {
        this.searchField = searchField;
        this.nodeSupplier = nodeSupplier;
        this.nodeSelected = nodeSelected;
        this.active = active;
        this.cancelLookup = cancelLookup;
        this.nodeMenu.addEventFilter(KeyEvent.KEY_PRESSED, this::handleMenuKeyPressed);
    }

    /**
     * Refreshes suggestions from the current field text.
     */
    void refreshSuggestions() {
        if (!active.getAsBoolean()) {
            hide();
            return;
        }

        String nodeQuery = currentFieldText();
        List<NodeData> candidates = nodeSupplier.get();
        List<ChatBotCommandHelper.NodeSuggestion> suggestions =
                ChatBotCommandHelper.suggestNodes(candidates, nodeQuery, 8);
        List<SuggestionRow> rows = suggestions.stream()
                .map(suggestion -> buildSuggestionRow(suggestion, candidates))
                .flatMap(Optional::stream)
                .toList();

        if (rows.isEmpty()) {
            hide();
            return;
        }

        currentMatches = rows.stream()
                .map(SuggestionRow::node)
                .toList();
        currentItems = IntStream.range(0, rows.size())
                .mapToObj(index -> withHoverSelection(rows.get(index).item(), index))
                .toList();
        selectedIndex = 0;
        nodeMenu.getItems().setAll(currentItems);
        updateSelection();
        showIfNeeded();
    }

    /**
     * Shows suggestions when the list is not yet built or the menu is temporarily hidden.
     */
    void ensureSuggestions() {
        if (currentMatches.isEmpty()) {
            refreshSuggestions();
            return;
        }
        showIfNeeded();
    }

    /**
     * Moves suggestion selection with wraparound.
     */
    void moveSelection(int delta) {
        if (currentMatches.isEmpty()) {
            return;
        }
        selectedIndex = selectedIndex < 0 || selectedIndex >= currentMatches.size()
                ? initialSelectionIndex(delta)
                : Math.floorMod(selectedIndex + delta, currentMatches.size());
        updateSelection();
    }

    /**
     * Chooses the current suggestion and passes it to the outer controller.
     */
    void selectCurrent() {
        ensureSuggestions();
        if (selectedIndex < 0 || selectedIndex >= currentMatches.size()) {
            return;
        }
        nodeSelected.accept(currentMatches.get(selectedIndex));
    }

    /**
     * Clears popup state and hides the menu.
     */
    void hide() {
        currentMatches = List.of();
        currentItems = List.of();
        selectedIndex = -1;
        nodeMenu.hide();
    }

    private void handleMenuKeyPressed(KeyEvent event) {
        if (!active.getAsBoolean()) {
            return;
        }
        switch (event.getCode()) {
            case ENTER -> {
                selectCurrent();
                event.consume();
            }
            case DOWN -> {
                moveSelection(1);
                event.consume();
            }
            case UP -> {
                moveSelection(-1);
                event.consume();
            }
            case ESCAPE -> {
                cancelLookup.run();
                event.consume();
            }
            default -> {
            }
        }
    }

    private Optional<SuggestionRow> buildSuggestionRow(ChatBotCommandHelper.NodeSuggestion suggestion,
                                                       List<NodeData> candidates) {
        return Optional.of(ChatBotCommandHelper.resolveTarget(suggestion.insertText(), candidates))
                .filter(resolution -> resolution.status() == ChatBotCommandHelper.NodeResolutionStatus.FOUND)
                .map(ChatBotCommandHelper.NodeResolution::node)
                .flatMap(Optional::ofNullable)
                .map(node -> new SuggestionRow(node, buildSuggestionItem(suggestion, node)));
    }

    private CustomMenuItem buildSuggestionItem(ChatBotCommandHelper.NodeSuggestion suggestion, NodeData node) {
        Label primary = new Label(suggestion.primaryText());
        primary.getStyleClass().add("chat-command-suggestion-primary");

        Label secondary = new Label(suggestion.secondaryText());
        secondary.getStyleClass().add("chat-command-suggestion-secondary");
        boolean hasSecondary = Optional.ofNullable(suggestion.secondaryText())
                .filter(text -> !text.isBlank())
                .isPresent();
        secondary.setVisible(hasSecondary);
        secondary.setManaged(hasSecondary);

        VBox labels = new VBox(2, primary, secondary);
        labels.setAlignment(Pos.CENTER_LEFT);
        labels.getStyleClass().add("map-search-suggestion-row");
        labels.setPrefWidth(Math.max(220, searchField.getWidth()));

        CustomMenuItem item = new CustomMenuItem(labels, true);
        item.setOnAction(event -> nodeSelected.accept(node));
        return item;
    }

    private CustomMenuItem withHoverSelection(CustomMenuItem item, int index) {
        item.getContent().setOnMouseEntered(event -> {
            selectedIndex = index;
            updateSelection();
        });
        return item;
    }

    private void updateSelection() {
        IntStream.range(0, currentItems.size()).forEach(index -> {
            var content = currentItems.get(index).getContent();
            content.getStyleClass().remove("map-search-suggestion-row-selected");
            if (index == selectedIndex) {
                content.getStyleClass().add("map-search-suggestion-row-selected");
            }
        });
    }

    private int initialSelectionIndex(int delta) {
        return delta > 0 ? 0 : currentMatches.size() - 1;
    }

    private void showIfNeeded() {
        Optional.ofNullable(searchField.getScene())
                .filter(scene -> !nodeMenu.isShowing())
                .ifPresent(scene -> nodeMenu.show(searchField, Side.BOTTOM, 0, 0));
    }

    private String currentFieldText() {
        return Optional.ofNullable(searchField.getText()).map(String::trim).orElse("");
    }
}
