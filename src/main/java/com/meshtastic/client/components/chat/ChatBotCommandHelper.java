package com.meshtastic.client.components.chat;

import com.meshtastic.client.model.NodeData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор команд встроенных чат-ботов и подготовка подсказок для autocomplete.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ChatBotCommandHelper {

    public static final String TRACEBOT_HANDLE = "@tracebot";
    public static final String INFOBOT_HANDLE = "@infobot";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\S+");
    private static final Pattern NODE_ID_PATTERN = Pattern.compile("!([0-9a-fA-F]{1,8})");
    private static final List<BotDefinition> BOTS = List.of(
            new BotDefinition(TRACEBOT_HANDLE, "Traceroute до ноды", BotAction.TRACEROUTE),
            new BotDefinition(INFOBOT_HANDLE, "Информация о ноде", BotAction.NODE_INFO)
    );

    private ChatBotCommandHelper() {}

    public enum BotAction {
        TRACEROUTE,
        NODE_INFO
    }

    public enum SuggestionMode {
        NONE,
        BOT,
        NODE
    }

    public enum NodeResolutionStatus {
        FOUND,
        NOT_FOUND,
        AMBIGUOUS
    }

    public record BotDefinition(String handle, String description, BotAction action) {}

    public record SuggestionContext(SuggestionMode mode,
                                    String query,
                                    int replacementStart,
                                    int replacementEnd,
                                    BotAction action) {

        public static SuggestionContext none() {
            return new SuggestionContext(SuggestionMode.NONE, "", 0, 0, null);
        }

        public boolean isVisible() {
            return mode != SuggestionMode.NONE;
        }
    }

    public record ParsedBotCommand(BotAction action,
                                   String botHandle,
                                   String targetToken,
                                   boolean hasExtraTokens) {

        public static ParsedBotCommand none() {
            return new ParsedBotCommand(null, "", "", false);
        }

        public boolean isCommand() {
            return action != null;
        }

        public boolean isReady() {
            return isCommand() && !isBlank(targetToken) && !hasExtraTokens;
        }
    }

    public record NodeSuggestion(String insertText, String primaryText, String secondaryText) {}

    public record NodeResolution(NodeResolutionStatus status, NodeData node) {

        public static NodeResolution found(NodeData node) {
            return new NodeResolution(NodeResolutionStatus.FOUND, node);
        }

        public static NodeResolution notFound() {
            return new NodeResolution(NodeResolutionStatus.NOT_FOUND, null);
        }

        public static NodeResolution ambiguous() {
            return new NodeResolution(NodeResolutionStatus.AMBIGUOUS, null);
        }
    }

    private record Token(String text, int start, int end) {}

    private record RankedNodeSuggestion(NodeSuggestion suggestion, int score, int lastHeard) {}

    public static List<BotDefinition> suggestBots(String rawQuery) {
        String query = normalizeBotQuery(rawQuery);
        return BOTS.stream()
                .filter(bot -> query.isEmpty()
                        || bot.handle().substring(1).startsWith(query)
                        || bot.handle().substring(1).contains(query))
                .toList();
    }

    public static SuggestionContext detectSuggestionContext(String text, int caretPosition) {
        String safeText = text != null ? text : "";
        int caret = Math.max(0, Math.min(caretPosition, safeText.length()));
        List<Token> tokens = tokenize(safeText);
        if (tokens.isEmpty()) {
            return SuggestionContext.none();
        }

        Token firstToken = tokens.getFirst();
        if (firstToken.start() != 0) {
            return SuggestionContext.none();
        }

        if (firstToken.text().startsWith("@") && caret <= firstToken.end()) {
            return new SuggestionContext(
                    SuggestionMode.BOT,
                    firstToken.text(),
                    firstToken.start(),
                    firstToken.end(),
                    null
            );
        }

        BotDefinition bot = findBot(firstToken.text());
        if (bot == null || tokens.size() > 2 || caret < firstToken.end()) {
            return SuggestionContext.none();
        }

        String query = tokens.size() == 2 ? tokens.get(1).text() : "";
        return new SuggestionContext(
                SuggestionMode.NODE,
                query,
                firstToken.end(),
                safeText.length(),
                bot.action()
        );
    }

    public static ParsedBotCommand parseCommand(String text) {
        String safeText = text != null ? text.trim() : "";
        if (safeText.isEmpty()) {
            return ParsedBotCommand.none();
        }

        List<Token> tokens = tokenize(safeText);
        if (tokens.isEmpty()) {
            return ParsedBotCommand.none();
        }

        BotDefinition bot = findBot(tokens.getFirst().text());
        if (bot == null) {
            return ParsedBotCommand.none();
        }

        String targetToken = tokens.size() >= 2 ? tokens.get(1).text() : "";
        return new ParsedBotCommand(bot.action(), bot.handle(), targetToken, tokens.size() > 2);
    }

    public static List<NodeSuggestion> suggestNodes(Collection<NodeData> rawNodes, String rawQuery, int limit) {
        String query = normalize(rawQuery);
        return uniqueNodes(rawNodes).stream()
                .map(node -> {
                    int score = nodeMatchScore(node, query);
                    if (score < 0) {
                        return null;
                    }
                    return new RankedNodeSuggestion(
                            new NodeSuggestion(
                                    formatNodeToken(node),
                                    displayName(node),
                                    secondaryText(node)
                            ),
                            score,
                            node.getLastHeard()
                    );
                })
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparingInt(RankedNodeSuggestion::score)
                        .thenComparing(RankedNodeSuggestion::lastHeard, Comparator.reverseOrder())
                        .thenComparing(rank -> rank.suggestion().primaryText(), String.CASE_INSENSITIVE_ORDER))
                .limit(Math.max(limit, 0))
                .map(RankedNodeSuggestion::suggestion)
                .toList();
    }

    public static NodeResolution resolveTarget(String rawTargetToken, Collection<NodeData> rawNodes) {
        if (isBlank(rawTargetToken)) {
            return NodeResolution.notFound();
        }

        List<NodeData> nodes = uniqueNodes(rawNodes);
        if (nodes.isEmpty()) {
            return NodeResolution.notFound();
        }

        String targetToken = rawTargetToken.trim();
        String nodeIdQuery = extractNodeIdQuery(targetToken);
        if (nodeIdQuery != null) {
            return matchByNodeId(nodes, nodeIdQuery);
        }

        List<NodeData> byName = nodes.stream()
                .filter(node -> matchesNodeName(node, targetToken))
                .toList();
        if (byName.size() == 1) {
            return NodeResolution.found(byName.getFirst());
        }
        if (byName.size() > 1) {
            return NodeResolution.ambiguous();
        }
        return NodeResolution.notFound();
    }

    public static String displayName(NodeData node) {
        if (node == null) {
            return "";
        }
        if (!isBlank(node.getLongName())) {
            return node.getLongName().trim();
        }
        if (!isBlank(node.getShortName())) {
            return node.getShortName().trim();
        }
        if (!isBlank(node.getNodeId())) {
            return node.getNodeId().trim();
        }
        return String.format("!%08x", node.getNodeNum());
    }

    public static String formatNodeToken(NodeData node) {
        String name = commandTokenName(node);
        String nodeId = normalizedNodeId(node);
        if (name.equalsIgnoreCase(nodeId)) {
            return nodeId;
        }
        return name + "(" + nodeId + ")";
    }

    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text != null ? text : "");
        while (matcher.find()) {
            tokens.add(new Token(matcher.group(), matcher.start(), matcher.end()));
        }
        return tokens;
    }

    private static BotDefinition findBot(String token) {
        if (isBlank(token)) {
            return null;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        return BOTS.stream()
                .filter(bot -> bot.handle().equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private static List<NodeData> uniqueNodes(Collection<NodeData> rawNodes) {
        Map<String, NodeData> dedup = new LinkedHashMap<>();
        if (rawNodes == null) {
            return List.of();
        }
        for (NodeData node : rawNodes) {
            if (node == null) {
                continue;
            }
            dedup.putIfAbsent(normalizedNodeId(node).toLowerCase(Locale.ROOT), node);
        }
        return new ArrayList<>(dedup.values());
    }

    private static String extractNodeIdQuery(String rawTargetToken) {
        Matcher matcher = NODE_ID_PATTERN.matcher(rawTargetToken);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static NodeResolution matchByNodeId(List<NodeData> nodes, String nodeIdQuery) {
        String fullNodeId = "!" + nodeIdQuery;

        List<NodeData> exactMatches = nodes.stream()
                .filter(node -> normalizedNodeId(node).equalsIgnoreCase(fullNodeId))
                .toList();
        if (exactMatches.size() == 1) {
            return NodeResolution.found(exactMatches.getFirst());
        }
        if (exactMatches.size() > 1) {
            return NodeResolution.ambiguous();
        }

        List<NodeData> suffixMatches = nodes.stream()
                .filter(node -> {
                    String normalized = normalizedNodeId(node);
                    return normalized.length() > 1
                            && normalized.substring(1).toLowerCase(Locale.ROOT).endsWith(nodeIdQuery);
                })
                .toList();
        if (suffixMatches.size() == 1) {
            return NodeResolution.found(suffixMatches.getFirst());
        }
        if (suffixMatches.size() > 1) {
            return NodeResolution.ambiguous();
        }
        return NodeResolution.notFound();
    }

    private static boolean matchesNodeName(NodeData node, String query) {
        return equalsIgnoreCase(displayName(node), query)
                || equalsIgnoreCase(node.getLongName(), query)
                || equalsIgnoreCase(node.getShortName(), query)
                || equalsIgnoreCase(formatNodeToken(node), query)
                || equalsIgnoreCase(normalizedNodeId(node), query);
    }

    private static int nodeMatchScore(NodeData node, String query) {
        if (query.isEmpty()) {
            return 10;
        }

        String display = normalize(displayName(node));
        String longName = normalize(node.getLongName());
        String shortName = normalize(node.getShortName());
        String nodeId = normalize(normalizedNodeId(node));
        String nodeHex = nodeId.startsWith("!") ? nodeId.substring(1) : nodeId;
        String token = normalize(formatNodeToken(node));

        if (display.equals(query) || longName.equals(query) || shortName.equals(query)
                || nodeId.equals(query) || ("!" + nodeHex).equals(query) || token.equals(query)) {
            return 0;
        }
        if (display.startsWith(query) || longName.startsWith(query) || shortName.startsWith(query)
                || nodeId.startsWith(query) || nodeHex.startsWith(query) || nodeHex.endsWith(query)) {
            return 1;
        }
        if (display.contains(query) || longName.contains(query) || shortName.contains(query)
                || nodeId.contains(query) || nodeHex.contains(query) || token.contains(query)) {
            return 2;
        }
        return -1;
    }

    private static String secondaryText(NodeData node) {
        List<String> parts = new ArrayList<>();
        String primary = displayName(node);
        if (!isBlank(node.getShortName()) && !primary.equalsIgnoreCase(node.getShortName())) {
            parts.add(node.getShortName().trim());
        }
        if (!isBlank(node.getLongName()) && !primary.equalsIgnoreCase(node.getLongName())) {
            parts.add(node.getLongName().trim());
        }
        parts.add(normalizedNodeId(node));
        return String.join(" • ", parts);
    }

    private static String commandTokenName(NodeData node) {
        String shortName = sanitizeCommandTokenPart(node != null ? node.getShortName() : null);
        if (!isBlank(shortName)) {
            return shortName;
        }

        String longName = sanitizeCommandTokenPart(node != null ? node.getLongName() : null);
        if (!isBlank(longName)) {
            return longName;
        }

        return normalizedNodeId(node);
    }

    private static String sanitizeCommandTokenPart(String value) {
        if (isBlank(value)) {
            return "";
        }
        return value.trim().replaceAll("\\s+", "_");
    }

    private static String normalizedNodeId(NodeData node) {
        if (node != null && !isBlank(node.getNodeId())) {
            return node.getNodeId().trim();
        }
        return node != null ? String.format("!%08x", node.getNodeNum()) : "";
    }

    private static String normalizeBotQuery(String rawQuery) {
        String query = normalize(rawQuery);
        return query.startsWith("@") ? query.substring(1) : query;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return !isBlank(left) && !isBlank(right) && left.trim().equalsIgnoreCase(right.trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
