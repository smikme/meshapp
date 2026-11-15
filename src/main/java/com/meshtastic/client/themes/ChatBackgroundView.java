package com.meshtastic.client.themes;

import javafx.application.Platform;
import javafx.scene.CacheHint;
import javafx.scene.canvas.Canvas;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;

/**
 * Renders a stationary wallpaper behind a chat's scrolling message viewport.
 *
 * <p>The layer is mouse-transparent, clips artwork to its bounds and requests no
 * minimum or preferred size. Dimming and blur affect only the wallpaper, leaving
 * overlaid messages and controls unchanged. A separate CSS-backed region supplies
 * the current theme background while an image is loading or unavailable.
 *
 * <p>Image loading is asynchronous. Results from older configurations are ignored
 * after a chat or wallpaper change. Construct and update this view on the JavaFX
 * application thread.
 */
public final class ChatBackgroundView extends StackPane {
    private final Region fallback = new Region();
    private final Region base = new Region();
    private final Canvas pattern = new Canvas();
    private final StackPane artwork = new StackPane(fallback, base, pattern);
    private final Region shade = new Region();
    private final java.util.function.Function<String, java.util.concurrent.CompletableFuture<Image>> imageLoader;
    private ChatBackground config = ChatBackground.defaults();
    private Image image;
    private long generation;

    /** Creates a wallpaper layer using {@link BackgroundImageStore} for image loading. */
    public ChatBackgroundView() {
        this(BackgroundImageStore::load);
    }

    /**
     * Creates a wallpaper layer with an injectable asynchronous image loader.
     *
     * @param imageLoader non-null loader returning a non-null future; its image result
     *                    may be {@code null} to retain the theme background
     */
    ChatBackgroundView(java.util.function.Function<String, java.util.concurrent.CompletableFuture<Image>> imageLoader) {
        this.imageLoader = imageLoader;
        setMouseTransparent(true);
        setMinSize(0, 0);
        setPrefSize(0, 0);
        // CSS owns only the fallback. Giving the painted base the same class would let
        // a later CSS pass overwrite a user-selected color, gradient or image.
        fallback.getStyleClass().add("chat-wallpaper-base");
        pattern.setManaged(false);
        shade.setMouseTransparent(true);
        getChildren().addAll(artwork, shade);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        widthProperty().addListener(_ -> drawPattern());
        heightProperty().addListener(_ -> drawPattern());
        artwork.setCacheHint(CacheHint.QUALITY);
    }

    /**
     * Applies a wallpaper configuration without persisting it or rebuilding message rows.
     * Equal configurations are ignored. Image results are applied on the JavaFX thread
     * only if the configuration has not been superseded since loading began.
     *
     * @param next non-null normalized configuration to display
     */
    public void setConfiguration(ChatBackground next) {
        if (next.equals(config)) return;
        boolean sameImage = next.kind() == ChatBackground.Kind.IMAGE && config.kind() == next.kind()
                && next.imageId().equals(config.imageId());
        config = next;
        long request = ++generation;
        if (!sameImage) image = null;
        paint();
        if (config.kind() == ChatBackground.Kind.IMAGE && image == null) {
            imageLoader.apply(config.imageId()).thenAccept(loaded -> Platform.runLater(() -> {
                if (generation != request) return;
                image = loaded;
                paint();
            }));
        }
    }

    /**
     * Returns the most recently requested configuration, even while its image is loading.
     *
     * @return current immutable configuration; never {@code null}
     */
    public ChatBackground configuration() {
        return config;
    }

    private void paint() {
        // Solid fills, gradients and Canvas already render cheaply. Cache only the
        // blurred composite, and invalidate it whenever the editor changes its inputs.
        artwork.setCache(false);
        base.setBackground(null);
        switch (config.kind()) {
            case DEFAULT -> { }
            case COLOR, DOTS, GRID -> base.setBackground(Background.fill(Color.web(config.color())));
            case GRADIENT -> base.setBackground(Background.fill(new LinearGradient(0, 0, 1, 1,
                    true, CycleMethod.NO_CYCLE, new Stop(0, Color.web(config.color())),
                    new Stop(1, Color.web(config.secondColor())))));
            case IMAGE -> {
                if (image != null) base.setBackground(new Background(new BackgroundImage(image,
                        config.repeat() ? BackgroundRepeat.REPEAT : BackgroundRepeat.NO_REPEAT,
                        config.repeat() ? BackgroundRepeat.REPEAT : BackgroundRepeat.NO_REPEAT,
                        BackgroundPosition.CENTER, config.repeat() ? BackgroundSize.DEFAULT
                        : new BackgroundSize(100, 100, true, true, false, true))));
            }
        }
        artwork.setEffect(config.blur() > 0 ? new GaussianBlur(config.blur()) : null);
        artwork.setCache(config.blur() > 0);
        shade.setBackground(Background.fill(new Color(0, 0, 0, config.dim())));
        drawPattern();
    }

    private void drawPattern() {
        pattern.setWidth(getWidth());
        pattern.setHeight(getHeight());
        var g = pattern.getGraphicsContext2D();
        g.clearRect(0, 0, pattern.getWidth(), pattern.getHeight());
        if (config.kind() == ChatBackground.Kind.DOTS) {
            g.setFill(Color.rgb(30, 60, 70, .12));
            for (int x = 16; x < getWidth(); x += 28) {
                for (int y = 16; y < getHeight(); y += 28) g.fillOval(x, y, 3, 3);
            }
        } else if (config.kind() == ChatBackground.Kind.GRID) {
            g.setStroke(Color.rgb(30, 60, 70, .10));
            for (int x = 0; x < getWidth(); x += 32) g.strokeLine(x, 0, x, getHeight());
            for (int y = 0; y < getHeight(); y += 32) g.strokeLine(0, y, getWidth(), y);
        }
    }
}
