package com.meshtastic.client.lua.api;

import com.meshtastic.client.components.EmojiImageCache;
import com.meshtastic.client.components.EmojiTextFlow;
import com.meshtastic.client.lua.LuaCanvasBridge;
import com.meshtastic.client.lua.LuaCanvasKeyState;
import com.meshtastic.client.lua.LuaCanvasMouseState;
import com.meshtastic.client.lua.LuaCanvasOptions;
import com.meshtastic.client.lua.LuaCanvasSize;
import com.meshtastic.client.utils.UnicodeTextUtils;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;

import java.util.List;
import java.util.Locale;

/**
 * 2D canvas API exposed to the Lua sandbox.
 */
public final class LuaCanvasApi {

    private static final double DEFAULT_WIDTH = 640;
    private static final double DEFAULT_HEIGHT = 360;
    private static final double MAX_LINE_WIDTH = 128;
    private static final double MAX_FONT_SIZE = 256;
    private static final double CANVAS_EMOJI_SIZE_RATIO = 1.2;
    private static final double CANVAS_EMOJI_BASELINE_RATIO = 0.82;

    private final LuaSandboxContext context;

    public LuaCanvasApi(LuaSandboxContext context) {
        this.context = context;
    }

    /**
     * Creates the Lua table for {@code mesh.canvas}.
     *
     * @return canvas API table
     */
    public LuaTable create() {
        LuaTable canvas = new LuaTable();
        canvas.set("open", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                bridge().openCanvas(readOptions(args.arg1()));
                context.deferExecutionDeadline();
                return LuaValue.TRUE;
            }
        });
        canvas.set("close", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                bridge().closeCanvas();
                context.deferExecutionDeadline();
                return LuaValue.TRUE;
            }
        });
        canvas.set("set_fps", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue fpsArg) {
                bridge().setCanvasFrameRate(clamp(fpsArg.checkdouble(), 0, 120));
                context.deferExecutionDeadline();
                return LuaValue.TRUE;
            }
        });
        canvas.set("size", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return sizeToTable(bridge().canvasSize());
            }
        });
        canvas.set("mouse", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return mouseToTable(bridge().canvasMouseState());
            }
        });
        canvas.set("keys", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return keysToTable(bridge().canvasKeyState());
            }
        });
        canvas.set("clear", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Color color = optionalColor(args.arg1());
                return enqueue(gc -> {
                    if (color == null) {
                        gc.clearRect(0, 0, gc.getCanvas().getWidth(), gc.getCanvas().getHeight());
                    } else {
                        Paint old = gc.getFill();
                        gc.setFill(color);
                        gc.fillRect(0, 0, gc.getCanvas().getWidth(), gc.getCanvas().getHeight());
                        gc.setFill(old);
                    }
                });
            }
        });
        canvas.set("set_fill", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue colorArg) {
                Color color = readColor(colorArg);
                return enqueue(gc -> gc.setFill(color));
            }
        });
        canvas.set("set_stroke", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue colorArg) {
                Color color = readColor(colorArg);
                return enqueue(gc -> gc.setStroke(color));
            }
        });
        canvas.set("set_line_width", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue widthArg) {
                double width = checkedPositive(widthArg.checkdouble(), "line width", MAX_LINE_WIDTH);
                return enqueue(gc -> gc.setLineWidth(width));
            }
        });
        canvas.set("set_font", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                double size = checkedPositive(args.arg(1).checkdouble(), "font size", MAX_FONT_SIZE);
                String family = args.arg(2).isnil() ? Font.getDefault().getFamily() : args.arg(2).checkjstring();
                FontWeight weight = fontWeight(args.arg(3));
                Font font = weight != null ? Font.font(family, weight, size) : Font.font(family, size);
                return enqueue(gc -> gc.setFont(font));
            }
        });
        canvas.set("save", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return enqueue(gc -> gc.save());
            }
        });
        canvas.set("restore", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return enqueue(gc -> gc.restore());
            }
        });
        canvas.set("translate", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                double x = finite(args.arg(1).checkdouble(), "x");
                double y = finite(args.arg(2).checkdouble(), "y");
                return enqueue(gc -> gc.translate(x, y));
            }
        });
        canvas.set("rotate", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue degreesArg) {
                double degrees = finite(degreesArg.checkdouble(), "degrees");
                return enqueue(gc -> gc.rotate(degrees));
            }
        });
        canvas.set("scale", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                double x = finite(args.arg(1).checkdouble(), "x");
                double y = args.arg(2).isnil() ? x : finite(args.arg(2).checkdouble(), "y");
                return enqueue(gc -> gc.scale(x, y));
            }
        });

        canvas.set("fill_rect", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Rect rect = readRect(args);
                Color color = optionalColor(args.arg(5));
                return enqueue(gc -> withFill(gc, color, () -> gc.fillRect(rect.x(), rect.y(), rect.w(), rect.h())));
            }
        });
        canvas.set("stroke_rect", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Rect rect = readRect(args);
                Color color = optionalColor(args.arg(5));
                Double lineWidth = optionalLineWidth(args.arg(6));
                return enqueue(gc -> withStroke(gc, color, lineWidth,
                        () -> gc.strokeRect(rect.x(), rect.y(), rect.w(), rect.h())));
            }
        });
        canvas.set("fill_round_rect", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Rect rect = readRect(args);
                double radius = checkedNonNegative(args.arg(5).checkdouble(), "radius");
                Color color = optionalColor(args.arg(6));
                return enqueue(gc -> withFill(gc, color,
                        () -> gc.fillRoundRect(rect.x(), rect.y(), rect.w(), rect.h(), radius, radius)));
            }
        });
        canvas.set("stroke_round_rect", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Rect rect = readRect(args);
                double radius = checkedNonNegative(args.arg(5).checkdouble(), "radius");
                Color color = optionalColor(args.arg(6));
                Double lineWidth = optionalLineWidth(args.arg(7));
                return enqueue(gc -> withStroke(gc, color, lineWidth,
                        () -> gc.strokeRoundRect(rect.x(), rect.y(), rect.w(), rect.h(), radius, radius)));
            }
        });
        canvas.set("line", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                double x1 = finite(args.arg(1).checkdouble(), "x1");
                double y1 = finite(args.arg(2).checkdouble(), "y1");
                double x2 = finite(args.arg(3).checkdouble(), "x2");
                double y2 = finite(args.arg(4).checkdouble(), "y2");
                Color color = optionalColor(args.arg(5));
                Double lineWidth = optionalLineWidth(args.arg(6));
                return enqueue(gc -> withStroke(gc, color, lineWidth, () -> gc.strokeLine(x1, y1, x2, y2)));
            }
        });
        canvas.set("fill_circle", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Circle circle = readCircle(args);
                Color color = optionalColor(args.arg(4));
                return enqueue(gc -> withFill(gc, color,
                        () -> gc.fillOval(circle.x() - circle.r(), circle.y() - circle.r(),
                                circle.r() * 2, circle.r() * 2)));
            }
        });
        canvas.set("stroke_circle", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Circle circle = readCircle(args);
                Color color = optionalColor(args.arg(4));
                Double lineWidth = optionalLineWidth(args.arg(5));
                return enqueue(gc -> withStroke(gc, color, lineWidth,
                        () -> gc.strokeOval(circle.x() - circle.r(), circle.y() - circle.r(),
                                circle.r() * 2, circle.r() * 2)));
            }
        });
        canvas.set("fill_ellipse", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Rect rect = readRect(args);
                Color color = optionalColor(args.arg(5));
                return enqueue(gc -> withFill(gc, color, () -> gc.fillOval(rect.x(), rect.y(), rect.w(), rect.h())));
            }
        });
        canvas.set("stroke_ellipse", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Rect rect = readRect(args);
                Color color = optionalColor(args.arg(5));
                Double lineWidth = optionalLineWidth(args.arg(6));
                return enqueue(gc -> withStroke(gc, color, lineWidth,
                        () -> gc.strokeOval(rect.x(), rect.y(), rect.w(), rect.h())));
            }
        });
        canvas.set("fill_polygon", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Points points = readPoints(args.arg(1));
                Color color = optionalColor(args.arg(2));
                return enqueue(gc -> withFill(gc, color,
                        () -> gc.fillPolygon(points.xs(), points.ys(), points.count())));
            }
        });
        canvas.set("stroke_polygon", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Points points = readPoints(args.arg(1));
                Color color = optionalColor(args.arg(2));
                Double lineWidth = optionalLineWidth(args.arg(3));
                return enqueue(gc -> withStroke(gc, color, lineWidth,
                        () -> gc.strokePolygon(points.xs(), points.ys(), points.count())));
            }
        });
        canvas.set("polyline", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                Points points = readPoints(args.arg(1));
                Color color = optionalColor(args.arg(2));
                Double lineWidth = optionalLineWidth(args.arg(3));
                return enqueue(gc -> withStroke(gc, color, lineWidth,
                        () -> gc.strokePolyline(points.xs(), points.ys(), points.count())));
            }
        });
        canvas.set("fill_text", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String text = args.arg(1).checkjstring();
                double x = finite(args.arg(2).checkdouble(), "x");
                double y = finite(args.arg(3).checkdouble(), "y");
                Color color = optionalColor(args.arg(4));
                return enqueue(gc -> withFill(gc, color, () -> fillText(gc, text, x, y)));
            }
        });
        canvas.set("stroke_text", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String text = args.arg(1).checkjstring();
                double x = finite(args.arg(2).checkdouble(), "x");
                double y = finite(args.arg(3).checkdouble(), "y");
                Color color = optionalColor(args.arg(4));
                Double lineWidth = optionalLineWidth(args.arg(5));
                return enqueue(gc -> withStroke(gc, color, lineWidth, () -> strokeText(gc, text, x, y)));
            }
        });
        canvas.set("text", canvas.get("fill_text"));
        return canvas;
    }

    private LuaCanvasBridge bridge() {
        LuaCanvasBridge bridge = context.canvasBridge();
        if (bridge == null) {
            throw new LuaError("mesh.canvas: UI context is not available");
        }
        return bridge;
    }

    private LuaValue enqueue(com.meshtastic.client.lua.LuaCanvasDrawCommand command) {
        bridge().enqueueCanvasDraw(command);
        return LuaValue.TRUE;
    }

    private LuaCanvasOptions readOptions(LuaValue value) {
        if (value == null || value.isnil()) {
            return new LuaCanvasOptions("", DEFAULT_WIDTH, DEFAULT_HEIGHT, "", true, 0);
        }
        if (value.isstring()) {
            return new LuaCanvasOptions(value.checkjstring(), DEFAULT_WIDTH, DEFAULT_HEIGHT, "", true, 0);
        }
        LuaTable table = value.checktable();
        return new LuaCanvasOptions(
                optionalString(table, "title", ""),
                optionalDouble(table, "width", DEFAULT_WIDTH),
                optionalDouble(table, "height", DEFAULT_HEIGHT),
                optionalString(table, "background", ""),
                optionalBoolean(table, "resizable", true),
                optionalDouble(table, "fps", 0));
    }

    private LuaTable sizeToTable(LuaCanvasSize size) {
        LuaTable table = new LuaTable();
        table.set("width", LuaValue.valueOf(size.width()));
        table.set("height", LuaValue.valueOf(size.height()));
        return table;
    }

    private LuaTable mouseToTable(LuaCanvasMouseState state) {
        LuaTable table = new LuaTable();
        table.set("x", LuaValue.valueOf(state.x()));
        table.set("y", LuaValue.valueOf(state.y()));
        table.set("screen_x", LuaValue.valueOf(state.screenX()));
        table.set("screen_y", LuaValue.valueOf(state.screenY()));
        table.set("over", LuaValue.valueOf(state.over()));
        table.set("pressed", LuaValue.valueOf(state.pressed()));
        table.set("primary", LuaValue.valueOf(state.primaryDown()));
        table.set("middle", LuaValue.valueOf(state.middleDown()));
        table.set("secondary", LuaValue.valueOf(state.secondaryDown()));
        table.set("button", LuaValue.valueOf(state.button()));
        table.set("click_count", LuaValue.valueOf(state.clickCount()));
        table.set("wheel_delta_x", LuaValue.valueOf(state.wheelDeltaX()));
        table.set("wheel_delta_y", LuaValue.valueOf(state.wheelDeltaY()));
        table.set("last_type", LuaValue.valueOf(state.lastType()));
        table.set("time", LuaValue.valueOf(state.timeSeconds()));
        return table;
    }

    private LuaTable keysToTable(LuaCanvasKeyState state) {
        LuaTable table = new LuaTable();
        LuaTable pressed = new LuaTable();
        int index = 1;
        for (String code : state.pressedCodes()) {
            pressed.set(index++, LuaValue.valueOf(code));
            table.set(code, LuaValue.TRUE);
        }
        table.set("pressed", pressed);
        table.set("last_type", LuaValue.valueOf(state.lastType()));
        table.set("last_code", LuaValue.valueOf(state.lastCode()));
        table.set("last_key", LuaValue.valueOf(state.lastKey()));
        table.set("text", LuaValue.valueOf(state.text()));
        table.set("shift", LuaValue.valueOf(state.shiftDown()));
        table.set("ctrl", LuaValue.valueOf(state.controlDown()));
        table.set("alt", LuaValue.valueOf(state.altDown()));
        table.set("meta", LuaValue.valueOf(state.metaDown()));
        table.set("time", LuaValue.valueOf(state.timeSeconds()));
        return table;
    }

    private Rect readRect(Varargs args) {
        return new Rect(
                finite(args.arg(1).checkdouble(), "x"),
                finite(args.arg(2).checkdouble(), "y"),
                finite(args.arg(3).checkdouble(), "width"),
                finite(args.arg(4).checkdouble(), "height"));
    }

    private Circle readCircle(Varargs args) {
        return new Circle(
                finite(args.arg(1).checkdouble(), "x"),
                finite(args.arg(2).checkdouble(), "y"),
                checkedNonNegative(args.arg(3).checkdouble(), "radius"));
    }

    private Points readPoints(LuaValue value) {
        LuaTable table = value.checktable();
        int length = table.length();
        if (length <= 0) {
            throw new LuaError("mesh.canvas: points table is empty");
        }
        LuaValue first = table.get(1);
        if (first.istable()) {
            double[] xs = new double[length];
            double[] ys = new double[length];
            for (int i = 0; i < length; i++) {
                LuaTable point = table.get(i + 1).checktable();
                xs[i] = finite(point.get("x").checkdouble(), "point.x");
                ys[i] = finite(point.get("y").checkdouble(), "point.y");
            }
            return new Points(xs, ys, length);
        }
        if (length % 2 != 0) {
            throw new LuaError("mesh.canvas: flat points table must contain x/y pairs");
        }
        int count = length / 2;
        double[] xs = new double[count];
        double[] ys = new double[count];
        for (int i = 0; i < count; i++) {
            xs[i] = finite(table.get(i * 2 + 1).checkdouble(), "point.x");
            ys[i] = finite(table.get(i * 2 + 2).checkdouble(), "point.y");
        }
        return new Points(xs, ys, count);
    }

    private static void withFill(javafx.scene.canvas.GraphicsContext gc, Color color, Runnable draw) {
        if (color == null) {
            draw.run();
            return;
        }
        Paint old = gc.getFill();
        gc.setFill(color);
        draw.run();
        gc.setFill(old);
    }

    private static void withStroke(javafx.scene.canvas.GraphicsContext gc, Color color, Double lineWidth, Runnable draw) {
        Paint oldStroke = gc.getStroke();
        double oldWidth = gc.getLineWidth();
        if (color != null) {
            gc.setStroke(color);
        }
        if (lineWidth != null) {
            gc.setLineWidth(lineWidth);
        }
        draw.run();
        if (lineWidth != null) {
            gc.setLineWidth(oldWidth);
        }
        if (color != null) {
            gc.setStroke(oldStroke);
        }
    }

    private static void fillText(javafx.scene.canvas.GraphicsContext gc, String value, double x, double y) {
        drawText(gc, value, x, y, false);
    }

    private static void strokeText(javafx.scene.canvas.GraphicsContext gc, String value, double x, double y) {
        drawText(gc, value, x, y, true);
    }

    private static void drawText(javafx.scene.canvas.GraphicsContext gc,
                                 String value,
                                 double x,
                                 double y,
                                 boolean stroke) {
        String text = UnicodeTextUtils.sanitizeForJavaFxDisplay(value);
        if (text.isEmpty()) {
            return;
        }

        List<EmojiTextFlow.Segment> segments = EmojiTextFlow.parseSegments(text);
        if (!containsEmojiSegment(segments)) {
            drawPlainText(gc, text, x, y, stroke);
            return;
        }

        Font font = gc.getFont() != null ? gc.getFont() : Font.getDefault();
        double emojiSize = Math.max(12, font.getSize() * CANVAS_EMOJI_SIZE_RATIO);
        double cursorX = x;
        for (EmojiTextFlow.Segment segment : segments) {
            String segmentText = UnicodeTextUtils.sanitizeForJavaFxDisplay(segment.text());
            if (segmentText.isEmpty()) {
                continue;
            }
            if (segment.isEmoji()) {
                Image image = EmojiImageCache.getImage(segmentText);
                if (image != null) {
                    gc.drawImage(
                            image,
                            cursorX,
                            y - emojiSize * CANVAS_EMOJI_BASELINE_RATIO,
                            emojiSize,
                            emojiSize);
                    cursorX += emojiSize;
                    continue;
                }
            }
            drawPlainText(gc, segmentText, cursorX, y, stroke);
            cursorX += textWidth(segmentText, font);
        }
    }

    private static boolean containsEmojiSegment(List<EmojiTextFlow.Segment> segments) {
        for (EmojiTextFlow.Segment segment : segments) {
            if (segment.isEmoji()) {
                return true;
            }
        }
        return false;
    }

    private static void drawPlainText(javafx.scene.canvas.GraphicsContext gc,
                                      String text,
                                      double x,
                                      double y,
                                      boolean stroke) {
        if (stroke) {
            gc.strokeText(text, x, y);
        } else {
            gc.fillText(text, x, y);
        }
    }

    private static double textWidth(String value, Font font) {
        Text text = new Text(value);
        text.setFont(font);
        return text.getLayoutBounds().getWidth();
    }

    private static Color optionalColor(LuaValue value) {
        return value == null || value.isnil() ? null : readColor(value);
    }

    private static Color readColor(LuaValue value) {
        if (value.isstring()) {
            try {
                return Color.web(value.checkjstring());
            } catch (IllegalArgumentException e) {
                throw new LuaError("mesh.canvas: invalid color " + value.checkjstring());
            }
        }
        LuaTable table = value.checktable();
        double r = colorComponent(table.get("r"), 0);
        double g = colorComponent(table.get("g"), 0);
        double b = colorComponent(table.get("b"), 0);
        double a = colorComponent(table.get("a"), 1);
        return new Color(r, g, b, a);
    }

    private static double colorComponent(LuaValue value, double defaultValue) {
        if (value == null || value.isnil()) {
            return defaultValue;
        }
        double component = value.checkdouble();
        if (component > 1.0) {
            component /= 255.0;
        }
        return clamp(component, 0, 1);
    }

    private static Double optionalLineWidth(LuaValue value) {
        return value == null || value.isnil()
                ? null
                : checkedPositive(value.checkdouble(), "line width", MAX_LINE_WIDTH);
    }

    private static double checkedPositive(double value, String name, double max) {
        double checked = finite(value, name);
        if (checked <= 0 || checked > max) {
            throw new LuaError("mesh.canvas: " + name + " must be between 0 and " + max);
        }
        return checked;
    }

    private static double checkedNonNegative(double value, String name) {
        double checked = finite(value, name);
        if (checked < 0) {
            throw new LuaError("mesh.canvas: " + name + " must be non-negative");
        }
        return checked;
    }

    private static double finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new LuaError("mesh.canvas: " + name + " must be finite");
        }
        return value;
    }

    private static FontWeight fontWeight(LuaValue value) {
        if (value == null || value.isnil()) {
            return null;
        }
        String normalized = value.checkjstring().trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank() || "NORMAL".equals(normalized)) {
            return FontWeight.NORMAL;
        }
        if ("BOLD".equals(normalized)) {
            return FontWeight.BOLD;
        }
        try {
            return FontWeight.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new LuaError("mesh.canvas: invalid font weight " + value.checkjstring());
        }
    }

    private static String optionalString(LuaTable table, String key, String defaultValue) {
        LuaValue value = table.get(key);
        return value.isnil() ? defaultValue : value.checkjstring();
    }

    private static double optionalDouble(LuaTable table, String key, double defaultValue) {
        LuaValue value = table.get(key);
        return value.isnil() ? defaultValue : finite(value.checkdouble(), key);
    }

    private static boolean optionalBoolean(LuaTable table, String key, boolean defaultValue) {
        LuaValue value = table.get(key);
        return value.isnil() ? defaultValue : value.checkboolean();
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private record Rect(double x, double y, double w, double h) {}

    private record Circle(double x, double y, double r) {}

    private record Points(double[] xs, double[] ys, int count) {}
}
