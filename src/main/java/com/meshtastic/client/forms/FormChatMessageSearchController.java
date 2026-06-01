package com.meshtastic.client.forms;

import com.meshtastic.client.components.chat.ChatBotCommandHelper;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.model.NodeData;
import com.meshtastic.client.service.ChatMessageSearchService;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Controls message search inside the active chat header.
 *
 * <p>This component owns only search-panel state: the query text, node filter,
 * current highlight, match counter, and node-selection popup. Expensive database
 * work is delegated to {@link ChatMessageSearchService}, leaving the JavaFX
 * Application Thread responsible only for control updates and scrolling to the
 * selected result.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class FormChatMessageSearchController {

    private static final Duration INPUT_PAUSE = Duration.millis(500);

    private final Host host;
    private final Label headerNameLabel;
    private final Region headerSpacer;
    private final ChatMessageSearchService searchService = new ChatMessageSearchService();
    private final FormChatMessageSearchNodeLookup nodeLookup;
    private final Button searchButton;
    private final HBox controls;
    private final TextField searchField;
    private final Button nodeButton;
    private final Label counterLabel;
    private final Button previousButton;
    private final Button nextButton;
    private final PauseTransition inputPause;

    private boolean active;
    private String query = "";
    private int resultCount;
    private int resultIndex = -1;
    private boolean resultCountLimited;
    private boolean hasPrevious;
    private boolean hasNext;
    private long highlightedDbId;
    private boolean textDirty;
    private boolean searchInProgress;
    private boolean nodeLookupActive;
    private String nodeFilterId = "";
    private String nodeFilterLabel = "";
    private String textBeforeNodeLookup = "";

    /**
     * Contract provided by the chat form.
 *
     * <p>Search does not depend on {@link FormChatUi} internals. It asks only
     * for the current chat key, the loaded-row window, and scrolling actions.
     * The form remains the data owner; search owns only its controls.
     */
    interface Host {

        /** Returns {@code true} when a searchable chat is currently open. */
        boolean hasSelectedChat();

        /** Current chat type used for message-database queries. */
        String currentChatType();

        /** Current chat key used for message-database queries. */
        String currentChatKey();

        /** Owner node id for the current connection, used to isolate message history. */
        String currentOwnerNodeId();

        /** Nodes available to the "search messages from node" filter. */
        List<NodeData> listBotCommandNodes();

        /** Loaded message rows where visual highlights can be applied. */
        Map<Long, HBox> loadedMessageRows();

        /** Loads enough history for the row with the given id to enter the window. */
        void ensureMessageLoaded(long dbId);

        /** Schedules message-area layout before exact scrolling to a found row. */
        void requestMessageViewportLayout();

        /** Scrolls the message area to the given message. */
        void scrollToMessage(long dbId, double anchorOffset);

        /** Returns focus to the message input after search closes. */
        void focusChatInput();
    }

    /**
     * Creates the controller and builds both header elements immediately: the
     * search-open button and the panel with field, counter, navigation, and node filter.
     */
    FormChatMessageSearchController(Host host, Label headerNameLabel, Region headerSpacer) {
        this.host = Objects.requireNonNull(host, "host");
        this.headerNameLabel = Objects.requireNonNull(headerNameLabel, "headerNameLabel");
        this.headerSpacer = Objects.requireNonNull(headerSpacer, "headerSpacer");
        this.searchButton = createSearchButton();
        this.searchField = createSearchField();
        this.inputPause = createInputPause();
        this.nodeLookup = new FormChatMessageSearchNodeLookup(
                searchField,
                host::listBotCommandNodes,
                this::selectNode,
                () -> nodeLookupActive,
                this::cancelNodeLookup);
        this.nodeButton = createNodeButton();
        this.counterLabel = createCounterLabel();
        this.previousButton = createPreviousButton();
        this.nextButton = createNextButton();
        Button closeButton = createCloseButton();
        this.controls = createControls(closeButton);
        updateControlsState();
    }

    /**
     * Button shown in the chat header while search is closed.
     */
    Button searchButton() {
        return searchButton;
    }

    /**
     * Search panel shown instead of the chat name while search is active.
     */
    HBox controls() {
        return controls;
    }

    /**
     * Handles right-pane shortcuts: Ctrl/Cmd+F opens search, Escape closes an
     * active search.
     */
    void handleDetailPaneKeyPressed(KeyEvent event) {
        if (!host.hasSelectedChat()) {
            return;
        }
        if (event.isShortcutDown() && event.getCode() == KeyCode.F) {
            open();
            event.consume();
            return;
        }
        if (active && event.getCode() == KeyCode.ESCAPE) {
            close(true);
            event.consume();
        }
    }

    /**
     * Opens the search panel for the current chat.
     *
     * <p>The header switches to a compact mode: chat name and spacer are hidden,
     * and the search field receives focus. If the user briefly left search, the
     * last query remains in the field and is selected for quick replacement.
     */
    void open() {
        if (!host.hasSelectedChat()) {
            return;
        }
        if (!active) {
            active = true;
            FormChatUiSupport.setVisibleManaged(searchButton, false);
            FormChatUiSupport.setVisibleManaged(headerNameLabel, false);
            FormChatUiSupport.setVisibleManaged(headerSpacer, false);
            FormChatUiSupport.setVisibleManaged(controls, true);
            searchButton.getStyleClass().add("chat-header-icon-btn-active");
            updateFieldPrompt();
            updateControlsState();
        }
        Platform.runLater(() -> {
            searchField.requestFocus();
            searchField.selectAll();
        });
    }

    /**
     * Closes search and resets all search state.
     *
     * <p>Active background tasks are invalidated by generation, the node picker
     * popup is hidden, and highlights are removed from loaded rows. When closed
     * by Escape or by the button, focus returns to the message input.
     */
    void close(boolean focusInput) {
        active = false;
        stopInputPause();
        invalidateSearchWork();
        textDirty = false;
        query = "";
        resetResultState();
        nodeLookupActive = false;
        nodeFilterId = "";
        nodeFilterLabel = "";
        textBeforeNodeLookup = "";
        nodeLookup.hide();
        searchButton.getStyleClass().remove("chat-header-icon-btn-active");
        FormChatUiSupport.setVisibleManaged(searchButton, true);
        searchField.clear();
        FormChatUiSupport.setVisibleManaged(headerNameLabel, true);
        FormChatUiSupport.setVisibleManaged(headerSpacer, true);
        FormChatUiSupport.setVisibleManaged(controls, false);
        refreshHighlight();
        updateFieldPrompt();
        updateControlsState();
        if (focusInput && host.hasSelectedChat()) {
            host.focusChatInput();
        }
    }

    /**
     * Re-runs search after the message window changes or a row is deleted.
     *
     * <p>If text is still waiting for debounce, this method does not start an
     * extra query: the current search begins after the input pause or Enter.
     */
    void refreshResults(boolean jumpToLatest) {
        if (!active || !host.hasSelectedChat() || nodeLookupActive || textDirty) {
            return;
        }

        long previousHighlightedDbId = highlightedDbId;
        int previousResultIndex = resultIndex;
        query = currentSearchText();
        if (query.isEmpty()) {
            invalidateSearchWork();
            resetResultState();
            updateControlsState();
            refreshHighlight();
            return;
        }

        startSearch(query, jumpToLatest, previousHighlightedDbId, previousResultIndex);
    }

    /**
     * Refreshes the found-message highlight on rows that are already loaded.
     */
    void refreshHighlight() {
        Optional.ofNullable(host.loadedMessageRows())
                .filter(rows -> !rows.isEmpty())
                .stream()
                .flatMap(rows -> rows.entrySet().stream())
                .forEach(entry -> applyHighlight(entry.getValue(), entry.getKey()));
    }

    /**
     * Applies the found-message CSS class to one row.
     */
    void applyHighlight(HBox row, long dbId) {
        Optional.ofNullable(row).ifPresent(messageRow -> {
            messageRow.getStyleClass().remove("chat-message-search-hit");
            if (highlightedDbId > 0 && highlightedDbId == dbId) {
                messageRow.getStyleClass().add("chat-message-search-hit");
            }
        });
    }

    /**
     * Releases the background search executor. Used when the form is explicitly
     * destroyed with the application.
     */
    void dispose() {
        searchService.close();
    }

    private Button createSearchButton() {
        Button button = FormChatUiSupport.createHeaderIconButton(
                "/icons/search.svg", I18n.t("chat.searchMessages.tooltip"), "🔍");
        button.setOnAction(event -> open());
        return button;
    }

    private TextField createSearchField() {
        TextField field = new TextField();
        field.setPromptText(I18n.t("chat.searchMessages.placeholder"));
        field.setMinWidth(54);
        field.getStyleClass().add("chat-message-search-field");
        field.textProperty().addListener((obs, oldValue, newValue) -> handleTextChanged(newValue));
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::handleFieldKeyPressed);
        return field;
    }

    private PauseTransition createInputPause() {
        PauseTransition pause = new PauseTransition(INPUT_PAUSE);
        pause.setOnFinished(event -> runPendingSearch(true));
        return pause;
    }

    private Button createNodeButton() {
        Button button = FormChatUiSupport.createHeaderIconButton(
                "/icons/user.svg", I18n.t("chat.searchMessages.nodeFilter"), "👤");
        button.setOnAction(event -> toggleNodeLookup());
        return button;
    }

    private Label createCounterLabel() {
        Label label = new Label();
        label.getStyleClass().add("chat-message-search-counter");
        return label;
    }

    private Button createPreviousButton() {
        Button button = FormChatUiSupport.createMessageSearchNavButton(
                "↑", I18n.t("chat.searchMessages.previous"));
        button.setOnAction(event -> showPreviousResult());
        return button;
    }

    private Button createNextButton() {
        Button button = FormChatUiSupport.createMessageSearchNavButton(
                "↓", I18n.t("chat.searchMessages.next"));
        button.setOnAction(event -> showNextResult());
        return button;
    }

    private Button createCloseButton() {
        Button button = FormChatUiSupport.createHeaderIconButton(
                "/icons/close.svg", I18n.t("chat.searchMessages.close"), "×");
        button.getStyleClass().add("chat-message-search-close-btn");
        button.setOnAction(event -> close(true));
        return button;
    }

    private HBox createControls(Button closeButton) {
        HBox box = new HBox(4, nodeButton, searchField, counterLabel, previousButton, nextButton, closeButton);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("chat-message-search-controls");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        FormChatUiSupport.setVisibleManaged(box, false);
        return box;
    }

    private void handleFieldKeyPressed(KeyEvent event) {
        switch (event.getCode()) {
            case ESCAPE -> handleFieldEscape(event);
            case DOWN -> moveNodeLookupSelection(event, 1);
            case UP -> moveNodeLookupSelection(event, -1);
            case ENTER -> handleFieldEnter(event);
            default -> {
            }
        }
    }

    private void handleFieldEscape(KeyEvent event) {
        Runnable action = nodeLookupActive ? this::cancelNodeLookup : () -> close(true);
        action.run();
        event.consume();
    }

    private void moveNodeLookupSelection(KeyEvent event, int delta) {
        if (!nodeLookupActive) {
            return;
        }
        nodeLookup.ensureSuggestions();
        nodeLookup.moveSelection(delta);
        event.consume();
    }

    private void handleFieldEnter(KeyEvent event) {
        if (nodeLookupActive) {
            nodeLookup.selectCurrent();
            event.consume();
            return;
        }
        if (textDirty) {
            runPendingSearch(true);
            event.consume();
            return;
        }
        Runnable action = event.isShiftDown() ? this::showPreviousResult : this::showNextResult;
        action.run();
        event.consume();
    }

    private void startSearch(String searchQuery,
                             boolean jumpToLatest,
                             long previousHighlightedDbId,
                             int previousResultIndex) {
        long generation = beginSearchWork();
        query = searchQuery;
        resetResultState();
        searchInProgress = true;
        updateControlsState();
        refreshHighlight();

        ChatMessageSearchService.SearchRequest request = new ChatMessageSearchService.SearchRequest(
                generation,
                host.currentChatType(),
                host.currentChatKey(),
                searchQuery,
                host.currentOwnerNodeId(),
                currentNodeFilterId(),
                previousHighlightedDbId,
                previousResultIndex,
                jumpToLatest);
        searchService.submitSearch(
                request,
                result -> Platform.runLater(() -> applySearchResult(result)),
                (failedGeneration, error) -> Platform.runLater(() -> finishFailedSearch(failedGeneration)));
    }

    private void handleTextChanged(String newValue) {
        if (!active) {
            return;
        }

        invalidateSearchWork();
        stopInputPause();
        resetResultState();
        query = trimmedText(newValue);
        if (nodeLookupActive) {
            textDirty = false;
            refreshVisualState();
            nodeLookup.refreshSuggestions();
            return;
        }
        if (query.isEmpty()) {
            textDirty = false;
            refreshVisualState();
            return;
        }

        textDirty = true;
        refreshVisualState();
        inputPause.playFromStart();
    }

    private void runPendingSearch(boolean jumpToLatest) {
        stopInputPause();
        if (!active || nodeLookupActive) {
            return;
        }
        textDirty = false;
        refreshResults(jumpToLatest);
    }

    private long beginSearchWork() {
        return searchService.beginWork();
    }

    private void invalidateSearchWork() {
        searchService.invalidate();
        searchInProgress = false;
    }

    private void applySearchResult(ChatMessageSearchService.SearchResult result) {
        ChatMessageSearchService.SearchRequest request = result.request();
        if (!isCurrentSearchRequest(request)) {
            return;
        }

        searchInProgress = false;
        textDirty = false;
        query = request.query();
        highlightedDbId = result.highlightedDbId();
        resultCount = result.resultCount();
        resultIndex = result.resultIndex();
        resultCountLimited = result.resultCountLimited();
        hasPrevious = result.hasPrevious();
        hasNext = result.hasNext();

        updateControlsState();
        refreshHighlight();
        if (request.jumpToLatest() && highlightedDbId > 0) {
            showCurrentResult();
        }
    }

    private void finishFailedSearch(long generation) {
        if (!searchService.isCurrent(generation)) {
            return;
        }
        searchInProgress = false;
        updateControlsState();
    }

    private boolean isCurrentSearchRequest(ChatMessageSearchService.SearchRequest request) {
        return searchService.isCurrent(request.generation())
                && active
                && !nodeLookupActive
                && host.hasSelectedChat()
                && Objects.equals(request.chatType(), host.currentChatType())
                && Objects.equals(request.chatKey(), host.currentChatKey())
                && Objects.equals(request.ownerNodeId(), host.currentOwnerNodeId())
                && Objects.equals(request.fromNodeId(), currentNodeFilterId())
                && Objects.equals(request.query(), currentSearchText());
    }

    private boolean isCurrentNavigationRequest(ChatMessageSearchService.NavigationRequest request) {
        return searchService.isCurrent(request.generation())
                && active
                && !nodeLookupActive
                && host.hasSelectedChat()
                && Objects.equals(request.chatType(), host.currentChatType())
                && Objects.equals(request.chatKey(), host.currentChatKey())
                && Objects.equals(request.ownerNodeId(), host.currentOwnerNodeId())
                && Objects.equals(request.fromNodeId(), currentNodeFilterId())
                && Objects.equals(request.query(), query);
    }

    private String currentSearchText() {
        return trimmedText(searchField.getText());
    }

    private void stopInputPause() {
        inputPause.stop();
    }

    private void resetResultState() {
        resultCount = 0;
        resultIndex = -1;
        resultCountLimited = false;
        hasPrevious = false;
        hasNext = false;
        highlightedDbId = 0;
    }

    private void refreshVisualState() {
        refreshHighlight();
        updateControlsState();
    }

    private void showPreviousResult() {
        startNavigation(ChatMessageSearchService.Direction.PREVIOUS);
    }

    private void showNextResult() {
        startNavigation(ChatMessageSearchService.Direction.NEXT);
    }

    private void startNavigation(ChatMessageSearchService.Direction direction) {
        if (!canNavigate(direction)) {
            return;
        }

        long generation = beginSearchWork();
        searchInProgress = true;
        updateControlsState();
        ChatMessageSearchService.NavigationRequest request = new ChatMessageSearchService.NavigationRequest(
                generation,
                host.currentChatType(),
                host.currentChatKey(),
                query,
                host.currentOwnerNodeId(),
                currentNodeFilterId(),
                highlightedDbId,
                resultIndex,
                resultCount,
                resultCountLimited,
                direction);
        searchService.submitNavigation(
                request,
                result -> Platform.runLater(() -> applyNavigationResult(result)),
                (failedGeneration, error) -> Platform.runLater(() -> finishFailedSearch(failedGeneration)));
    }

    private boolean canNavigate(ChatMessageSearchService.Direction direction) {
        return !searchInProgress
                && !query.isBlank()
                && highlightedDbId > 0
                && host.hasSelectedChat()
                && switch (direction) {
                    case PREVIOUS -> hasPrevious;
                    case NEXT -> hasNext;
                };
    }

    private void applyNavigationResult(ChatMessageSearchService.NavigationResult result) {
        ChatMessageSearchService.NavigationRequest request = result.request();
        if (!isCurrentNavigationRequest(request)) {
            return;
        }

        searchInProgress = false;
        if (result.highlightedDbId() <= 0) {
            markNavigationEnd(request.direction());
            return;
        }

        highlightedDbId = result.highlightedDbId();
        resultIndex = result.resultIndex();
        hasPrevious = result.hasPrevious();
        hasNext = result.hasNext();
        showCurrentResult();
    }

    private void markNavigationEnd(ChatMessageSearchService.Direction direction) {
        switch (direction) {
            case PREVIOUS -> hasPrevious = false;
            case NEXT -> hasNext = false;
        }
        updateControlsState();
    }

    private void showCurrentResult() {
        if (resultIndex < 0 || resultCount <= 0 || highlightedDbId <= 0) {
            highlightedDbId = 0;
            refreshHighlight();
            updateControlsState();
            return;
        }

        host.ensureMessageLoaded(highlightedDbId);
        host.requestMessageViewportLayout();
        refreshHighlight();
        host.scrollToMessage(highlightedDbId, 0);
        updateControlsState();
    }

    private void toggleNodeLookup() {
        if (!active) {
            return;
        }
        if (nodeLookupActive) {
            cancelNodeLookup();
            return;
        }
        activateNodeLookup();
    }

    private void activateNodeLookup() {
        textBeforeNodeLookup = Optional.ofNullable(searchField.getText()).orElse("");
        nodeLookupActive = true;
        stopInputPause();
        textDirty = false;
        query = "";
        resetResultState();
        updateFieldPrompt();
        searchField.clear();
        refreshHighlight();
        updateControlsState();
        Platform.runLater(() -> {
            searchField.requestFocus();
            nodeLookup.refreshSuggestions();
        });
    }

    private void cancelNodeLookup() {
        finishNodeLookup(restoredLookupText(), false);
    }

    private void selectNode(NodeData node) {
        Optional.ofNullable(node).ifPresent(selectedNode -> {
            nodeFilterId = nodeId(selectedNode);
            nodeFilterLabel = ChatBotCommandHelper.displayName(selectedNode);
            finishNodeLookup(restoredLookupText(), true);
        });
    }

    private String restoredLookupText() {
        return Optional.ofNullable(textBeforeNodeLookup).orElse("");
    }

    private void finishNodeLookup(String restoredText, boolean refreshWhenEmpty) {
        textBeforeNodeLookup = "";
        nodeLookupActive = false;
        nodeLookup.hide();
        updateFieldPrompt();
        restoreSearchText(restoredText);
        if (restoredText.trim().isEmpty()) {
            resetResultState();
            Runnable stateUpdate = refreshWhenEmpty ? this::refreshVisualState : this::updateControlsState;
            stateUpdate.run();
            searchField.requestFocus();
            return;
        }
        runPendingSearch(true);
        searchField.requestFocus();
    }

    private void restoreSearchText(String text) {
        searchField.setText(text);
        searchField.positionCaret(searchField.getText().length());
    }

    private String currentNodeFilterId() {
        return Optional.ofNullable(nodeFilterId)
                .filter(id -> !id.isBlank())
                .orElse(null);
    }

    private String nodeId(NodeData node) {
        return Optional.ofNullable(node.getNodeId())
                .filter(rawNodeId -> !rawNodeId.isBlank())
                .map(String::trim)
                .orElseGet(() -> String.format("!%08x", node.getNodeNum()));
    }

    private void updateFieldPrompt() {
        String prompt = nodeLookupActive
                ? I18n.t("chat.searchMessages.nodeLookup")
                : Optional.ofNullable(currentNodeFilterId())
                        .map(id -> I18n.t("chat.searchMessages.fromNode", nodeFilterLabel))
                        .orElse(I18n.t("chat.searchMessages.placeholder"));
        searchField.setPromptText(prompt);
    }

    private void updateNodeButtonState() {
        nodeButton.getStyleClass().remove("chat-header-icon-btn-active");
        String filterId = currentNodeFilterId();
        if (nodeLookupActive || Optional.ofNullable(filterId).isPresent()) {
            nodeButton.getStyleClass().add("chat-header-icon-btn-active");
        }
        String tooltip = nodeLookupActive
                ? I18n.t("chat.searchMessages.nodePick")
                : Optional.ofNullable(filterId)
                        .map(id -> I18n.t("chat.searchMessages.filterActive", nodeFilterLabel))
                        .orElse(I18n.t("chat.searchMessages.nodeFilter"));
        nodeButton.setTooltip(new Tooltip(tooltip));
    }

    private String trimmedText(String value) {
        return Optional.ofNullable(value).map(String::trim).orElse("");
    }

    private void updateControlsState() {
        updateNodeButtonState();
        if (nodeLookupActive) {
            counterLabel.setText("");
            setNavigationDisabled(true);
            return;
        }

        boolean hasQuery = !currentSearchText().isEmpty();
        if (hasQuery && (textDirty || searchInProgress)) {
            counterLabel.setText("...");
            setNavigationDisabled(true);
            return;
        }
        boolean hasResults = resultCount > 0 && resultIndex >= 0 && highlightedDbId > 0;
        counterLabel.setText(!hasQuery ? "" : hasResults ? counterText() : "0");
        previousButton.setDisable(!hasResults || !hasPrevious);
        nextButton.setDisable(!hasResults || !hasNext);
    }

    private void setNavigationDisabled(boolean disabled) {
        previousButton.setDisable(disabled);
        nextButton.setDisable(disabled);
    }

    private String counterText() {
        return resultCountLimited ? resultCount + "+" : (resultIndex + 1) + "/" + resultCount;
    }
}
