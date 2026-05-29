package com.meshtastic.client.components.chat;

import com.meshtastic.client.model.NodeData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class ChatBotCommandHelperTest {

    @Test
    void detectsBotSuggestionWhenTypingFirstToken() {
        String input = "@tr";

        ChatBotCommandHelper.SuggestionContext context =
                ChatBotCommandHelper.detectSuggestionContext(input, input.length());

        assertEquals(ChatBotCommandHelper.SuggestionMode.BOT, context.mode());
        assertEquals("@tr", context.query());
        assertEquals(0, context.replacementStart());
        assertEquals(input.length(), context.replacementEnd());
    }

    @Test
    void suggestsExternalAutomationBotsTogetherWithBuiltIns() {
        List<ChatBotCommandHelper.BotDefinition> suggestions = ChatBotCommandHelper.suggestBots(
                "@tr",
                List.of(new ChatBotCommandHelper.BotDefinition("@traceauto", "Automation script", null)));

        assertEquals(List.of("@tracebot", "@traceauto"),
                suggestions.stream().map(ChatBotCommandHelper.BotDefinition::handle).toList());
    }

    @Test
    void externalAutomationBotIsNotParsedAsBuiltInCommandYet() {
        ChatBotCommandHelper.ParsedBotCommand command = ChatBotCommandHelper.parseCommand("@traceauto target");

        assertFalse(command.isCommand());
    }

    @Test
    void parsesExternalAutomationBotCommandWithArguments() {
        ChatBotCommandHelper.ParsedBotCommand command = ChatBotCommandHelper.parseCommand(
                "@traceauto Alpha Node",
                List.of(new ChatBotCommandHelper.BotDefinition(
                        "@traceauto",
                        "Automation script",
                        ChatBotCommandHelper.BotAction.AUTOMATION,
                        42L)));

        assertTrue(command.isCommand());
        assertEquals(ChatBotCommandHelper.BotAction.AUTOMATION, command.action());
        assertEquals(42L, command.scriptId());
        assertEquals("Alpha", command.targetToken());
        assertEquals("Alpha Node", command.arguments());
        assertEquals(List.of("Alpha", "Node"), command.argumentTokens());
        assertTrue(command.hasExtraTokens());
    }

    @Test
    void switchesToNodeSuggestionAfterBotSelection() {
        String input = "@tracebot ";

        ChatBotCommandHelper.SuggestionContext context =
                ChatBotCommandHelper.detectSuggestionContext(input, input.length());

        assertEquals(ChatBotCommandHelper.SuggestionMode.NODE, context.mode());
        assertEquals("", context.query());
        assertEquals("@tracebot".length(), context.replacementStart());
        assertEquals(input.length(), context.replacementEnd());
        assertEquals(ChatBotCommandHelper.BotAction.TRACEROUTE, context.action());
    }

    @Test
    void parsesKnownBotCommand() {
        ChatBotCommandHelper.ParsedBotCommand command =
                ChatBotCommandHelper.parseCommand("  @infobot Alpha(!0000beef)  ");

        assertTrue(command.isCommand());
        assertTrue(command.isReady());
        assertEquals(ChatBotCommandHelper.BotAction.NODE_INFO, command.action());
        assertEquals("@infobot", command.botHandle());
        assertEquals("Alpha(!0000beef)", command.targetToken());
        assertFalse(command.hasExtraTokens());
    }

    @Test
    void resolvesNodeByInsertedNodeIdSuffix() {
        NodeData alpha = node(0x0000BEEF, "Alpha", "ALP", 100);
        NodeData bravo = node(0x0000CAFE, "Bravo", "BRV", 50);

        ChatBotCommandHelper.NodeResolution resolution =
                ChatBotCommandHelper.resolveTarget("Alpha(!beef)", List.of(alpha, bravo));

        assertEquals(ChatBotCommandHelper.NodeResolutionStatus.FOUND, resolution.status());
        assertEquals(alpha, resolution.node());
    }

    @Test
    void marksAmbiguousNameResolution() {
        NodeData alpha1 = node(0x0000BEEF, "Alpha", "A1", 100);
        NodeData alpha2 = node(0x0000FEED, "Alpha", "A2", 90);

        ChatBotCommandHelper.NodeResolution resolution =
                ChatBotCommandHelper.resolveTarget("Alpha", List.of(alpha1, alpha2));

        assertEquals(ChatBotCommandHelper.NodeResolutionStatus.AMBIGUOUS, resolution.status());
    }

    @Test
    void suggestsNodesUsingNameAndKeepsCommandTokenFormat() {
        NodeData alpha = node(0x0000BEEF, "Alpha", "ALP", 100);
        NodeData bravo = node(0x0000CAFE, "Bravo", "BRV", 50);

        List<ChatBotCommandHelper.NodeSuggestion> suggestions =
                ChatBotCommandHelper.suggestNodes(List.of(alpha, bravo), "alp", 10);

        assertEquals(1, suggestions.size());
        assertEquals("ALP(!0000beef)", suggestions.getFirst().insertText());
        assertTrue(suggestions.getFirst().secondaryText().contains("!0000beef"));
    }

    @Test
    void usesSingleWordTokenForNamesWithSpaces() {
        NodeData spaced = node(0x0000BEEF, "Alpha Node", "AN", 100);

        assertEquals("AN(!0000beef)", ChatBotCommandHelper.formatNodeToken(spaced));
    }

    private static NodeData node(int nodeNum, String longName, String shortName, int lastHeard) {
        NodeData node = new NodeData(nodeNum);
        node.setLongName(longName);
        node.setShortName(shortName);
        node.setLastHeard(lastHeard);
        return node;
    }
}
