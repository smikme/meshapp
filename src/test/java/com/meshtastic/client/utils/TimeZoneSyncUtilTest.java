package com.meshtastic.client.utils;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
class TimeZoneSyncUtilTest {

    @Test
    void buildsFixedPositiveOffsetTzDef() {
        assertEquals("GMT-3", TimeZoneSyncUtil.buildFixedGmtTzDef(ZoneOffset.ofHours(3)));
    }

    @Test
    void buildsFixedNegativeOffsetTzDefWithMinutes() {
        assertEquals("GMT5:30", TimeZoneSyncUtil.buildFixedGmtTzDef(ZoneOffset.ofHoursMinutes(-5, -30)));
    }

    @Test
    void resolvesCurrentOffsetForFixedTzDef() {
        Instant instant = Instant.parse("2026-03-30T09:00:00Z");
        assertTrue(TimeZoneSyncUtil.matchesCurrentGmtOffset("MSK-3", ZoneOffset.ofHours(3), instant));
    }

    @Test
    void resolvesCurrentOffsetForGmtLiteralTzDef() {
        Instant instant = Instant.parse("2026-03-30T09:00:00Z");
        assertTrue(TimeZoneSyncUtil.matchesCurrentGmtOffset("GMT-3", ZoneOffset.ofHours(3), instant));
    }

    @Test
    void resolvesCurrentOffsetForNorthernHemisphereDst() {
        Instant instant = Instant.parse("2026-03-30T12:00:00Z");
        assertTrue(TimeZoneSyncUtil.matchesCurrentGmtOffset(
                "EST5EDT,M3.2.0,M11.1.0",
                ZoneOffset.ofHours(-4),
                instant));
    }

    @Test
    void resolvesCurrentOffsetForSouthernHemisphereDst() {
        Instant instant = Instant.parse("2026-01-10T00:00:00Z");
        assertTrue(TimeZoneSyncUtil.matchesCurrentGmtOffset(
                "AEST-10AEDT,M10.1.0,M4.1.0/3",
                ZoneOffset.ofHours(11),
                instant));
    }

    @Test
    void invalidTzDefDoesNotMatchExpectedOffset() {
        Instant instant = Instant.parse("2026-03-30T09:00:00Z");
        assertFalse(TimeZoneSyncUtil.matchesCurrentGmtOffset("not-a-tzdef", ZoneOffset.ofHours(3), instant));
    }
}
