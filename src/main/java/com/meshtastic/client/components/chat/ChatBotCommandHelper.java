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
 * Parses slash-prefixed chat bot commands and prepares bot and node suggestions.
 *
 * <p>A leading {@code /} selects bots, while {@code @} at the start of a token
 * searches node names anywhere in a draft. Chat command spelling is separate
 * from stored automation handles so existing Lua scripts retain their handles.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class ChatBotCommandHelper {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\S+");
    private static final Pattern NODE_ID_PATTERN = Pattern.compile("!([0-9a-fA-F]{1,8})");
    private static final List<BotDefinition> BOTS = List.of();

    private ChatBotCommandHelper() {}

    public enum BotAction {
        AUTOMATION
    }

    /** Content offered by the autocomplete popup at the current caret position. */
    public enum SuggestionMode {
        /** No autocomplete trigger is active. */
        NONE,
        /** Bot commands matching a leading {@code /} token. */
        BOT,
        /** Node names matching the current {@code @} token. */
        NODE
    }

    public enum NodeResolutionStatus {
        FOUND,
        NOT_FOUND,
        AMBIGUOUS
    }

    /**
     * Bot metadata shared by command parsing and autocomplete.
     *
     * @param handle stored automation handle, typically prefixed with {@code @}
     * @param description explanatory text displayed below the command
     * @param action action dispatched when the bot is selected or invoked
     * @param scriptId identifier of the associated Lua script
     */
    public record BotDefinition(String handle, String description, BotAction action, long scriptId) {

        public BotDefinition(String handle, String description, BotAction action) {
            this(handle, description, action, 0L);
        }

        /**
         * Returns the slash-prefixed spelling shown and accepted in chat.
         *
         * <p>For example, a stored {@code @tracebot} handle becomes
         * {@code /tracebot}. This conversion preserves name casing and does not
         * change the handle passed to Lua scripts.
         *
         * @return trimmed handle with its prefix replaced by, or prepended with,
         *         {@code /}; a null or empty handle produces {@code /}
         */
        public String commandHandle() {
            String name = handle != null ? handle.trim() : "";
            return "/" + (name.startsWith("@") || name.startsWith("/") ? name.substring(1) : name);
        }
    }

    /**
     * Autocomplete query and the complete token to replace upon selection.
     *
     * @param mode type of suggestions to display
     * @param query text up to the caret, excluding {@code @} for nodes and
     *              including {@code /} for bots
     * @param replacementStart inclusive UTF-16 offset of the trigger character
     * @param replacementEnd exclusive UTF-16 offset of the token's end, which
     *                       may be after the caret
     * @param action associated bot action, or null for trigger-based completion
     */
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
                                   boolean hasExtraTokens,
                                   String arguments,
                                   List<String> argumentTokens,
                                   long scriptId) {

        public ParsedBotCommand(BotAction action,
                                String botHandle,
                                String targetToken,
                                boolean hasExtraTokens) {
            this(action, botHandle, targetToken, hasExtraTokens, "", List.of(), 0L);
        }

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

    /**
     * Node completion data for both chat text and Lua node selection.
     *
     * @param insertText node token containing its address, used to resolve Lua selections
     * @param primaryText display name inserted into chat when an {@code @} result is selected
     * @param secondaryText additional names and address shown below the display name
     */
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

    /**
     * Finds matching bots without additional automation definitions.
     *
     * @param rawQuery case-insensitive name fragment, optionally prefixed with {@code /}
     * @return matching built-in definitions; currently empty
     * @see #suggestBots(String, Collection)
     */
    public static List<BotDefinition> suggestBots(String rawQuery) {
        return suggestBots(rawQuery, List.of());
    }

    /**
     * Finds bots whose handles contain the query, ignoring case and handle prefixes.
     *
     * @param rawQuery name fragment; a leading {@code /} or legacy {@code @} is
     *                 ignored, and a null or blank query matches all bots
     * @param extraBots additional automation definitions, or null for none
     * @return matching definitions with their original stored handles
     */
    public static List<BotDefinition> suggestBots(String rawQuery, Collection<BotDefinition> extraBots) {
        String query = normalizeBotQuery(rawQuery);
        return mergeBots(extraBots).stream()
                .filter(bot -> botMatchesQuery(bot, query))
                .toList();
    }

    private static boolean botMatchesQuery(BotDefinition bot, String query) {
        String handle = normalizeBotQuery(bot.handle());
        return query.isEmpty() || handle.startsWith(query) || handle.contains(query);
    }

    /**
     * Detects autocomplete for the whitespace-delimited token containing the caret.
     *
     * <p>A token beginning with {@code @} enables node search anywhere in the
     * draft. A token beginning with {@code /} enables bot search only at offset
     * zero. The caret must be after the trigger and at or before the token's end;
     * embedded triggers in email addresses and URLs do not activate completion.
     * The query ends at the caret, but selection replaces the entire token.
     *
     * @param text current draft, or null for an empty draft
     * @param caretPosition UTF-16 caret offset, clamped to the draft's bounds
     * @return active query and replacement range, or {@link SuggestionContext#none()}
     */
    public static SuggestionContext detectSuggestionContext(String text, int caretPosition) {
        String safeText = text != null ? text : "";
        int caret = Math.clamp(caretPosition, 0, safeText.length());
        List<Token> tokens = tokenize(safeText);
        if (tokens.isEmpty()) {
            return SuggestionContext.none();
        }

        for (Token token : tokens) {
            if (caret <= token.start() || caret > token.end()) {
                continue;
            }
            if (token.text().startsWith("@")) {
                return new SuggestionContext(
                        SuggestionMode.NODE,
                        safeText.substring(token.start() + 1, caret),
                        token.start(),
                        token.end(),
                        null);
            }
            if (token.start() == 0 && token.text().startsWith("/")) {
                return new SuggestionContext(
                        SuggestionMode.BOT,
                        safeText.substring(0, caret),
                        0,
                        token.end(),
                        null);
            }
        }
        return SuggestionContext.none();
    }

    /**
     * Parses a slash command using only built-in bot definitions.
     *
     * @param text draft to parse, or null for an empty draft
     * @return parsed command, or {@link ParsedBotCommand#none()} if no bot matches
     * @see #parseCommand(String, Collection)
     */
    public static ParsedBotCommand parseCommand(String text) {
        return parseCommand(text, List.of());
    }

    /**
     * Parses a leading slash command and its whitespace-delimited arguments.
     *
     * <p>Surrounding whitespace is trimmed and command matching ignores case.
     * Text beginning with {@code @} is not a bot command. The result retains the
     * matched bot's stored handle, including a legacy {@code @} prefix, for Lua.
     *
     * @param text draft to parse, or null for an empty draft
     * @param extraBots additional automation definitions, or null for none
     * @return matched bot and arguments, or {@link ParsedBotCommand#none()}
     */
    public static ParsedBotCommand parseCommand(String text, Collection<BotDefinition> extraBots) {
        String safeText = text != null ? text.trim() : "";
        if (safeText.isEmpty()) {
            return ParsedBotCommand.none();
        }

        List<Token> tokens = tokenize(safeText);
        if (tokens.isEmpty()) {
            return ParsedBotCommand.none();
        }

        BotDefinition bot = findBot(tokens.getFirst().text(), extraBots);
        if (bot == null) {
            return ParsedBotCommand.none();
        }

        String targetToken = tokens.size() >= 2 ? tokens.get(1).text() : "";
        String arguments = tokens.size() >= 2 ? safeText.substring(tokens.get(1).start()).trim() : "";
        List<String> argumentTokens = tokens.stream().skip(1).map(Token::text).toList();
        return new ParsedBotCommand(
                bot.action(),
                bot.handle(),
                targetToken,
                tokens.size() > 2,
                arguments,
                argumentTokens,
                bot.scriptId());
    }

    /**
     * Searches unique nodes by full name, short name, or address, ignoring case.
     *
     * <p>Results are ordered by match quality, then most recent contact, then
     * display name. Chat completion inserts {@link NodeSuggestion#primaryText()},
     * while Lua selection resolves {@link NodeSuggestion#insertText()}.
     *
     * @param rawNodes candidate nodes, possibly containing nulls or duplicate addresses
     * @param rawQuery search text without the {@code @} trigger; null or blank matches all nodes
     * @param limit maximum number of results; nonpositive values return an empty list
     * @return ranked suggestions, or an empty list when no candidates match
     */
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

    /**
     * Resolves a slash-prefixed command against stored bot handles without changing them.
     *
     * @param token complete command token
     * @param extraBots additional automation definitions, or null for none
     * @return matching definition, or null for an unknown or non-slash token
     */
    private static BotDefinition findBot(String token, Collection<BotDefinition> extraBots) {
        if (isBlank(token) || !token.startsWith("/")) {
            return null;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        return mergeBots(extraBots).stream()
                .filter(bot -> bot.commandHandle().equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(null);
    }

    private static List<BotDefinition> mergeBots(Collection<BotDefinition> extraBots) {
        Map<String, BotDefinition> bots = new LinkedHashMap<>();
        BOTS.forEach(bot -> bots.put(bot.handle().toLowerCase(Locale.ROOT), bot));
        if (extraBots != null) {
            for (BotDefinition bot : extraBots) {
                if (bot == null || isBlank(bot.handle())) {
                    continue;
                }
                bots.put(bot.handle().trim().toLowerCase(Locale.ROOT), bot);
            }
        }
        return new ArrayList<>(bots.values());
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

    /**
     * Normalizes chat queries and stored handles for case-insensitive matching.
     *
     * @param rawQuery name or handle, optionally prefixed with {@code /} or {@code @}
     * @return trimmed lowercase text without its prefix, or an empty string for null
     */
    private static String normalizeBotQuery(String rawQuery) {
        String query = normalize(rawQuery);
        return query.startsWith("@") || query.startsWith("/") ? query.substring(1) : query;
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
