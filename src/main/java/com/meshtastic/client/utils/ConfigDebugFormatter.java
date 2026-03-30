package com.meshtastic.client.utils;

import org.meshtastic.proto.ConfigProtos;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Вспомогательное форматирование для отладки проблем сохранения конфигурации.
 */
public final class ConfigDebugFormatter {

    private ConfigDebugFormatter() {}

    public static String describeIgnoreIncoming(ConfigProtos.Config config) {
        if (config == null) {
            return "null";
        }
        if (config.getPayloadVariantCase() != ConfigProtos.Config.PayloadVariantCase.LORA) {
            return "not-lora(" + config.getPayloadVariantCase() + ")";
        }
        return describeNodeNums(config.getLora().getIgnoreIncomingList());
    }

    public static String describeNodeNums(List<Integer> nodeNums) {
        if (nodeNums == null || nodeNums.isEmpty()) {
            return "count=0 []";
        }
        return "count=" + nodeNums.size() + " ["
                + nodeNums.stream()
                .map(ConfigDebugFormatter::formatNodeNum)
                .collect(Collectors.joining(", "))
                + "]";
    }

    public static String describeNodeNumObjects(List<?> values) {
        if (values == null || values.isEmpty()) {
            return "count=0 []";
        }
        return "count=" + values.size() + " ["
                + values.stream()
                .map(ConfigDebugFormatter::formatObjectNodeNum)
                .collect(Collectors.joining(", "))
                + "]";
    }

    public static String formatNodeNum(int rawValue) {
        return String.format("!%08x(%d)", rawValue, Integer.toUnsignedLong(rawValue));
    }

    public static String formatObjectNodeNum(Object value) {
        if (value instanceof Number number) {
            return formatNodeNum(number.intValue());
        }
        return String.valueOf(value);
    }
}
