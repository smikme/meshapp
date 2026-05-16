package com.meshtastic.client.components;

import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Labeled;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Глобальный JavaFX-hook для локального рендеринга Twemoji в стандартных {@link Labeled} controls.
 *
 * <p>JavaFX {@code Label}/{@code Button}/{@code ToggleButton} рисуют emoji через системный
 * fallback-шрифт. На системах без emoji-шрифта это даёт квадраты, поэтому controls с emoji-текстом
 * получают graphic на базе {@link EmojiTextFlow}; исходное text-property при этом сохраняется.
 */
public final class EmojiRenderingSupport {

    private static final String INSTALLED_KEY = EmojiRenderingSupport.class.getName() + ".installed";
    private static final String LABELED_STATE_KEY = EmojiRenderingSupport.class.getName() + ".labeledState";

    private EmojiRenderingSupport() {}

    public static void install(Scene scene) {
        if (scene == null) {
            return;
        }
        install(scene.getRoot());
        scene.rootProperty().addListener((obs, oldRoot, newRoot) -> install(newRoot));
    }

    private static void install(Node node) {
        if (node == null || Boolean.TRUE.equals(node.getProperties().put(INSTALLED_KEY, Boolean.TRUE))) {
            return;
        }

        if (node instanceof Labeled labeled) {
            installLabeled(labeled);
        }

        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                install(child);
            }
            parent.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) change -> {
                while (change.next()) {
                    if (change.wasAdded()) {
                        for (Node child : change.getAddedSubList()) {
                            install(child);
                        }
                    }
                }
            });
        }
    }

    private static void installLabeled(Labeled labeled) {
        LabeledState state = new LabeledState();
        labeled.getProperties().put(LABELED_STATE_KEY, state);

        labeled.textProperty().addListener((obs, oldText, newText) -> syncLabeled(labeled, state));
        labeled.fontProperty().addListener((obs, oldFont, newFont) -> syncLabeled(labeled, state));
        labeled.textFillProperty().addListener((obs, oldFill, newFill) -> syncLabeled(labeled, state));
        labeled.graphicProperty().addListener((obs, oldGraphic, newGraphic) -> {
            if (!state.updating && !state.overridden) {
                state.originalGraphic = newGraphic;
            }
        });
        labeled.contentDisplayProperty().addListener((obs, oldValue, newValue) -> {
            if (!state.updating && !state.overridden) {
                state.originalContentDisplay = newValue;
            }
        });

        syncLabeled(labeled, state);
    }

    private static void syncLabeled(Labeled labeled, LabeledState state) {
        if (state.updating) {
            return;
        }

        String text = labeled.getText();
        boolean needsEmojiRendering = containsLocalEmoji(text);
        state.updating = true;
        try {
            if (needsEmojiRendering) {
                applyEmojiGraphic(labeled, state, text);
            } else if (state.overridden) {
                restoreOriginalGraphic(labeled, state);
            }
        } finally {
            state.updating = false;
        }
    }

    private static boolean containsLocalEmoji(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (EmojiTextFlow.Segment segment : EmojiTextFlow.parseSegments(text)) {
            if (segment.isEmoji()) {
                return true;
            }
        }
        return false;
    }

    private static void applyEmojiGraphic(Labeled labeled, LabeledState state, String text) {
        if (!state.overridden) {
            state.originalGraphic = labeled.getGraphic();
            state.originalContentDisplay = labeled.getContentDisplay();
            state.overridden = true;
        } else {
            detachOriginalGraphic(state);
        }

        Node emojiContent = createEmojiContent(labeled, text, state);
        state.wrapper = createWrapper(state.originalGraphic, emojiContent, state.originalContentDisplay,
                labeled.getGraphicTextGap());
        if (state.originalGraphic != null) {
            labeled.setGraphic(null);
        }
        labeled.setGraphic(state.wrapper);
        labeled.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    private static Node createEmojiContent(Labeled labeled, String text, LabeledState state) {
        state.flow = null;

        String emoji = singleLocalEmoji(text);
        if (emoji != null) {
            ImageView imageView = EmojiImageCache.createImageView(emoji, emojiSize(labeled));
            if (imageView != null) {
                return imageView;
            }
        }

        EmojiTextFlow flow = new EmojiTextFlow(text, emojiSize(labeled));
        flow.setMouseTransparent(true);
        flow.setMinHeight(Region.USE_PREF_SIZE);
        applyFlowStyle(labeled, flow);
        state.flow = flow;
        return flow;
    }

    private static String singleLocalEmoji(String text) {
        List<EmojiTextFlow.Segment> segments = EmojiTextFlow.parseSegments(text);
        if (segments.size() == 1 && segments.getFirst().isEmoji()) {
            return segments.getFirst().text();
        }
        return null;
    }

    private static void applyFlowStyle(Labeled labeled, EmojiTextFlow flow) {
        flow.setTextFont(labeled.getFont());
        flow.setTextFill(labeled.getTextFill());
        flow.setTextStyleClasses(labeled.getStyleClass());
        flow.setEmojiSize(emojiSize(labeled));
    }

    private static double emojiSize(Labeled labeled) {
        double fontSize = labeled.getFont() == null ? 13 : labeled.getFont().getSize();
        return Math.max(12, fontSize * 1.25);
    }

    private static Node createWrapper(Node originalGraphic,
                                      Node emojiContent,
                                      ContentDisplay originalContentDisplay,
                                      double gap) {
        if (originalGraphic == null) {
            return emojiContent;
        }

        ContentDisplay display = originalContentDisplay == null ? ContentDisplay.LEFT : originalContentDisplay;
        if (display == ContentDisplay.TOP || display == ContentDisplay.BOTTOM) {
            VBox box = new VBox(Math.max(0, gap));
            box.setAlignment(Pos.CENTER);
            box.setMouseTransparent(true);
            addWrappedChildren(box, originalGraphic, emojiContent, display == ContentDisplay.BOTTOM);
            return box;
        }

        HBox box = new HBox(Math.max(0, gap));
        box.setAlignment(Pos.CENTER);
        box.setMouseTransparent(true);
        addWrappedChildren(box, originalGraphic, emojiContent, display == ContentDisplay.RIGHT);
        return box;
    }

    private static void addWrappedChildren(Pane wrapper,
                                           Node originalGraphic,
                                           Node emojiContent,
                                           boolean textFirst) {
        if (textFirst) {
            wrapper.getChildren().addAll(emojiContent, originalGraphic);
        } else {
            wrapper.getChildren().addAll(originalGraphic, emojiContent);
        }
    }

    private static void restoreOriginalGraphic(Labeled labeled, LabeledState state) {
        detachOriginalGraphic(state);
        labeled.setGraphic(state.originalGraphic);
        labeled.setContentDisplay(state.originalContentDisplay == null
                ? ContentDisplay.LEFT
                : state.originalContentDisplay);

        state.flow = null;
        state.wrapper = null;
        state.originalGraphic = null;
        state.originalContentDisplay = null;
        state.overridden = false;
    }

    private static void detachOriginalGraphic(LabeledState state) {
        if (state.wrapper instanceof Pane pane && state.originalGraphic != null) {
            pane.getChildren().remove(state.originalGraphic);
        }
    }

    private static final class LabeledState {
        private Node originalGraphic;
        private ContentDisplay originalContentDisplay;
        private EmojiTextFlow flow;
        private Node wrapper;
        private boolean overridden;
        private boolean updating;
    }
}
