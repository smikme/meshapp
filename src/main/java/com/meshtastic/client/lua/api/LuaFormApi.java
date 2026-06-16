package com.meshtastic.client.lua.api;

import com.meshtastic.client.lua.LuaFormBridge;
import com.meshtastic.client.lua.LuaFormComponentSpec;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedded extension-form functions exposed to the Lua sandbox.
 */
public final class LuaFormApi {

    private final LuaSandboxContext context;

    public LuaFormApi(LuaSandboxContext context) {
        this.context = context;
    }

    /**
     * Creates the Lua table for {@code mesh.form}.
     *
     * @return form API table
     */
    public LuaTable create() {
        LuaTable form = new LuaTable();
        form.set("show", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaFormBridge bridge = bridge();
                if (!args.arg1().isnil()) {
                    LuaFormComponentSpec spec = readSpec(args.arg1(), false);
                    if (spec.text() != null && !spec.text().isBlank()) {
                        bridge.setFormTitle(spec.text());
                    }
                }
                bridge.showForm();
                context.deferExecutionDeadline();
                return LuaValue.TRUE;
            }
        });
        form.set("set_title", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue titleArg) {
                bridge().setFormTitle(titleArg.checkjstring());
                context.deferExecutionDeadline();
                return LuaValue.TRUE;
            }
        });
        form.set("clear", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                bridge().clearForm();
                context.deferExecutionDeadline();
                return LuaValue.TRUE;
            }
        });
        form.set("add", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue specArg) {
                String id = bridge().addFormComponent(readSpec(specArg, true));
                context.deferExecutionDeadline();
                return LuaValue.valueOf(id);
            }
        });
        form.set("set", new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue idArg, LuaValue specArg) {
                bridge().updateFormComponent(idArg.checkjstring(), readSpec(specArg, false));
                context.deferExecutionDeadline();
                return LuaValue.TRUE;
            }
        });
        form.set("remove", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue idArg) {
                bridge().removeFormComponent(idArg.checkjstring());
                context.deferExecutionDeadline();
                return LuaValue.TRUE;
            }
        });
        form.set("value", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue idArg) {
                return objectToLua(bridge().formComponentValue(idArg.checkjstring()));
            }
        });
        return form;
    }

    private LuaFormBridge bridge() {
        LuaFormBridge bridge = context.formBridge();
        if (bridge == null || !bridge.isFormAvailable()) {
            throw new LuaError("No active extension form");
        }
        return bridge;
    }

    private LuaFormComponentSpec readSpec(LuaValue value, boolean requireType) {
        if (value == null || value.isnil()) {
            if (requireType) {
                throw new LuaError("mesh.form.add: component options are required");
            }
            return emptySpec();
        }
        if (value.isstring()) {
            String type = value.checkjstring();
            return new LuaFormComponentSpec(null, type, null, null, null, null, List.of(),
                    null, null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }

        LuaTable table = value.checktable();
        String type = optionalString(table, "type");
        if (requireType && (type == null || type.isBlank())) {
            throw new LuaError("mesh.form.add: component type is required");
        }
        Object fieldValue = javaValue(table.get("value"));
        if (fieldValue == null && !table.get("selected").isnil()) {
            fieldValue = table.get("selected").checkboolean();
        }
        return new LuaFormComponentSpec(
                optionalString(table, "id"),
                type,
                optionalString(table, "parent"),
                optionalString(table, "text"),
                optionalString(table, "prompt"),
                fieldValue,
                stringList(table.get("items")),
                optionalDouble(table, "min"),
                optionalDouble(table, "max"),
                optionalBoolean(table, "disabled"),
                optionalBoolean(table, "visible"),
                optionalString(table, "style"),
                optionalString(table, "orientation"),
                optionalDouble(table, "width"),
                optionalDouble(table, "height"),
                optionalDouble(table, "min_width", "minWidth"),
                optionalDouble(table, "min_height", "minHeight"),
                optionalDouble(table, "max_width", "maxWidth"),
                optionalDouble(table, "max_height", "maxHeight"),
                optionalBoolean(table, "read_only", "readOnly", "readonly"),
                optionalBoolean(table, "wrap"),
                optionalBoolean(table, "monospace"),
                optionalString(table, "grow"),
                optionalInteger(table, "rows"));
    }

    private static LuaFormComponentSpec emptySpec() {
        return new LuaFormComponentSpec(null, null, null, null, null, null, List.of(),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
    }

    private static Object javaValue(LuaValue value) {
        if (value == null || value.isnil()) {
            return null;
        }
        if (value.isboolean()) {
            return value.checkboolean();
        }
        if (value.isnumber()) {
            return value.checkdouble();
        }
        return value.tojstring();
    }

    private static LuaValue objectToLua(Object value) {
        if (value == null) {
            return LuaValue.NIL;
        }
        if (value instanceof Boolean booleanValue) {
            return LuaValue.valueOf(booleanValue);
        }
        if (value instanceof Number number) {
            return LuaValue.valueOf(number.doubleValue());
        }
        if (value instanceof List<?> list) {
            LuaTable table = new LuaTable();
            for (int i = 0; i < list.size(); i++) {
                table.set(i + 1, objectToLua(list.get(i)));
            }
            return table;
        }
        return LuaValue.valueOf(value.toString());
    }

    private static String optionalString(LuaTable table, String key) {
        LuaValue value = table.get(key);
        return value.isnil() ? null : value.checkjstring();
    }

    private static Double optionalDouble(LuaTable table, String key) {
        LuaValue value = table.get(key);
        return value.isnil() ? null : value.checkdouble();
    }

    private static Double optionalDouble(LuaTable table, String... keys) {
        for (String key : keys) {
            LuaValue value = table.get(key);
            if (!value.isnil()) {
                return value.checkdouble();
            }
        }
        return null;
    }

    private static Boolean optionalBoolean(LuaTable table, String key) {
        LuaValue value = table.get(key);
        return value.isnil() ? null : value.checkboolean();
    }

    private static Boolean optionalBoolean(LuaTable table, String... keys) {
        for (String key : keys) {
            LuaValue value = table.get(key);
            if (!value.isnil()) {
                return value.checkboolean();
            }
        }
        return null;
    }

    private static Integer optionalInteger(LuaTable table, String key) {
        LuaValue value = table.get(key);
        return value.isnil() ? null : value.checkint();
    }

    private static List<String> stringList(LuaValue value) {
        if (value == null || value.isnil()) {
            return List.of();
        }
        LuaTable table = value.checktable();
        List<String> result = new ArrayList<>();
        for (int i = 1; ; i++) {
            LuaValue item = table.get(i);
            if (item.isnil()) {
                break;
            }
            result.add(item.tojstring());
        }
        return List.copyOf(result);
    }
}
