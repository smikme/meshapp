package com.meshtastic.client.terminal;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.Screen.RefreshType;
import com.meshtastic.client.logging.UiLogAppender;
import com.meshtastic.client.model.ConnectionEntry;
import com.meshtastic.client.model.DeviceState;
import com.meshtastic.client.model.LogEntry;
import com.meshtastic.client.model.MeshMessage;
import com.meshtastic.client.model.NodeData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.meshtastic.client.terminal.TerminalChannelFormatter.channelLabel;
import static com.meshtastic.client.terminal.TerminalDisplayFormatter.connectionSummary;
import static com.meshtastic.client.terminal.TerminalDisplayFormatter.displayDirectChatLabel;
import static com.meshtastic.client.terminal.TerminalDisplayFormatter.logbackEntryLines;
import static com.meshtastic.client.terminal.TerminalDisplayFormatter.messageBlockLines;
import static com.meshtastic.client.terminal.TerminalDisplayFormatter.previewText;
import static com.meshtastic.client.terminal.TerminalDisplayFormatter.safe;
import static com.meshtastic.client.terminal.TerminalLayout.clamp;
import static com.meshtastic.client.terminal.TerminalLayout.inputTopRow;
import static com.meshtastic.client.terminal.TerminalLayout.leftPaneWidth;
import static com.meshtastic.client.terminal.TerminalLayout.tail;
import static com.meshtastic.client.terminal.TerminalScreenWriter.putChar;
import static com.meshtastic.client.terminal.TerminalScreenWriter.putString;
import static com.meshtastic.client.terminal.TerminalText.fit;
import static com.meshtastic.client.terminal.TerminalText.padRight;

