package com.meshtastic.client.lua;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Lightweight function outline builder for Lua source used by MeshApp IDE.
 * <p>
 * This is intentionally not a full Lua parser. It masks comments and strings,
 * then walks tokens to find named function declarations and common assignment
 * forms while keeping enough block state to preserve nested functions.
 */
public final class LuaFunctionIndex {

    private LuaFunctionIndex() {}

    /**
     * Builds a hierarchical index of named Lua functions found in the supplied source code.
     * <p>
     * The parser keeps comments and string literals out of the token stream, so function-like
     * text inside those ranges is ignored. The returned list contains only top-level functions;
     * nested declarations are exposed through {@link FunctionNode#children()}.
     *
     * @param code Lua source text; {@code null} is treated as an empty source
     * @return immutable list of top-level function nodes in source order
     */
    public static List<FunctionNode> parse(String code) {
        String source = code != null ? code : "";
        if (source.isBlank()) {
            return List.of();
        }

        List<Token> tokens = tokenize(maskNonCode(source));
        List<FunctionBuilder> roots = new ArrayList<>();
        Deque<Block> blocks = new ArrayDeque<>();

        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.isIdentifier("function")) {
                ParsedFunction parsed = parseFunction(tokens, index, source);
                if (parsed != null) {
                    if (parsed.function() != null) {
                        FunctionBuilder parent = currentVisibleFunction(blocks);
                        if (parent != null) {
                            parent.children().add(parsed.function());
                        } else {
                            roots.add(parsed.function());
                        }
                    }
                    blocks.push(new Block(BlockKind.FUNCTION, parsed.function()));
                    index = Math.max(index, parsed.lastTokenIndex());
                    continue;
                }
            }

