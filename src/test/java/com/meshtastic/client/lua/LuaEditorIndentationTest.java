package com.meshtastic.client.lua;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LuaEditorIndentationTest {

    @Test
    void enterKeepsCurrentIndent() {
        LuaEditorIndentation.TextEdit edit = LuaEditorIndentation.newLineEdit("    mesh.log(msg.text)", 22, 22);

        assertEquals("\n    ", edit.replacement());
        assertEquals(27, edit.selectionStart());
        assertEquals(27, edit.selectionEnd());
    }

    @Test
    void enterAddsOneLevelAfterLuaBlockOpeners() {
        assertEquals("\n    ", LuaEditorIndentation.newLineEdit("if msg.text then", 16, 16).replacement());
        assertEquals("\n        ", LuaEditorIndentation.newLineEdit("    function run()", 18, 18).replacement());
        assertEquals("\n        ", LuaEditorIndentation.newLineEdit("    local cb = function(msg)", 28, 28).replacement());
        assertEquals("\n        ", LuaEditorIndentation.newLineEdit("    repeat", 10, 10).replacement());
        assertEquals("\n        ", LuaEditorIndentation.newLineEdit("    else", 8, 8).replacement());
        assertEquals("\n        ", LuaEditorIndentation.newLineEdit("    local cfg = {", 17, 17).replacement());
    }

    @Test
    void tabInsertsFourSpacesAtCaret() {
        LuaEditorIndentation.TextEdit edit = LuaEditorIndentation.tabEdit("abc", 1, 1, false);

        assertEquals(1, edit.start());
        assertEquals(1, edit.end());
        assertEquals("    ", edit.replacement());
        assertEquals(5, edit.selectionStart());
        assertEquals(5, edit.selectionEnd());
    }

    @Test
    void tabIndentsSelectedLines() {
        LuaEditorIndentation.TextEdit edit = LuaEditorIndentation.tabEdit("alpha\nbeta", 0, 10, false);

        assertEquals(0, edit.start());
        assertEquals(10, edit.end());
        assertEquals("    alpha\n    beta", edit.replacement());
        assertEquals(0, edit.selectionStart());
        assertEquals(18, edit.selectionEnd());
    }

    @Test
    void shiftTabUnindentsSelectedLinesByFourSpaces() {
        LuaEditorIndentation.TextEdit edit = LuaEditorIndentation.tabEdit("    alpha\n  beta\n\tgamma", 0, 23, true);

        assertEquals("alpha\nbeta\ngamma", edit.replacement());
    }

    @Test
    void shiftTabUnindentsCurrentLineAndMovesCaretBack() {
        LuaEditorIndentation.TextEdit edit = LuaEditorIndentation.tabEdit("    mesh.log()", 6, 6, true);

        assertEquals(0, edit.start());
        assertEquals(14, edit.end());
        assertEquals("mesh.log()", edit.replacement());
        assertEquals(2, edit.selectionStart());
        assertEquals(2, edit.selectionEnd());
    }
}
