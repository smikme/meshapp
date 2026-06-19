package com.meshtastic.client.lua;

import java.util.List;

/**
 * One named series for a chart embedded in a Lua extension form.
 */
public record LuaFormChartSeries(String name,
                                 String color,
                                 List<LuaFormChartPoint> points) {
}
