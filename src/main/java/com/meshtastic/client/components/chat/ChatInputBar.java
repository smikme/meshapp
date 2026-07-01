package com.meshtastic.client.components.chat;

import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.components.EmojiPicker;
import com.meshtastic.client.components.EmojiRenderingSupport;
import com.meshtastic.client.i18n.I18n;
import com.meshtastic.client.modal.Toast;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.service.MeshFilesUploadService;
import com.meshtastic.client.utils.UnicodeTextUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Chat input panel: emoji button, text field, byte counter, send button, and reply bar.
 *
 * <p>Extends VBox and contains replyBar plus inputBar.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public class ChatInputBar extends VBox {

    /**
     * Maximum serialized Data protobuf size from mesh.proto DATA_PAYLOAD_LEN.
     * Protocol overhead inside Data is portnum (2 bytes) plus payload tag and
     * length (3 bytes), for 5 bytes total. Reply mode adds reply_id: tag
     * (1 byte) plus fixed32 (4 bytes), another 5 bytes.
     */
    private static final int DATA_PAYLOAD_LEN = 233;
    private static final int PROTO_OVERHEAD = 5;
    private static final int REPLY_ID_OVERHEAD = 5;
    private static final int MAX_COMMAND_SUGGESTIONS = 8;
    private static final String SELECTED_SUGGESTION_STYLE_CLASS = "chat-command-suggestion-btn-selected";
    private static final double IMAGE_ICON_SIZE = 22;
    private static final double ATTACHMENT_PREVIEW_WIDTH = 156;
    private static final double ATTACHMENT_PREVIEW_HEIGHT = 104;
    private static final List<String> SUPPORTED_IMAGE_EXTENSIONS = List.of(
            ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".tif", ".tiff");

    /** Data passed to the send callback. */
    public record SendRequest(String text, int replyId) {}

    /** State for an inline node-pick request from the Lua UI API. */
    private record ActiveNodePick(Consumer<ChatBotCommandHelper.NodeSuggestion> onSelected,
                                  Runnable onCancelled) {}

    /**
     * Result of inserting an uploaded image URL into the input model.
     *
     * @param text full input text after insertion
     * @param caretPosition caret position immediately after the protected URL block
     * @param protectedBlock substring that must remain intact until explicit image removal
     */
    private record ImageUrlInsertion(String text, int caretPosition, String protectedBlock) {}

    private final Consumer<SendRequest> onSend;
    private final Predicate<ChatBotCommandHelper.ParsedBotCommand> onBotCommand;
    private final Function<String, List<ChatBotCommandHelper.BotDefinition>> botSuggestionProvider;
    private final Function<String, List<ChatBotCommandHelper.NodeSuggestion>> nodeSuggestionProvider;
    private final Consumer<MeshFilesImage> onOpenImage;
    private final MeshFilesUploadService meshFilesUploadService = MeshFilesUploadService.getInstance();
    private final EmojiTextField messageInput;
    private final SendButtonWithRing sendRing;
    private final EmojiPicker emojiPicker;
    private final StackPane inputStack;
    private final VBox commandSuggestionRoot;
    private final Button imageBtn;
    private final HBox imagePreviewBar;
    private final ImageView imagePreview;
    private final Label imagePreviewStatus;
    private final Button clearImagePreviewBtn;

    private final HBox replyBar;
    private final Label replyQuoteLabel;
    private final Separator inputSep;

    private MeshMessage replyToMessage;
    private MeshFilesImage attachedImage;
    private String protectedImageBlock;
    private int protectedImageCaretPosition;
    private int savedCaretPosition;
    private int selectedSuggestionIndex = -1;
    private ActiveNodePick activeNodePick;
    private boolean imageUploadInProgress;
    private boolean inputEnabled = true;
    private long imageUploadGeneration;
    private boolean restoringProtectedImageText;

    /**
     * @param onSend message-send callback carrying text and replyId
     */
    public ChatInputBar(Consumer<SendRequest> onSend,
                        Predicate<ChatBotCommandHelper.ParsedBotCommand> onBotCommand,
                        Function<String, List<ChatBotCommandHelper.BotDefinition>> botSuggestionProvider,
                        Function<String, List<ChatBotCommandHelper.NodeSuggestion>> nodeSuggestionProvider) {
        this(onSend, onBotCommand, botSuggestionProvider, nodeSuggestionProvider, null);
    }

    /**
     * Creates a chat input bar.
     *
     * @param onSend message-send callback carrying text and replyId
     * @param onBotCommand callback for parsed bot commands; returns {@code true} when handled
     * @param botSuggestionProvider provider for bot command suggestions
     * @param nodeSuggestionProvider provider for node suggestions in bot command arguments
     * @param onOpenImage callback for opening uploaded image previews; external URL fallback is used when absent
     */
    public ChatInputBar(Consumer<SendRequest> onSend,
                        Predicate<ChatBotCommandHelper.ParsedBotCommand> onBotCommand,
                        Function<String, List<ChatBotCommandHelper.BotDefinition>> botSuggestionProvider,
                        Function<String, List<ChatBotCommandHelper.NodeSuggestion>> nodeSuggestionProvider,
                        Consumer<MeshFilesImage> onOpenImage) {
        this.onSend = onSend;
        this.onBotCommand = onBotCommand;
        this.onOpenImage = onOpenImage;
        this.botSuggestionProvider = botSuggestionProvider != null
                ? botSuggestionProvider
                : ChatBotCommandHelper::suggestBots;
        this.nodeSuggestionProvider = nodeSuggestionProvider;
        getStyleClass().add("chat-input-wrapper");
        setMaxHeight(Region.USE_PREF_SIZE);
        addEventFilter(DragEvent.DRAG_OVER, this::handleImageDragOver);
        addEventFilter(DragEvent.DRAG_DROPPED, this::handleImageDragDropped);

        // Divider
        inputSep = new Separator();
        inputSep.getStyleClass().add("chat-input-separator");

        // Emoji button, using an image sized to match the send button.
        Button emojiBtn = new Button();
        ImageView emojiBtnIcon = EmojiImageCache.createImageView("\uD83D\uDE00", 22);
        if (emojiBtnIcon != null) {
            emojiBtn.setGraphic(emojiBtnIcon);
        } else {
            emojiBtn.setText("\uD83D\uDE00");
        }
        emojiBtn.getStyleClass().add("chat-emoji-btn");
        emojiBtn.setTooltip(new Tooltip(I18n.t("chat.emoji")));
        emojiPicker = new EmojiPicker(this::insertEmoji);
        emojiBtn.setOnAction(e -> emojiPicker.toggle(emojiBtn));

        imageBtn = createImageButton();

        commandSuggestionRoot = new VBox(2);
        commandSuggestionRoot.getStyleClass().add("chat-command-popup");
        commandSuggestionRoot.setVisible(false);
        commandSuggestionRoot.setManaged(false);

        // Custom input field with inline emoji images.
        messageInput = new EmojiTextField();
        messageInput.setPromptText(defaultPromptText());
        messageInput.setMaxBytesSupplier(this::getMaxTextBytes);
        HBox.setHgrow(messageInput, Priority.ALWAYS);

        // Send button with a circular fill indicator.
        sendRing = new SendButtonWithRing(this::doSend);

        messageInput.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                savedCaretPosition = messageInput.getCaretPosition();
                Platform.runLater(() -> {
                    if (activeNodePick != null && !messageInput.isFocused() && !commandSuggestionRoot.isHover()) {
                        cancelActiveNodePick(true);
                    }
                });
            }
            Platform.runLater(this::refreshCommandSuggestions);
        });

        messageInput.textProperty().addListener((obs, oldVal, newVal) -> {
            if (restoringProtectedImageText) {
                refreshInputState(newVal);
                return;
            }
            if (newVal != null && textByteLength(newVal) > getMaxTextBytes()) {
                // Revert to the previous text as a whole instead of trimming the
                // end; otherwise inserting in the middle appears to work by
                // deleting the final character.
                messageInput.setText(oldVal != null ? oldVal : "");
                return;
            }
            if (!isProtectedImageTextIntact(newVal)) {
                restoreProtectedImageText(oldVal);
                return;
            }
            refreshInputState(newVal);
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

        HBox inputBar = new HBox(8, emojiBtn, imageBtn, messageInput, sendRing);
        inputBar.setAlignment(Pos.CENTER_LEFT);
        inputBar.setPadding(new Insets(8, 15, 8, 15));
        inputBar.getStyleClass().add("chat-input-bar");
        inputBar.setMaxHeight(Region.USE_PREF_SIZE);

        inputStack = new StackPane(inputBar, commandSuggestionRoot);
        inputStack.setMaxWidth(Double.MAX_VALUE);
        inputStack.setMaxHeight(Region.USE_PREF_SIZE);
        inputStack.setAlignment(Pos.BOTTOM_LEFT);
        StackPane.setAlignment(inputBar, Pos.BOTTOM_LEFT);
        StackPane.setAlignment(commandSuggestionRoot, Pos.BOTTOM_LEFT);

        // Reply bar, hidden by default.
        Label replyIcon = new Label("↩");
        replyIcon.getStyleClass().add("chat-reply-icon");

        replyQuoteLabel = new Label();
        replyQuoteLabel.getStyleClass().add("chat-reply-quote");
        replyQuoteLabel.setMaxWidth(Double.MAX_VALUE);
        replyQuoteLabel.setMaxHeight(Region.USE_PREF_SIZE);
        replyQuoteLabel.setWrapText(false);
        EmojiRenderingSupport.disableFor(replyQuoteLabel);
        HBox.setHgrow(replyQuoteLabel, Priority.ALWAYS);

        Button cancelReplyBtn = new Button("✕");
        cancelReplyBtn.getStyleClass().add("chat-reply-cancel");
        cancelReplyBtn.setTooltip(new Tooltip(I18n.t("chat.input.cancelReply")));
        cancelReplyBtn.setOnAction(e -> cancelReply());

        replyBar = new HBox(8, replyIcon, replyQuoteLabel, cancelReplyBtn);
        replyBar.setAlignment(Pos.CENTER_LEFT);
        replyBar.setPadding(new Insets(6, 15, 6, 15));
        replyBar.getStyleClass().add("chat-reply-bar");
        replyBar.setFillHeight(false);
        replyBar.setMaxHeight(Region.USE_PREF_SIZE);
        replyBar.setVisible(false);
        replyBar.setManaged(false);

        imagePreview = new ImageView();
        imagePreview.setPreserveRatio(true);
        imagePreview.setSmooth(true);
        imagePreview.setFitWidth(ATTACHMENT_PREVIEW_WIDTH);
        imagePreview.setFitHeight(ATTACHMENT_PREVIEW_HEIGHT);
        imagePreview.getStyleClass().add("chat-input-image-preview");
        imagePreview.setCursor(javafx.scene.Cursor.HAND);
        imagePreview.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || attachedImage == null) {
                return;
            }
            openAttachedImage();
            event.consume();
        });

        imagePreviewStatus = new Label();
        imagePreviewStatus.getStyleClass().add("chat-input-image-status");
        HBox.setHgrow(imagePreviewStatus, Priority.ALWAYS);

        clearImagePreviewBtn = new Button("✕");
        clearImagePreviewBtn.getStyleClass().add("chat-reply-cancel");
        clearImagePreviewBtn.setTooltip(new Tooltip(I18n.t("chat.image.clear")));
        clearImagePreviewBtn.setOnAction(e -> clearAttachedImage(true));

        imagePreviewBar = new HBox(8, imagePreview, imagePreviewStatus, clearImagePreviewBtn);
        imagePreviewBar.setAlignment(Pos.CENTER_LEFT);
        imagePreviewBar.setPadding(new Insets(6, 15, 6, 15));
        imagePreviewBar.getStyleClass().add("chat-input-image-bar");
        imagePreviewBar.setVisible(false);
        imagePreviewBar.setManaged(false);

        getChildren().addAll(replyBar, imagePreviewBar, inputStack);
    }

    /** Returns the separator placed above the input panel. */
    public Separator getInputSeparator() {
        return inputSep;
    }

    /** Clears the input field and exits reply mode. */
    public void clear() {
        cancelActiveNodePick(true);
        hideCommandSuggestions();
        clearAttachedImage(false);
        messageInput.clear();
        cancelReply();
    }

    /** Enables or disables the input panel. */
    public void setInputEnabled(boolean enabled) {
        inputEnabled = enabled;
        messageInput.setFieldDisabled(!enabled);
        sendRing.setSendDisable(!enabled
                || messageInput.getText().trim().isEmpty());
        updateImageButtonState();
        if (!enabled) {
            cancelActiveNodePick(true);
            hideCommandSuggestions();
        }
    }

    /**
     * Starts the legacy inline node picker directly in the chat input panel.
     * Used by the Lua API {@code mesh.ui.pick_node()}.
     */
    public void startNodePick(String query,
                              String prompt,
                              Consumer<ChatBotCommandHelper.NodeSuggestion> onSelected,
                              Runnable onCancelled) {
        cancelActiveNodePick(true);
        activeNodePick = new ActiveNodePick(onSelected, onCancelled);
        updateImageButtonState();
        hideCommandSuggestions();
        clearAttachedImage(false);
        cancelReply();

        String initialQuery = query != null ? query : "";
        messageInput.setPromptText(prompt != null && !prompt.isBlank() ? prompt : nodePickPromptText());
        messageInput.setText(initialQuery);
        savedCaretPosition = initialQuery.length();
        messageInput.requestFocus();
        messageInput.positionCaret(savedCaretPosition);
        Platform.runLater(this::refreshCommandSuggestions);
    }

    /** Enters reply mode for a message. */
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

    /** Cancels reply mode. */
    public void cancelReply() {
        replyToMessage = null;
        replyBar.setVisible(false);
        replyBar.setManaged(false);
        replyQuoteLabel.setText("");
        updateCharCount();
    }

    /** Cancels a pending image upload without changing regular input text. */
    public void cancelPendingImageUpload() {
        if (imageUploadInProgress) {
            clearAttachedImage(false);
        }
    }

    /**
     * Returns whether the input can currently accept an image drop.
     *
     * <p>Drops are ignored while the input is disabled, while a previous upload
     * is in progress, or while the inline node picker owns the input field.
     *
     * @return {@code true} when image drag-and-drop may start an upload
     */
    public boolean canAcceptImageDrop() {
        return getScene() != null
                && isVisible()
                && inputEnabled
                && !imageUploadInProgress
                && activeNodePick == null;
    }

    /**
     * Uploads the first supported image file from a drag-and-drop payload.
     *
     * <p>If another image is already attached, it is explicitly removed from the
     * input text before the new upload starts.
     *
     * @param files files carried by a dragboard
     * @return {@code true} when a supported image file was accepted for upload
     */
    public boolean acceptDroppedImageFiles(List<File> files) {
        if (!canAcceptImageDrop()) {
            return false;
        }
        Optional<File> imageFile = firstSupportedImageFile(files);
        if (imageFile.isEmpty()) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.image.unsupported"));
            return false;
        }
        uploadSelectedImage(imageFile.get());
        return true;
    }

    /**
     * Checks whether a file name has an image extension accepted by the chat UI.
     *
     * <p>The upload service still validates magic bytes before sending data to
     * MeshFiles; this check is only the UI/drop filter.
     *
     * @param file candidate local file
     * @return {@code true} when the extension is one of the supported image types
     */
    public static boolean isSupportedImageFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        String name = file.getName();
        if (name == null || name.isBlank()) {
            return false;
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        return SUPPORTED_IMAGE_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
    }

    /** Requests focus for the text field. */
    public void focusInput() {
        Platform.runLater(() -> messageInput.requestFocus());
    }

    // === Internal Methods ===

    private Button createImageButton() {
        Button button = new Button();
        button.setGraphic(createImageIcon());
        button.getStyleClass().addAll("chat-emoji-btn", "chat-image-btn");
        button.setTooltip(new Tooltip(I18n.t("chat.image.attach")));
        button.setOnAction(e -> chooseAndUploadImage());
        return button;
    }

    private static Node createImageIcon() {
        double size = IMAGE_ICON_SIZE;
        Pane icon = new Pane();
        icon.getStyleClass().add("chat-image-icon");
        icon.setMinSize(size, size);
        icon.setPrefSize(size, size);
        icon.setMaxSize(size, size);
        icon.setMouseTransparent(true);

        Rectangle background = new Rectangle(0, 0, size, size);
        background.setArcWidth(5);
        background.setArcHeight(5);
        background.getStyleClass().add("chat-image-icon-bg");

        Circle sun = new Circle(size * 0.72, size * 0.31, size * 0.13);
        sun.getStyleClass().add("chat-image-icon-sun");

        Polygon backMountain = new Polygon(
                size * 0.12, size * 0.80,
                size * 0.43, size * 0.47,
                size * 0.70, size * 0.80);
        backMountain.getStyleClass().add("chat-image-icon-mountain-back");

        Polygon frontMountain = new Polygon(
                size * 0.33, size * 0.80,
                size * 0.61, size * 0.56,
                size * 0.90, size * 0.80);
        frontMountain.getStyleClass().add("chat-image-icon-mountain-front");

        Rectangle frame = new Rectangle(1.25, 1.25, size - 2.5, size - 2.5);
        frame.setArcWidth(5);
        frame.setArcHeight(5);
        frame.getStyleClass().add("chat-image-icon-frame");

        icon.getChildren().addAll(background, sun, backMountain, frontMountain, frame);
        return icon;
    }

    private void chooseAndUploadImage() {
        if (!inputEnabled || imageUploadInProgress || activeNodePick != null) {
            return;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.t("chat.image.chooseTitle"));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(
                        I18n.t("chat.image.files"),
                        "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp", "*.tif", "*.tiff"),
                new FileChooser.ExtensionFilter(I18n.t("chat.image.allFiles"), "*.*")
        );

        File selectedFile = chooser.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
        if (selectedFile == null) {
            return;
        }
        uploadSelectedImage(selectedFile);
    }

    private void uploadSelectedImage(File selectedFile) {
        if (!isSupportedImageFile(selectedFile)) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.image.unsupported"));
            return;
        }
        if (attachedImage != null || protectedImageBlock != null) {
            clearAttachedImage(true);
        }
        long generation = ++imageUploadGeneration;
        showImageUploadProgress(selectedFile.getName());
        meshFilesUploadService.upload(selectedFile.toPath())
                .whenComplete((image, failure) -> Platform.runLater(() -> {
                    if (generation != imageUploadGeneration) {
                        return;
                    }
                    imageUploadInProgress = false;
                    updateImageButtonState();
                    if (failure != null) {
                        hideImagePreviewBar();
                        Toast.show(Toast.Type.ERROR, I18n.t("chat.image.uploadFailed"));
                        return;
                    }
                    attachUploadedImage(image);
                }));
    }

    private void showImageUploadProgress(String fileName) {
        attachedImage = null;
        imageUploadInProgress = true;
        imagePreview.setImage(null);
        imagePreview.setVisible(false);
        imagePreview.setManaged(false);
        imagePreviewStatus.setText(I18n.t("chat.image.uploading", fileName != null ? fileName : ""));
        clearImagePreviewBtn.setVisible(false);
        clearImagePreviewBtn.setManaged(false);
        imagePreviewBar.setVisible(true);
        imagePreviewBar.setManaged(true);
        updateImageButtonState();
    }

    private void attachUploadedImage(MeshFilesImage image) {
        if (image == null || !insertImageUrl(image.url())) {
            hideImagePreviewBar();
            return;
        }

        attachedImage = image;
        Image previewImage = new Image(image.previewUrl(), true);
        imagePreview.setImage(previewImage);
        imagePreview.setVisible(true);
        imagePreview.setManaged(true);
        imagePreviewStatus.setText(I18n.t("chat.image.ready"));
        clearImagePreviewBtn.setVisible(true);
        clearImagePreviewBtn.setManaged(true);
        imagePreviewBar.setVisible(true);
        imagePreviewBar.setManaged(true);

        Platform.runLater(() -> {
            messageInput.requestFocus();
            messageInput.positionCaret(savedCaretPosition);
        });
    }

    private boolean insertImageUrl(String url) {
        String current = messageInput.getText();
        int rawCaret = messageInput.isFocused() ? messageInput.getCaretPosition() : savedCaretPosition;
        int caret = Math.min(Math.max(rawCaret, 0), current.length());
        ImageUrlInsertion insertion = imageUrlInsertion(current, caret, url);
        if (textByteLength(insertion.text()) > getMaxTextBytes()) {
            Toast.show(Toast.Type.WARNING, I18n.t("chat.image.tooLong"));
            return false;
        }

        protectedImageBlock = insertion.protectedBlock();
        protectedImageCaretPosition = insertion.caretPosition();
        messageInput.setText(insertion.text());
        savedCaretPosition = insertion.caretPosition();
        messageInput.positionCaret(savedCaretPosition);
        return true;
    }

    /**
     * Inserts a MeshFiles URL as a protected block with whitespace around it.
     *
     * <p>The trailing space keeps subsequently typed text from corrupting the URL.
     * The whole block is later restored on accidental edits and removed only by
     * explicit image removal.
     *
     * @param current current input text
     * @param caret requested insertion position
     * @param url original public image URL
     * @return insertion result with updated text and caret
     */
    private static ImageUrlInsertion imageUrlInsertion(String current, int caret, String url) {
        String safeCurrent = current != null ? current : "";
        int safeCaret = Math.min(Math.max(caret, 0), safeCurrent.length());
        String before = safeCurrent.substring(0, safeCaret);
        String after = safeCurrent.substring(safeCaret);

        String prefix = before.isEmpty() || Character.isWhitespace(before.charAt(before.length() - 1))
                ? ""
                : " ";
        String protectedBlock = prefix + url + " ";
        String newText = before + protectedBlock + after;
        return new ImageUrlInsertion(newText, before.length() + protectedBlock.length(), protectedBlock);
    }

    /**
     * Checks whether the uploaded-image URL block is still present.
     *
     * @param text current input text
     * @return {@code true} when there is no protected block or it is still intact
     */
    private boolean isProtectedImageTextIntact(String text) {
        return protectedImageBlock == null
                || protectedImageBlock.isEmpty()
                || (text != null && text.contains(protectedImageBlock));
    }

    /**
     * Restores the last valid input text after an edit attempts to remove the
     * protected uploaded-image URL block.
     *
     * @param oldText previous text value from the text property listener
     */
    private void restoreProtectedImageText(String oldText) {
        String restored = isProtectedImageTextIntact(oldText) ? oldText : messageInput.getText();
        if (!isProtectedImageTextIntact(restored)) {
            return;
        }
        restoringProtectedImageText = true;
        try {
            messageInput.setText(restored);
            int caret = Math.min(Math.max(protectedImageCaretPosition, 0), restored.length());
            savedCaretPosition = caret;
            messageInput.positionCaret(caret);
        } finally {
            restoringProtectedImageText = false;
        }
        refreshInputState(restored);
    }

    private void clearAttachedImage(boolean removeUrl) {
        imageUploadGeneration++;
        imageUploadInProgress = false;
        String imageUrl = attachedImage != null ? attachedImage.url() : null;
        String imageBlock = protectedImageBlock;
        attachedImage = null;
        protectedImageBlock = null;
        protectedImageCaretPosition = 0;
        if (removeUrl && imageUrl != null) {
            removeImageUrlFromInput(imageUrl, imageBlock);
        }
        hideImagePreviewBar();
        updateImageButtonState();
    }

    /**
     * Removes the uploaded-image URL from the input after explicit image removal.
     *
     * @param url original public image URL
     * @param protectedBlock protected block captured before attachment state reset
     */
    private void removeImageUrlFromInput(String url, String protectedBlock) {
        String current = messageInput.getText();
        int blockStart = protectedBlock != null && !protectedBlock.isEmpty()
                ? current.indexOf(protectedBlock)
                : -1;
        if (blockStart >= 0) {
            String newText = removeRangePreservingSpacing(
                    current,
                    blockStart,
                    blockStart + protectedBlock.length());
            messageInput.setText(newText);
            savedCaretPosition = Math.min(blockStart, newText.length());
            messageInput.positionCaret(savedCaretPosition);
            return;
        }

        int start = current.indexOf(url);
        if (start < 0) {
            return;
        }

        int removalStart = start;
        int removalEnd = start + url.length();
        if (removalEnd < current.length() && Character.isWhitespace(current.charAt(removalEnd))) {
            removalEnd++;
        }
        if (removalStart > 0 && Character.isWhitespace(current.charAt(removalStart - 1))) {
            removalStart--;
        }

        String newText = removeRangePreservingSpacing(current, removalStart, removalEnd);
        messageInput.setText(newText);
        savedCaretPosition = Math.min(removalStart, newText.length());
        messageInput.positionCaret(savedCaretPosition);
    }

    /**
     * Removes a text range and inserts one separator space only when surrounding
     * text would otherwise be joined into a single token.
     *
     * @param text source text
     * @param start inclusive removal start index
     * @param end exclusive removal end index
     * @return text with the range removed
     */
    private static String removeRangePreservingSpacing(String text, int start, int end) {
        String before = text.substring(0, Math.max(0, start));
        String after = text.substring(Math.min(text.length(), end));
        boolean needsSeparator = !before.isEmpty()
                && !after.isEmpty()
                && !Character.isWhitespace(before.charAt(before.length() - 1))
                && !Character.isWhitespace(after.charAt(0));
        return needsSeparator ? before + " " + after : before + after;
    }

    private void hideImagePreviewBar() {
        imagePreview.setImage(null);
        imagePreview.setVisible(false);
        imagePreview.setManaged(false);
        imagePreviewStatus.setText("");
        clearImagePreviewBtn.setVisible(false);
        clearImagePreviewBtn.setManaged(false);
        imagePreviewBar.setVisible(false);
        imagePreviewBar.setManaged(false);
    }

    private void openAttachedImage() {
        if (attachedImage == null) {
            return;
        }
        if (onOpenImage != null) {
            onOpenImage.accept(attachedImage);
            return;
        }
        com.meshtastic.client.utils.ExternalUrlLauncher.open(attachedImage.url());
    }

    private void updateImageButtonState() {
        if (imageBtn != null) {
            imageBtn.setDisable(!inputEnabled || imageUploadInProgress || activeNodePick != null);
        }
    }

    /**
     * Handles drag-over events on the input panel when a supported image file is present.
     *
     * @param event JavaFX drag event
     */
    private void handleImageDragOver(DragEvent event) {
        if (!canAcceptImageDrop()) {
            return;
        }
        Dragboard dragboard = event.getDragboard();
        if (dragboard.hasFiles() && firstSupportedImageFile(dragboard.getFiles()).isPresent()) {
            event.acceptTransferModes(TransferMode.COPY);
            event.consume();
        }
    }

    /**
     * Handles dropped image files on the input panel.
     *
     * @param event JavaFX drop event
     */
    private void handleImageDragDropped(DragEvent event) {
        Dragboard dragboard = event.getDragboard();
        boolean completed = dragboard.hasFiles() && acceptDroppedImageFiles(dragboard.getFiles());
        event.setDropCompleted(completed);
        if (completed) {
            event.consume();
        }
    }

    /**
     * Finds the first supported image file in a drag payload.
     *
     * @param files files from a dragboard
     * @return first accepted image file, if any
     */
    private static Optional<File> firstSupportedImageFile(List<File> files) {
        if (files == null || files.isEmpty()) {
            return Optional.empty();
        }
        return files.stream().filter(ChatInputBar::isSupportedImageFile).findFirst();
    }

    /** Maximum text byte length after protobuf overhead and reply mode are accounted for. */
    private int getMaxTextBytes() {
        int overhead = PROTO_OVERHEAD;
        if (replyToMessage != null) {
            overhead += REPLY_ID_OVERHEAD;
        }
        return DATA_PAYLOAD_LEN - overhead;
    }

    private void doSend() {
        if (activeNodePick != null) {
            return;
        }
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
                clearAttachedImage(false);
                messageInput.clear();
                cancelReply();
            }
            return;
        }

        hideCommandSuggestions();
        int replyId = replyToMessage != null
                ? replyToMessage.getPacketId() : 0;
        onSend.accept(new SendRequest(text, replyId));
        clearAttachedImage(false);
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

    private void refreshInputState(String text) {
        updateCharCount();
        sendRing.setSendDisable(text == null || text.trim().isEmpty());
        Platform.runLater(this::refreshCommandSuggestions);
    }

    /** Returns string length in UTF-8 bytes. */
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

        if (activeNodePick != null) {
            List<Button> rows = buildActiveNodePickRows();
            if (rows.isEmpty()) {
                hideCommandSuggestions();
                return;
            }
            commandSuggestionRoot.getChildren().setAll(rows);
            selectedSuggestionIndex = 0;
            updateSelectedSuggestionStyles();
            showCommandSuggestions();
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
                        () -> applyBotSuggestion(bot)))
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

    private List<Button> buildActiveNodePickRows() {
        List<ChatBotCommandHelper.NodeSuggestion> suggestions = nodeSuggestionProvider.apply(messageInput.getText());
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of();
        }
        return suggestions
                .stream()
                .limit(MAX_COMMAND_SUGGESTIONS)
                .map(suggestion -> buildSuggestionButton(
                        suggestion.primaryText(),
                        suggestion.secondaryText(),
                        () -> completeActiveNodePick(suggestion)))
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

    private void applyBotSuggestion(ChatBotCommandHelper.BotDefinition bot) {
        if (bot != null && bot.action() == ChatBotCommandHelper.BotAction.AUTOMATION) {
            hideCommandSuggestions();
            ChatBotCommandHelper.ParsedBotCommand command = new ChatBotCommandHelper.ParsedBotCommand(
                    bot.action(),
                    bot.handle(),
                    "",
                    false,
                    "",
                    List.of(),
                    bot.scriptId());
            if (onBotCommand != null && onBotCommand.test(command)) {
                clearAttachedImage(false);
                messageInput.clear();
                savedCaretPosition = 0;
                cancelReply();
                return;
            }
        }

        insertBotHandle(bot != null ? bot.handle() : "");
    }

    private void insertBotHandle(String handle) {
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

    private void completeActiveNodePick(ChatBotCommandHelper.NodeSuggestion suggestion) {
        ActiveNodePick pick = activeNodePick;
        if (pick == null) {
            return;
        }
        activeNodePick = null;
        updateImageButtonState();
        hideCommandSuggestions();
        messageInput.clear();
        messageInput.setPromptText(defaultPromptText());
        savedCaretPosition = 0;
        messageInput.requestFocus();
        if (pick.onSelected() != null) {
            pick.onSelected().accept(suggestion);
        }
    }

    private void cancelActiveNodePick(boolean notify) {
        ActiveNodePick pick = activeNodePick;
        if (pick == null) {
            return;
        }
        activeNodePick = null;
        updateImageButtonState();
        hideCommandSuggestions();
        messageInput.clear();
        messageInput.setPromptText(defaultPromptText());
        savedCaretPosition = 0;
        if (notify && pick.onCancelled() != null) {
            pick.onCancelled().run();
        }
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
        if (activeNodePick != null && event.getCode() == KeyCode.ESCAPE) {
            cancelActiveNodePick(true);
            return true;
        }
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

    private static String defaultPromptText() {
        return I18n.t("chat.input.message");
    }

    private static String nodePickPromptText() {
        return I18n.t("chat.input.nodePick");
    }

}
