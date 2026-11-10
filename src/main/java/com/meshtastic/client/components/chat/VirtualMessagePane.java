package com.meshtastic.client.components.chat;

import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.utils.ChatHistoryTrace;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * A variable-height, pixel-anchored virtual viewport inside the existing ScrollPane.
 * Only visible bubbles and a small overscan are materialized. Unseen entries carry
 * geometry and message data, never a scene graph. Cell roots are recycled after
 * their previous content, bindings and image requests have been disposed.
 */
public final class VirtualMessagePane extends Pane {
    private static final double GAP = 6;
    private static final double OVERSCAN = 240;
    private static final int MAX_RECYCLED_ROWS = 24;

    public record Anchor(long dbId, double offset, boolean bottom) {}

    private static final class Entry {
        MeshMessage message;
        double top;
        double height;
        MessageBubbleFactory.RenderedMessageRow rendered;
        Entry(MeshMessage message, double height) { this.message = message; this.height = height; }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Node> accessories = new ArrayList<>();
    private final ArrayDeque<HBox> recycled = new ArrayDeque<>();
    private BiFunction<MeshMessage, HBox, MessageBubbleFactory.RenderedMessageRow> renderer;
    private Consumer<MessageBubbleFactory.RenderedMessageRow> disposer;
    private ScrollPane scrollPane;
    private Anchor pendingAnchor;
    private double contentHeight = 20;
    private double lastWidth = -1;
    private boolean adjusting;
    private boolean layingOut;

    public VirtualMessagePane() {
        setPadding(new Insets(10, 15, 10, 15));
        getStyleClass().add("chat-message-container");
    }

    public void configure(ScrollPane scrollPane,
                          BiFunction<MeshMessage, HBox, MessageBubbleFactory.RenderedMessageRow> renderer,
                          Consumer<MessageBubbleFactory.RenderedMessageRow> disposer) {
        this.scrollPane = scrollPane;
        this.renderer = renderer;
        this.disposer = disposer;
        scrollPane.vvalueProperty().addListener((_, oldValue, newValue) -> {
            if (adjusting) return;
            if (pendingAnchor != null) {
                // Preserve input received between inserting data and its layout.
                double delta = (newValue.doubleValue() - oldValue.doubleValue()) * extent();
                if (delta != 0) {
                    pendingAnchor = new Anchor(pendingAnchor.dbId(), pendingAnchor.offset() + delta, false);
                }
            }
            requestLayout();
        });
        scrollPane.viewportBoundsProperty().addListener((_, _, _) -> requestLayout());
    }

    public boolean isAdjustingViewport() { return adjusting; }
    public int renderedCount() { return (int) entries.stream().filter(e -> e.rendered != null).count(); }
    public boolean containsMessage(long dbId) { return entry(dbId) != null; }
    public double messageTop(long dbId) {
        Entry entry = entry(dbId);
        return entry == null ? Double.NaN : entry.top;
    }

    public Anchor captureAnchor() {
        if (entries.isEmpty()) return null;
        double offset = pixelOffset();
        for (Entry entry : entries) {
            if (entry.top + entry.height >= offset) {
                return new Anchor(entry.message.getDbId(), offset - entry.top, extent() == 0 || scrollPane.getVvalue() >= .99999);
            }
        }
        Entry last = entries.getLast();
        return new Anchor(last.message.getDbId(), offset - last.top, false);
    }

