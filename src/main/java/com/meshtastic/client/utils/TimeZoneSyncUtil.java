package com.meshtastic.client.utils;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Утилиты для синхронизации часового пояса Meshtastic-ноды с часовым поясом ПК.
 * <p>
 * Нода хранит timezone не как IANA ZoneId, а как POSIX {@code tzdef}. Для UI нам
 * нужно уметь:
 * <ul>
 *   <li>получить текущее GMT-смещение системы</li>
 *   <li>сравнить его с текущим {@code tzdef} ноды</li>
 *   <li>сгенерировать безопасный fixed-offset {@code tzdef} для записи на ноду</li>
 * </ul>
 *
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
public final class TimeZoneSyncUtil {

    private static final int DEFAULT_TRANSITION_TIME_SECONDS = 2 * 60 * 60;

    private TimeZoneSyncUtil() {}

    public static ZoneOffset systemOffset(Instant instant) {
        return ZoneId.systemDefault().getRules().getOffset(instant);
    }

    public static String formatGmtOffset(ZoneOffset offset) {
        String suffix = "Z".equals(offset.getId()) ? "+00:00" : offset.getId();
        return "GMT" + suffix;
    }

    /**
     * Генерирует POSIX tzdef с фиксированным смещением от GMT.
     * <p>
     * Используется как fallback-формат для синхронизации GMT с ПК. Например:
     * <ul>
     *   <li>{@code GMT-3} для UTC+03:00</li>
     *   <li>{@code GMT5} для UTC-05:00</li>
     * </ul>
     */
    public static String buildFixedGmtTzDef(ZoneOffset offset) {
        int posixSeconds = -offset.getTotalSeconds();
        int absSeconds = Math.abs(posixSeconds);
        int hours = absSeconds / 3600;
        int minutes = (absSeconds % 3600) / 60;
        int seconds = absSeconds % 60;

        StringBuilder builder = new StringBuilder("GMT");
        if (posixSeconds < 0) {
            builder.append('-');
        }
        builder.append(hours);
        if (minutes != 0 || seconds != 0) {
            builder.append(':').append(String.format("%02d", minutes));
        }
        if (seconds != 0) {
            builder.append(':').append(String.format("%02d", seconds));
        }
        return builder.toString();
    }

    public static boolean matchesCurrentGmtOffset(String tzdef, ZoneOffset expectedOffset, Instant instant) {
        return resolveCurrentOffset(tzdef, instant)
                .map(expectedOffset::equals)
                .orElse(false);
    }

    public static Optional<ZoneOffset> resolveCurrentOffset(String tzdef, Instant instant) {
        if (tzdef == null || tzdef.isBlank()) {
            return Optional.empty();
        }

        try {
            return Optional.of(PosixTzDef.parse(tzdef.trim()).offsetAt(instant));
        } catch (IllegalArgumentException | DateTimeException e) {
            return Optional.empty();
        }
    }

    private static final class PosixTzDef {
        private final int standardOffsetSeconds;
        private final Integer daylightOffsetSeconds;
        private final TransitionRule daylightStart;
        private final TransitionRule daylightEnd;

        private PosixTzDef(int standardOffsetSeconds,
                           Integer daylightOffsetSeconds,
                           TransitionRule daylightStart,
                           TransitionRule daylightEnd) {
            this.standardOffsetSeconds = standardOffsetSeconds;
            this.daylightOffsetSeconds = daylightOffsetSeconds;
            this.daylightStart = daylightStart;
            this.daylightEnd = daylightEnd;
        }

        static PosixTzDef parse(String text) {
            Cursor cursor = new Cursor(text);

            parseName(cursor);
            int standardOffsetSeconds = parsePosixOffsetSeconds(cursor);

            if (!cursor.hasRemaining()) {
                return new PosixTzDef(standardOffsetSeconds, null, null, null);
            }

            parseName(cursor);

            int daylightOffsetSeconds = cursor.hasRemaining() && isOffsetStart(cursor.current())
                    ? parsePosixOffsetSeconds(cursor)
                    : standardOffsetSeconds + 3600;

            if (!cursor.hasRemaining()) {
                throw new IllegalArgumentException("DST tzdef without transition rules: " + text);
            }
            cursor.expect(',');
            TransitionRule daylightStart = parseTransitionRule(cursor);
            cursor.expect(',');
            TransitionRule daylightEnd = parseTransitionRule(cursor);
            cursor.expectEnd();

            return new PosixTzDef(standardOffsetSeconds, daylightOffsetSeconds, daylightStart, daylightEnd);
        }

