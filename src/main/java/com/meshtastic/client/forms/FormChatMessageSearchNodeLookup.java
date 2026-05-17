package com.meshtastic.client.forms;

import com.meshtastic.client.components.chat.ChatBotCommandHelper;
import com.meshtastic.client.model.NodeData;

import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Отдельный компонент popup-поиска ноды для фильтра сообщений.
 *
 * <p>Компонент не знает о полнотекстовом поиске сообщений. Он читает текущий
 * текст из поля, строит список подходящих нод через {@link ChatBotCommandHelper},
 * держит выбранную строку popup-меню и сообщает наружу только выбранную
 * {@link NodeData}. Благодаря этому основной контроллер поиска не хранит
 * детали меню и клавиатурной навигации по подсказкам.
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
     * Связка найденной ноды и готовой строки popup-меню.
     *
     * @param node нода, которую нужно выбрать при активации строки
     * @param item JavaFX-элемент меню с текстом подсказки
     */
    private record SuggestionRow(NodeData node, CustomMenuItem item) {}

    /**
     * Создаёт popup-компонент и регистрирует обработчик клавиш меню.
     *
     * @param searchField поле, из которого берётся строка фильтра нод
     * @param nodeSupplier источник кандидатов для подсказок
     * @param nodeSelected callback выбора ноды
     * @param active признак активного режима выбора ноды
     * @param cancelLookup callback отмены режима выбора ноды
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
     * Обновляет список подсказок по текущему тексту поля.
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
     * Показывает подсказки, если список ещё не построен или меню временно скрыто.
     */
    void ensureSuggestions() {
        if (currentMatches.isEmpty()) {
            refreshSuggestions();
            return;
        }
        showIfNeeded();
    }

    /**
     * Сдвигает выделение подсказки с зацикливанием по списку.
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
     * Выбирает текущую подсказку и передаёт её внешнему контроллеру.
     */
    void selectCurrent() {
        ensureSuggestions();
        if (selectedIndex < 0 || selectedIndex >= currentMatches.size()) {
            return;
        }
        nodeSelected.accept(currentMatches.get(selectedIndex));
    }

    /**
     * Очищает состояние popup и скрывает меню.
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