    /** Reconciles a whole page in one scene-graph update; preserves the current pixel anchor. */
    public void setMessages(List<MeshMessage> messages) {
        if (pendingAnchor == null) pendingAnchor = captureAnchor();
        Map<Long, Entry> byId = new HashMap<>();
        for (Entry entry : entries) if (entry.message.getDbId() > 0) byId.put(entry.message.getDbId(), entry);
        List<Entry> next = new ArrayList<>(messages.size());
        for (MeshMessage message : messages) {
            Entry existing = byId.get(message.getDbId());
            if (existing == null && message.getDbId() <= 0) {
                existing = entries.stream().filter(e -> e.message == message).findFirst().orElse(null);
            }
            if (existing == null) existing = new Entry(message, estimateHeight(message));
            existing.message = message;
            next.add(existing);
        }
        Set<Entry> retained = new HashSet<>(next);
        if (pendingAnchor != null && !pendingAnchor.bottom()
                && next.stream().noneMatch(e -> e.message.getDbId() == pendingAnchor.dbId())) {
            // If the anchor itself was deleted, keep the nearest surviving
            // neighbor at its previous screen coordinate.
            double offset = pixelOffset();
            Entry neighbor = entries.stream().filter(retained::contains)
                    .filter(e -> e.top + e.height >= offset).findFirst()
                    .orElseGet(() -> entries.stream().filter(retained::contains).reduce((a, b) -> b).orElse(null));
            if (neighbor != null) pendingAnchor = new Anchor(neighbor.message.getDbId(), offset - neighbor.top, false);
        }
        List<Node> removed = new ArrayList<>();
        for (Entry entry : entries) {
            if (!retained.contains(entry) && entry.rendered != null) removed.add(release(entry));
        }
        getChildren().removeAll(removed);
        entries.clear();
        entries.addAll(next);
        recalculateGeometry();
        requestLayout();
    }

    public void clearMessages() {
        for (Entry entry : entries) if (entry.rendered != null) release(entry);
        entries.clear();
        accessories.clear();
        getChildren().clear();
        pendingAnchor = null;
        contentHeight = 20;
        requestLayout();
    }

    public void addAccessory(Node node) {
        accessories.add(node);
        getChildren().add(node);
        requestLayout();
    }

    public void invalidateRows() {
        if (pendingAnchor == null) pendingAnchor = captureAnchor();
        List<Node> removed = new ArrayList<>();
        for (Entry entry : entries) if (entry.rendered != null) removed.add(release(entry));
        getChildren().removeAll(removed);
        requestLayout();
    }

    public void restoreAnchor(Anchor anchor) {
        if (anchor == null) return;
        pendingAnchor = anchor;
        requestLayout();
    }

    public void scrollToBottom() {
        pendingAnchor = new Anchor(0, 0, true);
        requestLayout();
    }

    public void scrollToMessage(long dbId, double offset) {
        restoreAnchor(new Anchor(dbId, offset, false));
    }

    private Entry entry(long dbId) {
        for (Entry entry : entries) if (entry.message.getDbId() == dbId) return entry;
        return null;
    }

    private Node release(Entry entry) {
        var rendered = entry.rendered;
        entry.rendered = null;
        HBox row = rendered.row();
        if (disposer != null) disposer.accept(rendered);
        if (recycled.size() < MAX_RECYCLED_ROWS) recycled.addLast(row);
        return row;
    }

    private double viewportHeight() {
        return scrollPane == null ? 0 : scrollPane.getViewportBounds().getHeight();
    }
    private double extent() { return Math.max(0, getHeight() - viewportHeight()); }
    private double pixelOffset() { return scrollPane == null ? 0 : scrollPane.getVvalue() * extent(); }

    private double offsetFor(Anchor anchor, double fallback) {
        if (anchor == null) return fallback;
        if (anchor.bottom()) return Math.max(0, contentHeight - viewportHeight());
        Entry entry = entry(anchor.dbId());
        return entry == null ? fallback : entry.top + anchor.offset();
    }

    private double estimateHeight(MeshMessage message) {
        String text = message.getText() == null ? "" : message.getText();
        double charsPerLine = Math.max(20, Math.max(getWidth(), 500) * .65 / 8);
        int lines = 0;
        for (String line : text.split("\n", -1)) lines += Math.max(1, (int) Math.ceil(line.length() / charsPerLine));
        return 48 + lines * 18 + (message.getReplyId() != 0 ? 28 : 0)
                + (message.getReactions().isEmpty() ? 0 : 28);
    }

    private void recalculateGeometry() {
        double height = getInsets().getTop() + getInsets().getBottom();
        for (Entry entry : entries) height += entry.height + GAP;
        for (Node accessory : accessories) height += accessory.prefHeight(innerWidth()) + GAP;
        if (!entries.isEmpty() || !accessories.isEmpty()) height -= GAP;
        contentHeight = height;
        double y = getInsets().getTop() + Math.max(0, viewportHeight() - height);
        for (Entry entry : entries) { entry.top = y; y += entry.height + GAP; }
    }

