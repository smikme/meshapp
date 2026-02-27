package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.EmojiPicker;
import com.meshtastic.client.model.MeshMessage;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Панель ввода чата: кнопка эмодзи + текстовое поле + счётчик байт
 * + кнопка отправки + полоса ответа.
 *
 * <p>Расширяет VBox (содержит replyBar + inputBar).
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

    /** Данные для колбэка отправки. */
    public record SendRequest(String text, int replyId) {}

    private final Consumer<SendRequest> onSend;
    private final TextField messageInput;
    private final Label charCountLabel;
    private final Button sendButton;
    private final EmojiPicker emojiPicker;

    private final HBox replyBar;
    private final Label replyQuoteLabel;
    private final Separator inputSep;

    private MeshMessage replyToMessage;
    private int savedCaretPosition;

    /**
     * @param onSend колбэк отправки сообщения (текст + replyId)
     */
    public ChatInputBar(Consumer<SendRequest> onSend) {
        this.onSend = onSend;
        getStyleClass().add("chat-input-wrapper");

        // Разделитель
        inputSep = new Separator();
        inputSep.getStyleClass().add("chat-input-separator");

        // Кнопка эмодзи
        Button emojiBtn = new Button("\uD83D\uDE00");
        emojiBtn.getStyleClass().add("chat-emoji-btn");
        emojiBtn.setTooltip(new Tooltip("Эмодзи"));
        emojiPicker = new EmojiPicker(this::insertEmoji);
        emojiBtn.setOnAction(e -> emojiPicker.toggle(emojiBtn));

        // Поле ввода
        messageInput = new TextField();
        messageInput.setPromptText("Сообщение...");
        messageInput.getStyleClass().add("chat-message-input");
        HBox.setHgrow(messageInput, Priority.ALWAYS);

        // Счётчик байт
        charCountLabel = new Label("0/" + getMaxTextBytes());
        charCountLabel.getStyleClass().add("chat-char-count");

        // Кнопка отправки
        sendButton = new Button("➤");
        sendButton.getStyleClass().add("chat-send-btn");
        sendButton.setTooltip(new Tooltip("Отправить"));
        sendButton.setOnAction(e -> doSend());
        sendButton.setDisable(true);

        messageInput.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                savedCaretPosition = messageInput.getCaretPosition();
            }
        });

        messageInput.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && textByteLength(newVal) > getMaxTextBytes()) {
                messageInput.setText(truncateToBytes(newVal, getMaxTextBytes()));
                return;
            }
            updateCharCount();
            sendButton.setDisable(newVal == null || newVal.trim().isEmpty());
        });

        messageInput.setOnAction(e -> doSend());

        HBox inputBar = new HBox(8,
                emojiBtn, messageInput, charCountLabel, sendButton);
        inputBar.setAlignment(Pos.CENTER);
        inputBar.setPadding(new Insets(8, 15, 8, 15));
        inputBar.getStyleClass().add("chat-input-bar");

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

        getChildren().addAll(replyBar, inputBar);
    }

    /** Получить разделитель для размещения над панелью ввода. */
    public Separator getInputSeparator() {
        return inputSep;
    }

    /** Очистить поле ввода и сбросить режим ответа. */
    public void clear() {
        messageInput.clear();
        cancelReply();
    }

    /** Включить/выключить панель ввода. */
    public void setInputEnabled(boolean enabled) {
        messageInput.setDisable(!enabled);
        sendButton.setDisable(!enabled
                || messageInput.getText() == null
                || messageInput.getText().trim().isEmpty());
    }

    /** Включить режим ответа на сообщение. */
    public void startReply(MeshMessage msg, String senderName) {
        if (msg == null || msg.getPacketId() == 0) {
            return;
        }
        replyToMessage = msg;

        String preview = msg.getText();
        if (preview != null && preview.length() > 80) {
            preview = preview.substring(0, 80) + "…";
        }
        replyQuoteLabel.setText(
                senderName + ": " + (preview != null ? preview : ""));

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
        int replyId = replyToMessage != null
                ? replyToMessage.getPacketId() : 0;
        onSend.accept(new SendRequest(text, replyId));
        messageInput.clear();
        cancelReply();
    }

    private void insertEmoji(String emoji) {
        String text = messageInput.getText() != null
                ? messageInput.getText() : "";
        int maxBytes = getMaxTextBytes();
        if (textByteLength(text) + textByteLength(emoji) > maxBytes) {
            return;
        }
        int caret = Math.min(savedCaretPosition, text.length());
        messageInput.insertText(caret, emoji);
        savedCaretPosition = caret + emoji.length();
        int newCaret = savedCaretPosition;
        Platform.runLater(() -> {
            messageInput.requestFocus();
            messageInput.positionCaret(newCaret);
        });
    }

    private void updateCharCount() {
        String text = messageInput.getText();
        int byteLen = text != null ? textByteLength(text) : 0;
        int maxBytes = getMaxTextBytes();
        charCountLabel.setText(byteLen + "/" + maxBytes);
        if (byteLen >= maxBytes) {
            if (!charCountLabel.getStyleClass().contains("chat-char-count-limit")) {
                charCountLabel.getStyleClass().add("chat-char-count-limit");
            }
        } else {
            charCountLabel.getStyleClass().remove("chat-char-count-limit");
        }
    }

    /** Длина строки в байтах UTF-8. */
    private static int textByteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Обрезает строку до указанного лимита байт UTF-8,
     * не разрезая многобайтовые символы.
     */
    private static String truncateToBytes(String text, int maxBytes) {
        int byteCount = 0;
        int end = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charLen = Character.charCount(codePoint);
            int byteLen = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (byteCount + byteLen > maxBytes) break;
            byteCount += byteLen;
            i += charLen;
            end = i;
        }
        return text.substring(0, end);
    }
}