            if (token.isIdentifier("if")) {
                blocks.push(new Block(BlockKind.IF, null));
            } else if (token.isIdentifier("do")) {
                blocks.push(new Block(BlockKind.DO, null));
            } else if (token.isIdentifier("repeat")) {
                blocks.push(new Block(BlockKind.REPEAT, null));
            } else if (token.isIdentifier("end")) {
                popEndBlock(blocks);
            } else if (token.isIdentifier("until")) {
                popRepeatBlock(blocks);
            }
        }

        return roots.stream()
                .map(FunctionBuilder::toNode)
                .toList();
    }

    private static ParsedFunction parseFunction(List<Token> tokens, int functionIndex, String source) {
        int openParenIndex;
        NameInfo name = null;

        Token previous = functionIndex > 0 ? tokens.get(functionIndex - 1) : null;
        Token functionToken = tokens.get(functionIndex);
        if (previous != null && previous.isSymbol("=")
                && isWhitespaceOnly(source, previous.endOffset(), functionToken.offset())) {
            openParenIndex = functionIndex + 1;
            if (!isToken(tokens, openParenIndex, "(")) {
                return null;
            }
            name = parseAssignmentName(tokens, functionIndex - 1);
        } else {
            name = parseForwardName(tokens, functionIndex + 1);
            openParenIndex = name != null ? name.nextTokenIndex() : functionIndex + 1;
            if (name == null && isToken(tokens, functionIndex + 1, "(")) {
                openParenIndex = functionIndex + 1;
            }
            if (!isToken(tokens, openParenIndex, "(")) {
                return null;
            }
        }

        int closeParenIndex = findMatchingParen(tokens, openParenIndex);
        Token openParen = tokens.get(openParenIndex);
        int paramsEnd = closeParenIndex >= 0
                ? tokens.get(closeParenIndex).offset()
                : lineEnd(source, openParen.offset());
        String parameters = normalizeParameters(source.substring(
                Math.min(source.length(), openParen.offset() + 1),
                Math.max(Math.min(source.length(), openParen.offset() + 1), Math.min(source.length(), paramsEnd))));

        FunctionBuilder builder = null;
        if (name != null && !name.name().isBlank()) {
            builder = new FunctionBuilder(
                    name.name(),
                    parameters,
                    functionToken.offset(),
                    name.startOffset(),
                    name.endOffset(),
                    functionToken.line(),
                    new ArrayList<>());
        }

        return new ParsedFunction(builder, closeParenIndex >= 0 ? closeParenIndex : openParenIndex);
    }

    private static NameInfo parseForwardName(List<Token> tokens, int index) {
        if (!isIdentifier(tokens, index)) {
            return null;
        }
        StringBuilder name = new StringBuilder(tokens.get(index).text());
        int startOffset = tokens.get(index).offset();
        int endOffset = tokens.get(index).endOffset();
        int cursor = index + 1;
        while (cursor + 1 < tokens.size()
                && (tokens.get(cursor).isSymbol(".") || tokens.get(cursor).isSymbol(":"))
                && tokens.get(cursor + 1).type() == TokenType.IDENTIFIER) {
            name.append(tokens.get(cursor).text()).append(tokens.get(cursor + 1).text());
            endOffset = tokens.get(cursor + 1).endOffset();
            cursor += 2;
        }
        return new NameInfo(name.toString(), startOffset, endOffset, cursor);
    }

    private static NameInfo parseAssignmentName(List<Token> tokens, int equalsIndex) {
        int index = equalsIndex - 1;
        if (!isIdentifier(tokens, index)) {
            return null;
        }

        StringBuilder name = new StringBuilder(tokens.get(index).text());
        int startOffset = tokens.get(index).offset();
        int endOffset = tokens.get(index).endOffset();
        index--;

        while (index - 1 >= 0
                && (tokens.get(index).isSymbol(".") || tokens.get(index).isSymbol(":"))
                && tokens.get(index - 1).type() == TokenType.IDENTIFIER) {
            String separator = tokens.get(index).text();
            Token part = tokens.get(index - 1);
            name.insert(0, part.text() + separator);
            startOffset = part.offset();
            index -= 2;
        }

        return new NameInfo(name.toString(), startOffset, endOffset, equalsIndex);
    }

    private static int findMatchingParen(List<Token> tokens, int openParenIndex) {
        int depth = 0;
        for (int index = openParenIndex; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (token.isSymbol("(")) {
                depth++;
            } else if (token.isSymbol(")")) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static FunctionBuilder currentVisibleFunction(Deque<Block> blocks) {
        for (Block block : blocks) {
            if (block.function() != null) {
                return block.function();
            }
        }
        return null;
    }

    private static void popEndBlock(Deque<Block> blocks) {
        if (!blocks.isEmpty() && blocks.peek().kind() != BlockKind.REPEAT) {
            blocks.pop();
        }
    }

    private static void popRepeatBlock(Deque<Block> blocks) {
        if (!blocks.isEmpty() && blocks.peek().kind() == BlockKind.REPEAT) {
            blocks.pop();
        }
    }

    private static boolean isIdentifier(List<Token> tokens, int index) {
        return index >= 0 && index < tokens.size() && tokens.get(index).type() == TokenType.IDENTIFIER;
    }

    private static boolean isToken(List<Token> tokens, int index, String text) {
        return index >= 0 && index < tokens.size() && tokens.get(index).text().equals(text);
    }

    private static String normalizeParameters(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static int lineEnd(String text, int offset) {
        int newline = text.indexOf('\n', Math.max(0, Math.min(offset, text.length())));
        return newline >= 0 ? newline : text.length();
    }

    private static boolean isWhitespaceOnly(String text, int start, int end) {
        int safeStart = Math.max(0, Math.min(start, text.length()));
        int safeEnd = Math.max(safeStart, Math.min(end, text.length()));
        for (int index = safeStart; index < safeEnd; index++) {
            if (!Character.isWhitespace(text.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int line = 1;
        int index = 0;
        while (index < text.length()) {
            char ch = text.charAt(index);
            if (ch == '\n') {
                line++;
                index++;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                index++;
                continue;
            }
            if (isIdentifierStart(ch)) {
                int start = index;
                int tokenLine = line;
                index++;
                while (index < text.length() && isIdentifierPart(text.charAt(index))) {
                    index++;
                }
                tokens.add(new Token(TokenType.IDENTIFIER, text.substring(start, index), start, index, tokenLine));
                continue;
            }
            tokens.add(new Token(TokenType.SYMBOL, Character.toString(ch), index, index + 1, line));
            index++;
        }
        return tokens;
    }

    private static boolean isIdentifierStart(char ch) {
        return ch == '_' || Character.isLetter(ch);
    }

    private static boolean isIdentifierPart(char ch) {
        return ch == '_' || Character.isLetterOrDigit(ch);
    }

    private static String maskNonCode(String source) {
        StringBuilder masked = new StringBuilder(source);
        int index = 0;
        while (index < source.length()) {
            char ch = source.charAt(index);
            if (ch == '-' && index + 1 < source.length() && source.charAt(index + 1) == '-') {
                int longBracketEquals = longBracketEquals(source, index + 2);
                if (longBracketEquals >= 0) {
                    int close = findLongBracketClose(source, index + 2, longBracketEquals);
                    int end = close >= 0 ? close : source.length();
                    maskRange(masked, index, end);
                    index = end;
                } else {
                    int end = source.indexOf('\n', index);
                    if (end < 0) {
                        end = source.length();
                    }
                    maskRange(masked, index, end);
                    index = end;
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                int end = quotedStringEnd(source, index, ch);
                maskRange(masked, index, end);
                index = end;
                continue;
            }
            int longBracketEquals = longBracketEquals(source, index);
            if (longBracketEquals >= 0) {
                int close = findLongBracketClose(source, index, longBracketEquals);
                int end = close >= 0 ? close : source.length();
                maskRange(masked, index, end);
                index = end;
                continue;
            }
            index++;
        }
        return masked.toString();
    }

    private static int quotedStringEnd(String source, int start, char quote) {
        int index = start + 1;
        while (index < source.length()) {
            char ch = source.charAt(index);
            if (ch == '\\') {
                index = Math.min(source.length(), index + 2);
            } else if (ch == quote) {
                return index + 1;
            } else if (ch == '\n') {
                return index;
            } else {
                index++;
            }
        }
        return source.length();
    }

    private static int longBracketEquals(String source, int start) {
        if (start >= source.length() || source.charAt(start) != '[') {
            return -1;
        }
        int index = start + 1;
        while (index < source.length() && source.charAt(index) == '=') {
            index++;
        }
        return index < source.length() && source.charAt(index) == '[' ? index - start - 1 : -1;
    }

    private static int findLongBracketClose(String source, int start, int equalsCount) {
        int contentStart = start + equalsCount + 2;
        for (int index = contentStart; index < source.length(); index++) {
            if (source.charAt(index) != ']') {
                continue;
            }
            int cursor = index + 1;
            int equalsSeen = 0;
            while (equalsSeen < equalsCount && cursor < source.length() && source.charAt(cursor) == '=') {
                equalsSeen++;
                cursor++;
            }
            if (equalsSeen == equalsCount && cursor < source.length() && source.charAt(cursor) == ']') {
                return cursor + 1;
            }
        }
        return -1;
    }

    private static void maskRange(StringBuilder text, int start, int end) {
        for (int index = Math.max(0, start); index < Math.min(text.length(), end); index++) {
            if (text.charAt(index) != '\n') {
                text.setCharAt(index, ' ');
            }
        }
    }

    /**
     * Immutable entry used by MeshApp IDE's function outline tree.
     *
     * @param name function name as shown in the outline
     * @param parameters normalized parameter list without the enclosing parentheses
     * @param offset source offset of the {@code function} keyword
     * @param nameStartOffset source offset where the function name starts
     * @param nameEndOffset source offset immediately after the function name
     * @param line 1-based source line of the function declaration
     * @param children nested functions declared inside this function body
     */
    public record FunctionNode(
            String name,
            String parameters,
            int offset,
            int nameStartOffset,
            int nameEndOffset,
            int line,
            List<FunctionNode> children) {

        public FunctionNode {
            children = Collections.unmodifiableList(new ArrayList<>(children != null ? children : List.of()));
        }

        /**
         * @return display text containing the function name and normalized parameter list
         */
        public String signature() {
            return name + "(" + parameters + ")";
        }
    }

    private record FunctionBuilder(
            String name,
            String parameters,
            int offset,
            int nameStartOffset,
            int nameEndOffset,
            int line,
            List<FunctionBuilder> children) {

        FunctionNode toNode() {
            return new FunctionNode(
                    name,
                    parameters,
                    offset,
                    nameStartOffset,
                    nameEndOffset,
                    line,
                    children.stream().map(FunctionBuilder::toNode).toList());
        }
    }

    private record ParsedFunction(FunctionBuilder function, int lastTokenIndex) {}

    private record NameInfo(String name, int startOffset, int endOffset, int nextTokenIndex) {}

    private record Block(BlockKind kind, FunctionBuilder function) {}

    private enum BlockKind {
        FUNCTION,
        IF,
        DO,
        REPEAT
    }

    private record Token(TokenType type, String text, int offset, int endOffset, int line) {
        boolean isIdentifier(String value) {
            return type == TokenType.IDENTIFIER && text.equals(value);
        }

        boolean isSymbol(String value) {
            return type == TokenType.SYMBOL && text.equals(value);
        }
    }

    private enum TokenType {
        IDENTIFIER,
        SYMBOL
    }
}