/**
 * Draws the terminal UI from a state snapshot.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
final class TerminalRenderer {

    private TerminalRenderState state;
    private int selectedConnectionIndex;
    private int selectedMessageIndex;
    private int messageTopIndex;
    private int lastVisibleMessageCount;

    TerminalRenderResult draw(Screen screen, TerminalRenderState nextState) throws IOException {
        state = nextState;
        selectedConnectionIndex = state.selectedConnectionIndex();
        selectedMessageIndex = state.selectedMessageIndex();
        messageTopIndex = state.messageTopIndex();
        lastVisibleMessageCount = 1;

        TerminalSize size = screen.doResizeIfNecessary();
        if (size == null) {
            size = screen.getTerminalSize();
        }
        screen.clear();
        TextGraphics g = screen.newTextGraphics();
        drawFrame(g, size);
        if (state.showHelp()) {
            drawHelp(g, size);
        } else {
            drawLeftPane(g, size);
            drawDetail(g, size);
        }
        drawInputLine(screen, g, size);
        screen.refresh(RefreshType.DELTA);

        return new TerminalRenderResult(
                selectedConnectionIndex,
                selectedMessageIndex,
                messageTopIndex,
                lastVisibleMessageCount);
    }

    private void drawFrame(TextGraphics g, TerminalSize size) {
        int width = size.getColumns();
        g.setBackgroundColor(TextColor.ANSI.BLUE);
        g.setForegroundColor(TextColor.ANSI.WHITE);
        putString(g, 0, 0, padRight(" MeshApp Terminal", width), SGR.BOLD);
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        g.setForegroundColor(TextColor.ANSI.WHITE);
        int leftWidth = leftPaneWidth(size);
        for (int y = 1; y < inputTopRow(size); y++) {
            putChar(g, leftWidth, y, '|');
        }
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        putString(g, 1, Math.max(1, inputTopRow(size) - 1),
                fit("Tab panel | PgUp/PgDn history | Enter reply/send | r reply | / command | ? help | q quit",
                        Math.max(0, width - 2)));
    }

    private void drawLeftPane(TextGraphics g, TerminalSize size) {
        if (state.connectedView()) {
            drawChats(g, size);
        } else {
            drawConnections(g, size);
        }
    }

    private void drawConnections(TextGraphics g, TerminalSize size) {
        int leftWidth = leftPaneWidth(size);
        List<ConnectionEntry> entries = state.connections();
        selectedConnectionIndex = clamp(selectedConnectionIndex, 0, Math.max(0, entries.size() - 1));

        drawPanelTitle(g, 1, 2, "Connections", FocusPane.CONNECTIONS);
        if (entries.isEmpty()) {
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            putString(g, 1, 4, fit("No profiles", leftWidth - 2));
            return;
        }

        int y = 4;
        for (int i = 0; i < entries.size() && y < inputTopRow(size) - 8; i++) {
            ConnectionEntry entry = entries.get(i);
            boolean selected = i == selectedConnectionIndex;
            if (selected) {
                g.setBackgroundColor(TextColor.ANSI.WHITE);
                g.setForegroundColor(TextColor.ANSI.BLACK);
            } else if (entry.isConnected()) {
                g.setForegroundColor(TextColor.ANSI.GREEN);
            } else if (entry.isReconnecting()) {
                g.setForegroundColor(TextColor.ANSI.YELLOW);
            } else {
                g.setForegroundColor(TextColor.ANSI.WHITE);
            }
            putString(g, 1, y++, padRight((i + 1) + ". " + safe(entry.getName()), leftWidth - 2));
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            putString(g, 3, y++, fit(connectionSummary(entry), leftWidth - 4));
        }

        y = Math.max(y + 1, inputTopRow(size) - 7);
        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, 1, y++, "Chat");
        g.setForegroundColor(TextColor.ANSI.WHITE);
        String chat = state.selectedDmPeer() == null
                ? channelLabel(state.boundState(), state.selectedChannelIndex())
                : "dm " + displayDirectChatLabel(state.boundState(), state.selectedDmPeer());
        putString(g, 1, y++, fit(chat, leftWidth - 2));

        drawNodeList(g, size, leftWidth, y);
    }

    private void drawChats(TextGraphics g, TerminalSize size) {
        int leftWidth = leftPaneWidth(size);
        int y = 2;

        drawPanelTitle(g, 1, y++, "Chats", FocusPane.CHATS);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        if (state.activeConnectionEntry() != null) {
            putString(g, 1, y++, fit("on " + safe(state.activeConnectionEntry().getName()), leftWidth - 2));
        }
        y++;

        if (state.chatItems().isEmpty()) {
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            putString(g, 1, y++, fit("Waiting for channels", leftWidth - 2));
        }

        for (int i = 0; i < state.chatItems().size() && y < inputTopRow(size) - 8; i++) {
            TerminalChat chat = state.chatItems().get(i);
            boolean selected = i == state.selectedChatIndex();
            if (selected) {
                g.setBackgroundColor(TextColor.ANSI.WHITE);
                g.setForegroundColor(TextColor.ANSI.BLACK);
            } else {
                g.setForegroundColor(TextColor.ANSI.WHITE);
            }
            String unread = chat.unreadCount() > 0 ? " (" + chat.unreadCount() + ")" : "";
            putString(g, 1, y++, padRight(chat.menuLabel() + unread, leftWidth - 2));
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            putString(g, 3, y++, fit(chat.description(), leftWidth - 4));
        }

        y = Math.max(y + 1, inputTopRow(size) - 7);
        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, 1, y++, "Selected");
        g.setForegroundColor(TextColor.ANSI.WHITE);
        putString(g, 1, y++, fit(currentChatLabel(), leftWidth - 2));

        drawNodeList(g, size, leftWidth, y);
    }

    private void drawNodeList(TextGraphics g, TerminalSize size, int leftWidth, int y) {
        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, 1, y++, "Nodes");
        g.setForegroundColor(TextColor.ANSI.WHITE);
        for (String line : nodeLines(Math.max(0, inputTopRow(size) - y - 1))) {
            putString(g, 1, y++, fit(line, leftWidth - 2));
        }
    }

    private void drawDetail(TextGraphics g, TerminalSize size) {
        int leftWidth = leftPaneWidth(size);
        int x = leftWidth + 2;
        int width = Math.max(10, size.getColumns() - x - 1);
        int y = 2;

        if (state.showLogbackLog()) {
            drawLogbackDetail(g, size, x, width, y);
            return;
        }

        drawPanelTitle(g, x, y++, "Messages", FocusPane.MESSAGES);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        putString(g, x, y++, fit(detailSubtitle(), width));

        drawMessageHistory(g, x, y, width, Math.max(0, inputTopRow(size) - y - 1));
    }

    private void drawLogbackDetail(TextGraphics g, TerminalSize size, int x, int width, int y) {
        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, x, y++, "Logback", SGR.BOLD);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        putString(g, x, y++, fit("events=" + UiLogAppender.getBuffer().size(), width));

        List<String> recent = logbackLines(Math.max(0, inputTopRow(size) - y - 1), width);
        for (String line : recent) {
            putString(g, x, y++, fit(line, width));
        }
    }

    private void drawHelp(TextGraphics g, TerminalSize size) {
        int x = 2;
        int y = 2;
        int width = Math.max(10, size.getColumns() - 4);
        g.setForegroundColor(TextColor.ANSI.CYAN);
        putString(g, x, y++, "Help", SGR.BOLD);
        g.setForegroundColor(TextColor.ANSI.WHITE);
        for (String line : List.of(
                "Keys:",
                "  Tab / Shift-Tab      switch panel focus",
                "  Up/Down              navigate focused list or message selection",
                "  PgUp / PgDn          scroll chat history and load adjacent pages",
                "  Enter                connect, reply to selected message, or send input",
                "  r                    reply to selected message",
                "  Esc                  cancel reply/input focus/help",
                "  c / d                connect / disconnect selected profile",
                "  l                    toggle logback log",
                "  [ / ] or 0-9         select channel",
                "  /                    enter command mode outside input",
                "  ?                    show this help",
                "  q / Ctrl-C           quit",
                "",
                "Commands:",
                "  connect [n]          connect selected or numbered profile",
                "  disconnect           disconnect selected profile",
                "  channel <index>      select channel chat",
                "  dm <nodeId>          select direct message peer",
                "  send <text>          send to selected chat",
                "  nodes                append node summary to status buffer",
                "  quit                 exit terminal mode")) {
            if (y < inputTopRow(size) - 1) {
                putString(g, x, y++, fit(line, width));
            }
        }
    }

    private void drawInputLine(Screen screen, TextGraphics g, TerminalSize size) {
        int replyRow = size.getRows() - 2;
        int row = size.getRows() - 1;
        int width = size.getColumns();

        g.setBackgroundColor(TextColor.ANSI.BLACK);
        g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
        String latestActivity = state.activity().isEmpty() ? "" : state.activity().get(state.activity().size() - 1);
        String replyText = state.replyToMessage() != null
                ? "reply to " + previewText(state.replyToMessage().getText(), Math.max(10, width - 10))
                : latestActivity;
        putString(g, 0, replyRow, padRight(replyText, width));

        g.setBackgroundColor(TextColor.ANSI.BLACK);
        g.setForegroundColor(state.commandMode()
                ? TextColor.ANSI.YELLOW
                : state.activePane() == FocusPane.INPUT ? TextColor.ANSI.GREEN : TextColor.ANSI.WHITE);
        if (state.commandMode()) {
            String prompt = ":" + state.commandBuffer();
            putString(g, 0, row, padRight(prompt, width));
            int cursor = Math.min(width - 1, TerminalText.displayWidth(":" + state.commandBuffer()));
            screen.setCursorPosition(new TerminalPosition(cursor, row));
        } else {
            String counter = inputCounterText();
            String prompt = state.inputEnabled() ? "> " : "> [no active connection] ";
            int textWidth = Math.max(0, width - TerminalText.displayWidth(prompt) - TerminalText.displayWidth(counter) - 1);
            String visibleInput = fit(state.inputText(), textWidth);
            putString(g, 0, row, padRight(prompt + visibleInput, Math.max(0, width - TerminalText.displayWidth(counter))));
            g.setForegroundColor(inputOverLimit() ? TextColor.ANSI.RED : TextColor.ANSI.BLACK_BRIGHT);
            putString(g, Math.max(0, width - TerminalText.displayWidth(counter)), row, counter);
            if (state.activePane() == FocusPane.INPUT) {
                int cursor = TerminalText.displayWidth(prompt
                        + state.inputText().substring(0, Math.min(state.inputCaret(), state.inputText().length())));
                screen.setCursorPosition(new TerminalPosition(Math.min(width - 1, cursor), row));
            } else {
                screen.setCursorPosition(null);
            }
        }
        g.setBackgroundColor(TextColor.ANSI.DEFAULT);
    }

    private String inputCounterText() {
        return " " + state.inputByteLength() + "/" + state.maxInputBytes();
    }

    private boolean inputOverLimit() {
        return state.inputByteLength() > state.maxInputBytes();
    }

    private void drawPanelTitle(TextGraphics g, int x, int y, String title, FocusPane pane) {
        g.setForegroundColor(state.activePane() == pane ? TextColor.ANSI.YELLOW : TextColor.ANSI.CYAN);
        putString(g, x, y, title, SGR.BOLD);
    }

    private void drawMessageHistory(TextGraphics g, int x, int y, int width, int maxRows) {
        if (maxRows <= 0) {
            return;
        }
        if (state.loadedMessages().isEmpty()) {
            g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
            putString(g, x, y, fit(state.inputEnabled() ? "No messages yet" : "No active connection", width));
            return;
        }

        selectedMessageIndex = clamp(selectedMessageIndex, 0, state.loadedMessages().size() - 1);
        adjustMessageTopIndex(maxRows, width);

        int row = y;
        int visibleMessages = 0;
        for (int i = messageTopIndex; i < state.loadedMessages().size() && row < y + maxRows; i++) {
            MeshMessage msg = state.loadedMessages().get(i);
            List<String> lines = messageBlockLines(state.boundState(), msg, width - 2);
            boolean selected = i == selectedMessageIndex;
            for (String line : lines) {
                if (row >= y + maxRows) {
                    break;
                }
                if (selected) {
                    g.setBackgroundColor(TextColor.ANSI.WHITE);
                    g.setForegroundColor(TextColor.ANSI.BLACK);
                } else if (msg.isSystemMessage()) {
                    g.setForegroundColor(TextColor.ANSI.BLACK_BRIGHT);
                } else {
                    g.setForegroundColor(msg.isOutgoing() ? TextColor.ANSI.GREEN : TextColor.ANSI.WHITE);
                }
                putString(g, x, row++, padRight((selected ? "> " : "  ") + line, width));
                g.setBackgroundColor(TextColor.ANSI.DEFAULT);
            }
            visibleMessages++;
        }
        lastVisibleMessageCount = Math.max(1, visibleMessages);
    }

    private void adjustMessageTopIndex(int maxRows, int width) {
        messageTopIndex = clamp(messageTopIndex, 0, Math.max(0, state.loadedMessages().size() - 1));
        if (selectedMessageIndex < messageTopIndex) {
            messageTopIndex = selectedMessageIndex;
        }
        while (messageTopIndex < selectedMessageIndex
                && messageRows(messageTopIndex, selectedMessageIndex, width) > maxRows) {
            messageTopIndex++;
        }
        while (messageTopIndex > 0
                && messageRows(messageTopIndex - 1, selectedMessageIndex, width) <= maxRows) {
            messageTopIndex--;
        }
    }

    private int messageRows(int fromIndex, int toIndex, int width) {
        int rows = 0;
        for (int i = Math.max(0, fromIndex); i <= toIndex && i < state.loadedMessages().size(); i++) {
            rows += messageBlockLines(state.boundState(), state.loadedMessages().get(i), width - 2).size();
        }
        return rows;
    }

    private List<String> nodeLines(int maxLines) {
        DeviceState boundState = state.boundState();
        if (boundState == null || maxLines <= 0) {
            return List.of();
        }
        return boundState.getNodeDb().values().stream()
                .sorted(Comparator.comparingInt(NodeData::getLastHeard).reversed())
                .limit(maxLines)
                .map(TerminalDisplayFormatter::nodeLabel)
                .toList();
    }

    private String detailSubtitle() {
        ActiveConnection active = state.activeConnection();
        if (active == null) {
            return "No active connection";
        }
        return "connection=" + active.connectionId() + " chat=" + currentChatLabel();
    }

    private String currentChatLabel() {
        TerminalChat chat = selectedChat();
        if (chat != null) {
            return chat.label();
        }
        return state.selectedDmPeer() == null
                ? channelLabel(state.boundState(), state.selectedChannelIndex())
                : "dm " + displayDirectChatLabel(state.boundState(), state.selectedDmPeer());
    }

    private TerminalChat selectedChat() {
        if (state.chatItems().isEmpty()) {
            return null;
        }
        int index = clamp(state.selectedChatIndex(), 0, state.chatItems().size() - 1);
        return state.chatItems().get(index);
    }

    private List<String> logbackLines(int maxLines, int width) {
        if (maxLines <= 0) {
            return List.of();
        }
        List<LogEntry> entries = UiLogAppender.getBuffer();
        if (entries.isEmpty()) {
            return List.of("No logback events");
        }

        int from = Math.max(0, entries.size() - Math.max(20, maxLines));
        List<String> lines = new ArrayList<>();
        for (LogEntry entry : entries.subList(from, entries.size())) {
            lines.addAll(logbackEntryLines(entry, width));
        }
        return tail(lines, maxLines);
    }
}