    private double innerWidth() { return Math.max(1, getWidth() - getInsets().getLeft() - getInsets().getRight()); }
    @Override protected double computeMinHeight(double width) { return contentHeight; }
    @Override protected double computePrefHeight(double width) { return contentHeight; }
    @Override protected double computeMinWidth(double height) { return 0; }
    @Override protected double computePrefWidth(double height) { return 500; }

    @Override public void resize(double width, double height) {
        boolean wasAdjusting = adjusting;
        if (!layingOut && pendingAnchor == null
                && (Math.abs(getWidth() - width) > .25 || Math.abs(getHeight() - height) > .25)) {
            pendingAnchor = captureAnchor();
        }
        adjusting = true;
        try {
            super.resize(width, height);
            // ScrollPaneSkin compensates its scrollbar when content bounds
            // change. That is layout bookkeeping, not a new user scroll delta.
            getLayoutBounds();
        } finally {
            adjusting = wasAdjusting;
        }
    }

    @Override protected void layoutChildren() {
        if (layingOut || renderer == null || viewportHeight() <= 0) return;
        layingOut = true;
        adjusting = true;
        try {
            Anchor anchor = pendingAnchor != null ? pendingAnchor : captureAnchor();
            double offset = pixelOffset();
            boolean widthChanged = Math.abs(lastWidth - getWidth()) > .5;
            lastWidth = getWidth();
            // Measuring a newly visible variable-height bubble can change the
            // estimated range. Refine it locally while keeping the same anchor.
            for (int pass = 0; pass < 3; pass++) {
                recalculateGeometry();
                offset = Math.clamp(offsetFor(anchor, offset), 0, Math.max(0, contentHeight - viewportHeight()));
                double first = Math.max(0, offset - OVERSCAN);
                double last = offset + viewportHeight() + OVERSCAN;
                List<Node> removed = new ArrayList<>();
                for (Entry entry : entries) {
                    if ((entry.top + entry.height < first || entry.top > last) && entry.rendered != null) {
                        removed.add(release(entry));
                    }
                }
                getChildren().removeAll(removed);
                List<Node> added = new ArrayList<>();
                try (var trace = ChatHistoryTrace.start("create cells")) {
                    for (Entry entry : entries) {
                        if (entry.top + entry.height < first || entry.top > last) continue;
                        if (entry.rendered == null) {
                            entry.rendered = renderer.apply(entry.message, recycled.pollFirst());
                            added.add(entry.rendered.row());
                        }
                    }
                }
                getChildren().addAll(added);
                boolean changed = false;
                try (var trace = ChatHistoryTrace.start("CSS and measure")) {
                    if (!added.isEmpty() || widthChanged) applyCss();
                    for (Entry entry : entries) {
                        if (entry.rendered == null) continue;
                        HBox row = entry.rendered.row();
                        double height = Math.max(1, row.prefHeight(innerWidth()));
                        if (Math.abs(entry.height - height) > .25) {
                            entry.height = height;
                            changed = true;
                        }
                    }
                }
                if (!changed) break;
            }
            recalculateGeometry();
            // ScrollPane fits short conversations to its viewport. Updating our
            // intrinsic height here makes the anchor correction part of this layout.
            double targetHeight = Math.max(contentHeight, viewportHeight());
            if (Math.abs(getHeight() - targetHeight) > .25) {
                resize(getWidth(), targetHeight);
                if (getParent() != null) getParent().requestLayout();
            }
            try (var trace = ChatHistoryTrace.start("layout cells")) {
                for (Entry entry : entries) {
                    if (entry.rendered == null) continue;
                    HBox row = entry.rendered.row();
                    row.resizeRelocate(getInsets().getLeft(), entry.top, innerWidth(), entry.height);
                }
            }
            double accessoryTop = entries.isEmpty() ? getInsets().getTop()
                    : entries.getLast().top + entries.getLast().height + GAP;
            for (Node accessory : accessories) {
                double height = accessory.prefHeight(innerWidth());
                accessory.resizeRelocate(getInsets().getLeft(), accessoryTop, innerWidth(), height);
                accessoryTop += height + GAP;
            }
            try (var trace = ChatHistoryTrace.start("restore anchor")) {
                offset = Math.clamp(offsetFor(anchor, offset), 0, extent());
                scrollPane.setVvalue(extent() == 0 ? 0 : offset / extent());
                pendingAnchor = null;
            }
        } finally {
            adjusting = false;
            layingOut = false;
        }
    }
}