        ZoneOffset offsetAt(Instant instant) {
            ZoneOffset standardOffset = ZoneOffset.ofTotalSeconds(standardOffsetSeconds);
            if (daylightOffsetSeconds == null || daylightStart == null || daylightEnd == null) {
                return standardOffset;
            }

            ZoneOffset daylightOffset = ZoneOffset.ofTotalSeconds(daylightOffsetSeconds);
            int year = instant.atOffset(standardOffset).getYear();

            Instant startInstant = daylightStart.toInstant(year, standardOffset);
            Instant endInstant = daylightEnd.toInstant(year, daylightOffset);

            boolean inDaylightTime = startInstant.isBefore(endInstant)
                    ? !instant.isBefore(startInstant) && instant.isBefore(endInstant)
                    : !instant.isBefore(startInstant) || instant.isBefore(endInstant);

            return inDaylightTime ? daylightOffset : standardOffset;
        }
    }

    private enum RuleKind {
        JULIAN_NO_LEAP,
        DAY_OF_YEAR,
        MONTH_WEEK_DAY
    }

    private static final class TransitionRule {
        private final RuleKind kind;
        private final int value;
        private final int month;
        private final int week;
        private final int dayOfWeek;
        private final int timeSeconds;

        private TransitionRule(RuleKind kind, int value, int month, int week, int dayOfWeek, int timeSeconds) {
            this.kind = kind;
            this.value = value;
            this.month = month;
            this.week = week;
            this.dayOfWeek = dayOfWeek;
            this.timeSeconds = timeSeconds;
        }

        static TransitionRule julianNoLeap(int day, int timeSeconds) {
            return new TransitionRule(RuleKind.JULIAN_NO_LEAP, day, 0, 0, 0, timeSeconds);
        }

        static TransitionRule dayOfYear(int day, int timeSeconds) {
            return new TransitionRule(RuleKind.DAY_OF_YEAR, day, 0, 0, 0, timeSeconds);
        }

        static TransitionRule monthWeekDay(int month, int week, int dayOfWeek, int timeSeconds) {
            return new TransitionRule(RuleKind.MONTH_WEEK_DAY, 0, month, week, dayOfWeek, timeSeconds);
        }

        Instant toInstant(int year, ZoneOffset offsetBeforeTransition) {
            LocalDate date = switch (kind) {
                case JULIAN_NO_LEAP -> resolveJulianNoLeapDate(year, value);
                case DAY_OF_YEAR -> resolveDayOfYearDate(year, value);
                case MONTH_WEEK_DAY -> resolveMonthWeekDayDate(year, month, week, dayOfWeek);
            };

            LocalDateTime localDateTime = date.atStartOfDay().plus(timeSeconds, ChronoUnit.SECONDS);
            return localDateTime.toInstant(offsetBeforeTransition);
        }
    }

    private static LocalDate resolveJulianNoLeapDate(int year, int julianDay) {
        int dayOfYear = julianDay;
        if (LocalDate.of(year, 1, 1).isLeapYear() && julianDay >= 60) {
            dayOfYear++;
        }
        return LocalDate.ofYearDay(year, dayOfYear);
    }

    private static LocalDate resolveDayOfYearDate(int year, int dayOfYearZeroBased) {
        return LocalDate.ofYearDay(year, dayOfYearZeroBased + 1);
    }

    private static LocalDate resolveMonthWeekDayDate(int year, int month, int week, int dayOfWeekPosix) {
        LocalDate firstOfMonth = LocalDate.of(year, month, 1);
        int firstPosixDayOfWeek = toPosixDayOfWeek(firstOfMonth.getDayOfWeek().getValue());
        int delta = Math.floorMod(dayOfWeekPosix - firstPosixDayOfWeek, 7);
        LocalDate result = firstOfMonth.plusDays(delta + (long) (week - 1) * 7);
        if (week == 5 && result.getMonthValue() != month) {
            result = result.minusWeeks(1);
        }
        return result;
    }

    private static int toPosixDayOfWeek(int javaDayOfWeek) {
        return javaDayOfWeek % 7;
    }

    private static String parseName(Cursor cursor) {
        if (!cursor.hasRemaining()) {
            throw new IllegalArgumentException("Expected timezone name");
        }

        if (cursor.current() == '<') {
            cursor.advance();
            int start = cursor.position();
            while (cursor.hasRemaining() && cursor.current() != '>') {
                cursor.advance();
            }
            if (!cursor.hasRemaining()) {
                throw new IllegalArgumentException("Unterminated timezone name");
            }
            String name = cursor.substring(start, cursor.position());
            cursor.advance();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("Empty timezone name");
            }
            return name;
        }

