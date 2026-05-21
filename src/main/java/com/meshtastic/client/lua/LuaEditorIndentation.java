package com.meshtastic.client.lua;

import java.util.regex.Pattern;

/**
 * Правила редактирования отступов Lua-кода в MeshApp IDE.
 * <p>
 * Реализует поведение клавиш Enter, Tab и Shift+Tab как в редакторах кода:
 * четыре пробела на уровень, сохранение текущего отступа и увеличение отступа
 * после открывающих Lua-блоков.
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class LuaEditorIndentation {

    public static final String INDENT = "    ";

    private static final Pattern BLOCK_OPENER = Pattern.compile(
            "^(?:.*\\bthen|.*\\bdo|.*\\bfunction\\b.*|repeat|else|elseif\\b.*\\bthen|.*[({])\\s*$");

    private LuaEditorIndentation() {}

    public static TextEdit newLineEdit(String text, int selectionStart, int selectionEnd) {
        String safeText = text != null ? text : "";
        int start = clamp(Math.min(selectionStart, selectionEnd), safeText.length());
        int end = clamp(Math.max(selectionStart, selectionEnd), safeText.length());
        int lineStart = lineStart(safeText, start);
        String lineBeforeCaret = safeText.substring(lineStart, start);
        String insertion = "\n" + newLineIndent(lineBeforeCaret);
        int caret = start + insertion.length();
        return new TextEdit(start, end, insertion, caret, caret);
    }

    public static TextEdit tabEdit(String text, int selectionStart, int selectionEnd, boolean unindent) {
        String safeText = text != null ? text : "";
        int start = clamp(Math.min(selectionStart, selectionEnd), safeText.length());
        int end = clamp(Math.max(selectionStart, selectionEnd), safeText.length());
        if (!unindent && start == end) {
            int caret = start + INDENT.length();
            return new TextEdit(start, end, INDENT, caret, caret);
        }

        int editStart = lineStart(safeText, start);
        int editEnd = selectedLinesEnd(safeText, start, end);
        String original = safeText.substring(editStart, editEnd);
        String replacement = unindent ? unindentBlock(original) : indentBlock(original);

        if (start == end) {
            int removed = original.length() - replacement.length();
            int caret = unindent
                    ? Math.max(editStart, start - Math.min(Math.max(0, removed), start - editStart))
                    : start + INDENT.length();
            return new TextEdit(editStart, editEnd, replacement, caret, caret);
        }

        int selectionEndAfter = editStart + replacement.length();
        return new TextEdit(editStart, editEnd, replacement, editStart, selectionEndAfter);
    }

    public static String newLineIndent(String lineBeforeCaret) {
        String line = lineBeforeCaret != null ? lineBeforeCaret : "";
        String baseIndent = leadingWhitespace(line);
        String code = stripLineComment(line).trim();
        if (BLOCK_OPENER.matcher(code).matches() && !isCompleteSingleLineBlock(code)) {
            return baseIndent + INDENT;
        }
        return baseIndent;
    }

    private static String indentBlock(String block) {
        return INDENT + block.replace("\n", "\n" + INDENT);
    }

    private static String unindentBlock(String block) {
        StringBuilder result = new StringBuilder(block.length());
        int lineStart = 0;
        while (lineStart <= block.length()) {
            int newline = block.indexOf('\n', lineStart);
            int lineEnd = newline >= 0 ? newline : block.length();
            String line = block.substring(lineStart, lineEnd);
            result.append(unindentLine(line));
            if (newline < 0) {
                break;
            }
            result.append('\n');
            lineStart = newline + 1;
        }
        return result.toString();
    }

    private static String unindentLine(String line) {
        int remove = 0;
        while (remove < Math.min(INDENT.length(), line.length()) && line.charAt(remove) == ' ') {
            remove++;
        }
        if (remove == 0 && !line.isEmpty() && line.charAt(0) == '\t') {
            remove = 1;
        }
        return line.substring(remove);
    }

    private static int selectedLinesEnd(String text, int start, int end) {
        int effectiveEnd = end;
        if (effectiveEnd > start && effectiveEnd <= text.length() && text.charAt(effectiveEnd - 1) == '\n') {
            effectiveEnd--;
        }
        return lineEnd(text, Math.max(start, effectiveEnd));
    }

    private static int lineStart(String text, int offset) {
        int safeOffset = clamp(offset, text.length());
        int index = text.lastIndexOf('\n', Math.max(0, safeOffset - 1));
        return index >= 0 ? index + 1 : 0;
    }

    private static int lineEnd(String text, int offset) {
        int safeOffset = clamp(offset, text.length());
        int index = text.indexOf('\n', safeOffset);
        return index >= 0 ? index : text.length();
    }

    private static String leadingWhitespace(String line) {
        int index = 0;
        while (index < line.length()) {
            char ch = line.charAt(index);
            if (ch != ' ' && ch != '\t') {
                break;
            }
            index++;
        }
        return line.substring(0, index);
    }

    private static String stripLineComment(String line) {
        int commentStart = line.indexOf("--");
        return commentStart >= 0 ? line.substring(0, commentStart) : line;
    }

    private static boolean isCompleteSingleLineBlock(String code) {
        return code.matches(".*\\bend\\b\\s*$") || code.matches(".*\\buntil\\b.+$");
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }

    public record TextEdit(int start, int end, String replacement, int selectionStart, int selectionEnd) {}
}
