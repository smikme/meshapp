package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.components.EmojiPicker;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.utils.UnicodeTextUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Панель ввода чата: кнопка эмодзи + текстовое поле + счётчик байт
 * + кнопка отправки + полоса ответа.
 *
 * <p>Расширяет VBox (содержит replyBar + inputBar).
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ChatInputBar extends VBox {

    /**
     * Максимальный размер сериализованного Data protobuf (mesh.proto: DATA_PAYLOAD_LEN).
     * Протокольный overhead внутри Data: portnum (2 байта) + payload tag+length (3 байта) = 5.
     * При режиме ответа добавляется reply_id: tag (1 байт) + fixed32 (4 байта) = 5.
     */
    private static final int DATA_PAYLOAD_LEN = 233;
    private static final int PROTO_OVERHEAD = 5;
    private static final int REPLY_ID_OVERHEAD = 5;
    private static final int MAX_COMMAND_SUGGESTIONS = 8;
    private static final String SELECTED_SUGGESTION_STYLE_CLASS = "chat-command-suggestion-btn-selected";

    /** Данные для колбэка отправки. */
    public record SendRequest(String text, int replyId) {}

    private final Consumer<SendRequest> onSend;
    private final Predicate<ChatBotCommandHelper.ParsedBotCommand> onBotCommand;
    private final Function<String, List<ChatBotCommandHelper.BotDefinition>> botSuggestionProvider;
    private final Function<String, List<ChatBotCommandHelper.NodeSuggestion>> nodeSuggestionProvider;
    private final EmojiTextField messageInput;
    private final SendButtonWithRing sendRing;
    private final EmojiPicker emojiPicker;
    private final StackPane inputStack;
    private final VBox commandSuggestionRoot;

    private final HBox replyBar;
    private final Label replyQuoteLabel;
    private final Separator inputSep;

    private MeshMessage replyToMessage;
    private int savedCaretPosition;
    private int selectedSuggestionIndex = -1;

    /**
     * @param onSend колбэк отправки сообщения (текст + replyId)
     */
    public ChatInputBar(Consumer<SendRequest> onSend,
                        Predicate<ChatBotCommandHelper.ParsedBotCommand> onBotCommand,
                        Function<String, List<ChatBotCommandHelper.BotDefinition>> botSuggestionProvider,
                        Function<String, List<ChatBotCommandHelper.NodeSuggestion>> nodeSuggestionProvider) {
        this.onSend = onSend;
        this.onBotCommand = onBotCommand;
        this.botSuggestionProvider = botSuggestionProvider != null
                ? botSuggestionProvider
                : ChatBotCommandHelper::suggestBots;
        this.nodeSuggestionProvider = nodeSuggestionProvider;
        getStyleClass().add("chat-input-wrapper");

        // Разделитель
        inputSep = new Separator();
        inputSep.getStyleClass().add("chat-input-separator");

        // Кнопка эмодзи (с изображением, размер как у кнопки отправки)
        Button emojiBtn = new Button();
        ImageView emojiBtnIcon = EmojiImageCache.createImageView("\uD83D\uDE00", 22);
        if (emojiBtnIcon != null) {
            emojiBtn.setGraphic(emojiBtnIcon);
        } else {
            emojiBtn.setText("\uD83D\uDE00");
        }
        emojiBtn.getStyleClass().add("chat-emoji-btn");
        emojiBtn.setTooltip(new Tooltip("Эмодзи"));
        emojiPicker = new EmojiPicker(this::insertEmoji);
        emojiBtn.setOnAction(e -> emojiPicker.toggle(emojiBtn));

        commandSuggestionRoot = new VBox(2);
        commandSuggestionRoot.getStyleClass().add("chat-command-popup");
        commandSuggestionRoot.setVisible(false);
        commandSuggestionRoot.setManaged(false);

        // Поле ввода (кастомное с emoji-картинками)
        messageInput = new EmojiTextField();
        messageInput.setPromptText("Сообщение...");
        messageInput.setMaxBytesSupplier(this::getMaxTextBytes);
        HBox.setHgrow(messageInput, Priority.ALWAYS);

        // Кнопка отправки с кольцевым индикатором заполненности
        sendRing = new SendButtonWithRing(this::doSend);

        messageInput.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                savedCaretPosition = messageInput.getCaretPosition();
            }
            Platform.runLater(this::refreshCommandSuggestions);
        });

        messageInput.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && textByteLength(newVal) > getMaxTextBytes()) {
                // Откатить к предыдущему тексту целиком, а не обрезать конец —
                // иначе вставка в середину «работает» (удаляет последний символ)
                messageInput.setText(oldVal != null ? oldVal : "");
                return;
            }
            updateCharCount();
            sendRing.setSendDisable(newVal == null || newVal.trim().isEmpty());
            Platform.runLater(this::refreshCommandSuggestions);
        });
        messageInput.caretPositionProperty().addListener((obs, oldVal, newVal) ->
                Platform.runLater(this::refreshCommandSuggestions));
        messageInput.widthProperty().addListener((obs, oldVal, newVal) -> {
            if (isCommandSuggestionsVisible()) {
                Platform.runLater(this::showCommandSuggestions);
            }
        });
        messageInput.setKeyPressedInterceptor(this::handleCommandSuggestionKeyPressed);

        messageInput.setOnAction(v -> doSend());

        HBox inputBar = new HBox(8, emojiBtn, messageInput, sendRing);
        inputBar.setAlignment(Pos.CENTER_LEFT);
        inputBar.setPadding(new Insets(8, 15, 8, 15));
        inputBar.getStyleClass().add("chat-input-bar");

        inputStack = new StackPane(inputBar, commandSuggestionRoot);
        inputStack.setMaxWidth(Double.MAX_VALUE);
        inputStack.setAlignment(Pos.BOTTOM_LEFT);
        StackPane.setAlignment(inputBar, Pos.BOTTOM_LEFT);
        StackPane.setAlignment(commandSuggestionRoot, Pos.BOTTOM_LEFT);

        // Панель ответа (скрыта по умолчанию)
        Label replyIcon = new Label("↩");
        replyIcon.getStyleClass().add("chat-reply-icon");

        replyQuoteLabel = new Label();
        replyQuoteLabel.getStyleClass().add("chat-reply-quote");
        replyQuoteLabel.setMaxWidth(Double.MAX_VALUE);
        replyQuoteLabel.setWrapText(false);
        HBox.setHgrow(replyQuoteLabel, Priority.ALWAYS);

        Button cancelReplyBtn = new Button("✕");
        cancelReplyBtn.getStyleClass().add("chat-reply-cancel");
        cancelReplyBtn.setTooltip(new Tooltip("Отменить ответ"));
        cancelReplyBtn.setOnAction(e -> cancelReply());

        replyBar = new HBox(8, replyIcon, replyQuoteLabel, cancelReplyBtn);
        replyBar.setAlignment(Pos.CENTER_LEFT);
        replyBar.setPadding(new Insets(6, 15, 6, 15));
        replyBar.getStyleClass().add("chat-reply-bar");
        replyBar.setVisible(false);
        replyBar.setManaged(false);

        getChildren().addAll(replyBar, inputStack);
    }

    /** Получить разделитель для размещения над панелью ввода. */
    public Separator getInputSeparator() {
        return inputSep;
    }

    /** Очистить поле ввода и сбросить режим ответа. */
    public void clear() {
        hideCommandSuggestions();
        messageInput.clear();
        cancelReply();
    }

    /** Включить/выключить панель ввода. */
    public void setInputEnabled(boolean enabled) {
        messageInput.setFieldDisabled(!enabled);
        sendRing.setSendDisable(!enabled
                || messageInput.getText().trim().isEmpty());
        if (!enabled) {
            hideCommandSuggestions();
        }
    }

    /** Включить режим ответа на сообщение. */
    public void startReply(MeshMessage msg, String senderName) {
        if (msg == null || msg.getPacketId() == 0) {
            return;
        }
        replyToMessage = msg;

        String preview = UnicodeTextUtils.truncateWithSuffix(msg.getText(), 80, "…");
        replyQuoteLabel.setText(
                UnicodeTextUtils.sanitize(senderName != null ? senderName : "")
                        + ": "
                        + (preview != null ? preview : ""));

        replyBar.setVisible(true);
        replyBar.setManaged(true);
        updateCharCount();
        messageInput.requestFocus();
    }

    /** Отменить режим ответа. */
    public void cancelReply() {
        replyToMessage = null;
        replyBar.setVisible(false);
        replyBar.setManaged(false);
        replyQuoteLabel.setText("");
        updateCharCount();
    }

    /** Запросить фокус на текстовое поле. */
    public void focusInput() {
        Platform.runLater(() -> messageInput.requestFocus());
    }

    // === Внутренние методы ===

    /** Максимальное количество байт текста с учётом protobuf overhead и режима ответа. */
    private int getMaxTextBytes() {
        int overhead = PROTO_OVERHEAD;
        if (replyToMessage != null) {
            overhead += REPLY_ID_OVERHEAD;
        }
        return DATA_PAYLOAD_LEN - overhead;
    }

    private void doSend() {
        String text = messageInput.getText();
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        text = text.trim();

        ChatBotCommandHelper.ParsedBotCommand botCommand = ChatBotCommandHelper.parseCommand(
                text,
                botSuggestionProvider.apply(""));
        if (botCommand.isCommand()) {
            hideCommandSuggestions();
            if (onBotCommand != null && onBotCommand.test(botCommand)) {
                messageInput.clear();
                cancelReply();
            }
            return;
        }

        hideCommandSuggestions();
        int replyId = replyToMessage != null
                ? replyToMessage.getPacketId() : 0;
        onSend.accept(new SendRequest(text, replyId));
        messageInput.clear();
        cancelReply();
    }

    private void insertEmoji(String emoji) {
        String text = messageInput.getText();
        int maxBytes = getMaxTextBytes();
        if (textByteLength(text) + textByteLength(emoji) > maxBytes) {
            return;
        }
        int caret = Math.min(savedCaretPosition, text.length());
        messageInput.insertText(caret, emoji);
        savedCaretPosition = caret + emoji.length();
        Platform.runLater(() -> {
            messageInput.requestFocus();
            messageInput.positionCaret(savedCaretPosition);
            refreshCommandSuggestions();
        });
    }

    private void updateCharCount() {
        String text = messageInput.getText();
        int byteLen = text != null ? textByteLength(text) : 0;
        int maxBytes = getMaxTextBytes();
        sendRing.update(byteLen, maxBytes);
    }

    /** Длина строки в байтах UTF-8. */
    private static int textByteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private void refreshCommandSuggestions() {
        if (messageInput.isDisabled() || nodeSuggestionProvider == null) {
            hideCommandSuggestions();
            return;
        }
        if (!messageInput.isFocused()) {
            hideCommandSuggestions();
            return;
        }

        ChatBotCommandHelper.SuggestionContext context = ChatBotCommandHelper.detectSuggestionContext(
                messageInput.getText(),
                messageInput.getCaretPosition()
        );
        if (!context.isVisible()) {
            hideCommandSuggestions();
            return;
        }

        List<Button> rows = switch (context.mode()) {
            case BOT -> buildBotSuggestionRows(context);
            case NODE -> buildNodeSuggestionRows(context);
            case NONE -> List.of();
        };
        if (rows.isEmpty()) {
            hideCommandSuggestions();
            return;
        }

        commandSuggestionRoot.getChildren().setAll(rows);
        selectedSuggestionIndex = 0;
        updateSelectedSuggestionStyles();
        showCommandSuggestions();
    }

    private List<Button> buildBotSuggestionRows(ChatBotCommandHelper.SuggestionContext context) {
        List<ChatBotCommandHelper.BotDefinition> suggestions = botSuggestionProvider.apply(context.query());
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of();
        }
        return suggestions
                .stream()
                .limit(MAX_COMMAND_SUGGESTIONS)
                .map(bot -> buildSuggestionButton(
                        bot.handle(),
                        bot.description(),
                        () -> applyBotSuggestion(bot.handle())))
                .toList();
    }

    private List<Button> buildNodeSuggestionRows(ChatBotCommandHelper.SuggestionContext context) {
        List<ChatBotCommandHelper.NodeSuggestion> suggestions = nodeSuggestionProvider.apply(context.query());
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of();
        }
        return suggestions
                .stream()
                .limit(MAX_COMMAND_SUGGESTIONS)
                .map(suggestion -> buildSuggestionButton(
                        suggestion.primaryText(),
                        suggestion.secondaryText(),
                        () -> applyNodeSuggestion(context, suggestion.insertText())))
                .toList();
    }

    private Button buildSuggestionButton(String primary, String secondary, Runnable action) {
        Label primaryLabel = new Label(primary);
        primaryLabel.getStyleClass().add("chat-command-suggestion-primary");

        Label secondaryLabel = new Label(secondary);
        secondaryLabel.getStyleClass().add("chat-command-suggestion-secondary");
        boolean hasSecondary = secondary != null && !secondary.isBlank();
        secondaryLabel.setVisible(hasSecondary);
        secondaryLabel.setManaged(hasSecondary);

        VBox labels = new VBox(2, primaryLabel, secondaryLabel);
        labels.setAlignment(Pos.CENTER_LEFT);

        Button button = new Button();
        button.setGraphic(labels);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.getStyleClass().add("chat-command-suggestion-btn");
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        button.setOnMouseEntered(e -> selectSuggestion(button));
        return button;
    }

    private void applyBotSuggestion(String handle) {
        String current = messageInput.getText();
        ChatBotCommandHelper.SuggestionContext context = ChatBotCommandHelper.detectSuggestionContext(
                current, messageInput.getCaretPosition());
        if (context.mode() != ChatBotCommandHelper.SuggestionMode.BOT) {
            return;
        }

        String remainder = current.substring(context.replacementEnd()).stripLeading();
        String newText = handle + " " + remainder;
        int newCaret = handle.length() + 1;

        messageInput.setText(newText);
        savedCaretPosition = newCaret;
        messageInput.requestFocus();
        messageInput.positionCaret(newCaret);
        Platform.runLater(this::refreshCommandSuggestions);
    }

    private void applyNodeSuggestion(ChatBotCommandHelper.SuggestionContext context, String insertText) {
        String current = messageInput.getText();
        String prefix = current.substring(0, Math.min(context.replacementStart(), current.length())).stripTrailing();
        String newText = prefix + " " + insertText;
        int newCaret = newText.length();

        hideCommandSuggestions();
        messageInput.setText(newText);
        savedCaretPosition = newCaret;
        messageInput.requestFocus();
        messageInput.positionCaret(newCaret);
    }

    private void showCommandSuggestions() {
        double width = Math.max(messageInput.getWidth(), 260);
        commandSuggestionRoot.setPrefWidth(width);
        commandSuggestionRoot.setVisible(true);
        commandSuggestionRoot.applyCss();
        commandSuggestionRoot.autosize();
        double height = commandSuggestionRoot.prefHeight(width);
        commandSuggestionRoot.setTranslateY(-(height + 4));
        commandSuggestionRoot.toFront();
    }

    private void hideCommandSuggestions() {
        selectedSuggestionIndex = -1;
        commandSuggestionRoot.setVisible(false);
    }

    private boolean handleCommandSuggestionKeyPressed(KeyEvent event) {
        if (!isCommandSuggestionsVisible() || commandSuggestionRoot.getChildren().isEmpty()) {
            return false;
        }

        KeyCode code = event.getCode();
        switch (code) {
            case DOWN -> {
                moveSuggestionSelection(1);
                return true;
            }
            case UP -> {
                moveSuggestionSelection(-1);
                return true;
            }
            case ENTER -> {
                fireSelectedSuggestion();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void moveSuggestionSelection(int delta) {
        int size = commandSuggestionRoot.getChildren().size();
        if (size == 0) {
            selectedSuggestionIndex = -1;
            return;
        }
        if (selectedSuggestionIndex < 0) {
            selectedSuggestionIndex = 0;
        } else {
            selectedSuggestionIndex = Math.floorMod(selectedSuggestionIndex + delta, size);
        }
        updateSelectedSuggestionStyles();
    }

    private void fireSelectedSuggestion() {
        if (commandSuggestionRoot.getChildren().isEmpty()) {
            return;
        }
        if (selectedSuggestionIndex < 0 || selectedSuggestionIndex >= commandSuggestionRoot.getChildren().size()) {
            selectedSuggestionIndex = 0;
        }
        if (commandSuggestionRoot.getChildren().get(selectedSuggestionIndex) instanceof Button button) {
            button.fire();
        }
    }

    private void selectSuggestion(Button button) {
        int idx = commandSuggestionRoot.getChildren().indexOf(button);
        if (idx < 0) {
            return;
        }
        selectedSuggestionIndex = idx;
        updateSelectedSuggestionStyles();
    }

    private void updateSelectedSuggestionStyles() {
        for (int i = 0; i < commandSuggestionRoot.getChildren().size(); i++) {
            if (!(commandSuggestionRoot.getChildren().get(i) instanceof Button button)) {
                continue;
            }
            boolean selected = i == selectedSuggestionIndex;
            if (selected) {
                if (!button.getStyleClass().contains(SELECTED_SUGGESTION_STYLE_CLASS)) {
                    button.getStyleClass().add(SELECTED_SUGGESTION_STYLE_CLASS);
                }
            } else {
                button.getStyleClass().remove(SELECTED_SUGGESTION_STYLE_CLASS);
            }
        }
    }

    private boolean isCommandSuggestionsVisible() {
        return commandSuggestionRoot.isVisible();
    }

}