        int start = cursor.position();
        while (cursor.hasRemaining() && Character.isLetter(cursor.current())) {
            cursor.advance();
        }
        if (cursor.position() == start) {
            throw new IllegalArgumentException("Expected timezone name");
        }
        return cursor.substring(start, cursor.position());
    }

    private static int parsePosixOffsetSeconds(Cursor cursor) {
        int signedSeconds = parseSignedSeconds(cursor);
        return -signedSeconds;
    }

    private static int parseTransitionTimeSeconds(Cursor cursor) {
        return parseSignedSeconds(cursor);
    }

    private static int parseSignedSeconds(Cursor cursor) {
        int sign = 1;
        if (cursor.hasRemaining() && (cursor.current() == '+' || cursor.current() == '-')) {
            if (cursor.current() == '-') {
                sign = -1;
            }
            cursor.advance();
        }

        int hours = parseNumber(cursor);
        int minutes = 0;
        int seconds = 0;
        if (cursor.hasRemaining() && cursor.current() == ':') {
            cursor.advance();
            minutes = parseNumber(cursor);
            if (cursor.hasRemaining() && cursor.current() == ':') {
                cursor.advance();
                seconds = parseNumber(cursor);
            }
        }
        return sign * (hours * 3600 + minutes * 60 + seconds);
    }

    private static TransitionRule parseTransitionRule(Cursor cursor) {
        if (!cursor.hasRemaining()) {
            throw new IllegalArgumentException("Expected transition rule");
        }

        TransitionRule rule;
        if (cursor.current() == 'J') {
            cursor.advance();
            int day = parseNumber(cursor);
            rule = TransitionRule.julianNoLeap(day, DEFAULT_TRANSITION_TIME_SECONDS);
        } else if (cursor.current() == 'M') {
            cursor.advance();
            int month = parseNumber(cursor);
            cursor.expect('.');
            int week = parseNumber(cursor);
            cursor.expect('.');
            int dayOfWeek = parseNumber(cursor);
            rule = TransitionRule.monthWeekDay(month, week, dayOfWeek, DEFAULT_TRANSITION_TIME_SECONDS);
        } else if (Character.isDigit(cursor.current())) {
            int day = parseNumber(cursor);
            rule = TransitionRule.dayOfYear(day, DEFAULT_TRANSITION_TIME_SECONDS);
        } else {
            throw new IllegalArgumentException("Unsupported transition rule");
        }

        if (cursor.hasRemaining() && cursor.current() == '/') {
            cursor.advance();
            int transitionTimeSeconds = parseTransitionTimeSeconds(cursor);
            return switch (rule.kind) {
                case JULIAN_NO_LEAP -> TransitionRule.julianNoLeap(rule.value, transitionTimeSeconds);
                case DAY_OF_YEAR -> TransitionRule.dayOfYear(rule.value, transitionTimeSeconds);
                case MONTH_WEEK_DAY -> TransitionRule.monthWeekDay(
                        rule.month, rule.week, rule.dayOfWeek, transitionTimeSeconds);
            };
        }
        return rule;
    }

    private static int parseNumber(Cursor cursor) {
        if (!cursor.hasRemaining() || !Character.isDigit(cursor.current())) {
            throw new IllegalArgumentException("Expected number");
        }

        int value = 0;
        while (cursor.hasRemaining() && Character.isDigit(cursor.current())) {
            value = value * 10 + (cursor.current() - '0');
            cursor.advance();
        }
        return value;
    }

    private static boolean isOffsetStart(char ch) {
        return ch == '+' || ch == '-' || Character.isDigit(ch);
    }

    private static final class Cursor {
        private final String text;
        private int index;

        private Cursor(String text) {
            this.text = text;
        }

        boolean hasRemaining() {
            return index < text.length();
        }

        char current() {
            return text.charAt(index);
        }

        int position() {
            return index;
        }

        void advance() {
            index++;
        }

        void expect(char ch) {
            if (!hasRemaining() || current() != ch) {
                throw new IllegalArgumentException("Expected '" + ch + "'");
            }
            advance();
        }

        void expectEnd() {
            if (hasRemaining()) {
                throw new IllegalArgumentException("Unexpected trailing tzdef data: " + text.substring(index));
            }
        }

        String substring(int start, int end) {
            return text.substring(start, end);
        }
    }
}
