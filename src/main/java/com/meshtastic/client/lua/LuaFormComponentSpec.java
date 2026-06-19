package com.meshtastic.client.lua;

import java.util.List;

/**
 * Sanitized component options passed from Lua to the embedded extension form.
 */
public record LuaFormComponentSpec(String id,
                                   String type,
                                   String parentId,
                                   String text,
                                   String prompt,
                                   Object value,
                                   List<String> items,
                                   Double min,
                                   Double max,
                                   Boolean disabled,
                                   Boolean visible,
                                   String style,
                                   String orientation,
                                   Double width,
                                   Double height,
                                   Double minWidth,
                                   Double minHeight,
                                   Double maxWidth,
                                   Double maxHeight,
                                   Boolean readOnly,
                                   Boolean wrap,
                                   Boolean monospace,
                                   String grow,
                                   Integer rows,
                                   String chartType,
                                   String xLabel,
                                   String yLabel,
                                   String xType,
                                   Boolean legend,
                                   Boolean symbols,
                                   List<LuaFormChartSeries> series) {
}
