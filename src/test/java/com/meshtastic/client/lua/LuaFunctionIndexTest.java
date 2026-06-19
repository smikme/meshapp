package com.meshtastic.client.lua;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuaFunctionIndexTest {

    @Test
    void indexesNamedFunctionsAndNestedLocalFunctions() {
        String code = """
                function outer(a, b)
                    local function inner(value)
                    end
                end

                function api:send(text)
                end
                """;

        List<LuaFunctionIndex.FunctionNode> roots = LuaFunctionIndex.parse(code);

        assertEquals(2, roots.size());
        assertEquals("outer", roots.get(0).name());
        assertEquals("a, b", roots.get(0).parameters());
        assertEquals(1, roots.get(0).line());
        assertEquals("inner(value)", roots.get(0).children().getFirst().signature());
        assertEquals("api:send(text)", roots.get(1).signature());
    }

    @Test
    void indexesAssignmentStyleFunctions() {
        String code = """
                local callback = function(msg)
                end

                handlers.on_click = function(event, node)
                end
                """;

        List<LuaFunctionIndex.FunctionNode> roots = LuaFunctionIndex.parse(code);

        assertEquals(2, roots.size());
        assertEquals("callback(msg)", roots.get(0).signature());
        assertEquals("handlers.on_click(event, node)", roots.get(1).signature());
    }

    @Test
    void preservesNestingAcrossNonFunctionBlocks() {
        String code = """
                function outer()
                    if mesh.now() > 0 then
                        local function inside()
                        end
                    end
                end

                function sibling()
                end
                """;

        List<LuaFunctionIndex.FunctionNode> roots = LuaFunctionIndex.parse(code);

        assertEquals(2, roots.size());
        assertEquals("outer", roots.get(0).name());
        assertEquals("inside", roots.get(0).children().getFirst().name());
        assertEquals("sibling", roots.get(1).name());
    }

    @Test
    void ignoresFunctionTextInsideCommentsAndStrings() {
        String code = """
                -- function commented(a)
                local text = "function quoted(b)"
                local long = [[
                    function long_string(c)
                ]]

                function real()
                end
                """;

        List<LuaFunctionIndex.FunctionNode> roots = LuaFunctionIndex.parse(code);

        assertEquals(1, roots.size());
        assertEquals("real()", roots.getFirst().signature());
    }

    @Test
    void exposesNavigationOffsetsForFunctionName() {
        String code = "local run = function(msg)\nend\n";

        LuaFunctionIndex.FunctionNode node = LuaFunctionIndex.parse(code).getFirst();

        assertEquals(code.indexOf("function"), node.offset());
        assertEquals(code.indexOf("run"), node.nameStartOffset());
        assertEquals(code.indexOf("run") + "run".length(), node.nameEndOffset());
        assertTrue(node.nameEndOffset() > node.nameStartOffset());
    }
}
