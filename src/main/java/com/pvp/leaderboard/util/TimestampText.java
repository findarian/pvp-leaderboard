package com.pvp.leaderboard.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Discord-style timestamps for the side panel (operator decision
 * 2026-09-22): an epoch the server already sends is rendered the way a
 * Discord {@code <t:X:f>} / {@code <t:X:R>} pair renders — in the viewer's
 * own zone, with a relative phrase that the caller re-renders on its
 * existing tick — so a "registration closes in N minutes" never has to be
 * pushed and never goes stale.
 *
 * <p>Pure: the zone and "now" are arguments (the panel passes its injected
 * clock), so the same epoch renders identically in a test and in game.
 * A {@code null}, zero or negative epoch renders nothing.
 *
 * <p>The relative ladder is moment.js {@code fromNow} (what a Discord
 * {@code <t:X:R>} renders), with compact units for a 240 px panel. Each
 * unit is rounded half-up first, then: {@code < 45 s} "in a moment" /
 * "just now" · minutes {@code <= 1} "1 min" · minutes {@code < 45}
 * "N min" · hours {@code <= 1} "1 h" · hours {@code < 22} "N h" · days
 * {@code <= 1} "1 day" · days {@code < 26} "N days" · months {@code <= 1}
 * "1 month" · months {@code < 11} "N months" · years {@code <= 1}
 * "1 year" · else "N years". So the switches fall at 45 s, 90 s,
 * 44.5 min, 90 min, 21.5 h, 36 h, 25.5 days, ~45.7 days (a month is
 * 30.44 days), ~320 days and ~548 days.
 */
public final class TimestampText
{
    private static final DateTimeFormatter SAME_YEAR = DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter OTHER_YEAR = DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    private static final long MINUTE = 60L;
    private static final long HOUR = 60L * MINUTE;
    private static final long DAY = 24L * HOUR;
    /** moment.js: 146097 days per 4800 months. */
    private static final double MONTH = 146097.0 / 4800.0 * DAY;
    private static final double YEAR = 365.25 * DAY;

    private TimestampText()
    {
    }

    /** {@code <t:X:f>} in the viewer's zone: {@code "today 19:00"},
     *  {@code "tomorrow 09:30"}, {@code "yesterday 23:59"},
     *  {@code "Thu 24 Sep 19:00"}, or with the year when it differs from
     *  now's ({@code "Fri 1 Jan 2027 00:00"}). {@code ""} for no epoch. */
    public static String local(Long epochS, ZoneId zone, long nowMs)
    {
        if (epochS == null || epochS <= 0L) return "";
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        ZonedDateTime target = Instant.ofEpochSecond(epochS).atZone(z);
        ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(z);
        long dayDiff = ChronoUnit.DAYS.between(now.toLocalDate(), target.toLocalDate());
        if (dayDiff == 0) return "today " + TIME.format(target);
        if (dayDiff == 1) return "tomorrow " + TIME.format(target);
        if (dayDiff == -1) return "yesterday " + TIME.format(target);
        LocalDate targetDate = target.toLocalDate();
        return (targetDate.getYear() == now.getYear() ? SAME_YEAR : OTHER_YEAR).format(target);
    }

    /** {@code <t:X:R>}: {@code "in 12 min"}, {@code "3 h ago"},
     *  {@code "in 3 days"}, {@code "in a moment"} / {@code "just now"}
     *  inside 45 s. {@code ""} for no epoch. */
    public static String relative(Long epochS, long nowMs)
    {
        if (epochS == null || epochS <= 0L) return "";
        long deltaS = epochS - Math.floorDiv(nowMs, 1000L);
        boolean future = deltaS >= 0;
        long d = Math.abs(deltaS);
        if (d < 45) return future ? "in a moment" : "just now";
        long minutes = round(d, MINUTE), hours = round(d, HOUR), days = round(d, DAY), months = round(d, MONTH), years = round(d, YEAR);
        String unit;
        if (minutes <= 1) unit = "1 min";
        else if (minutes < 45) unit = minutes + " min";
        else if (hours <= 1) unit = "1 h";
        else if (hours < 22) unit = hours + " h";
        else if (days <= 1) unit = "1 day";
        else if (days < 26) unit = days + " days";
        else if (months <= 1) unit = "1 month";
        else if (months < 11) unit = months + " months";
        else if (years <= 1) unit = "1 year";
        else unit = years + " years";
        return future ? "in " + unit : unit + " ago";
    }

    /** {@link #local} + {@code " · "} + {@link #relative}, e.g.
     *  {@code "Thu 24 Sep 19:00 · in 3 days"}. {@code ""} for no epoch. */
    public static String describe(Long epochS, ZoneId zone, long nowMs)
    {
        if (epochS == null || epochS <= 0L) return "";
        return local(epochS, zone, nowMs) + " · " + relative(epochS, nowMs);
    }

    private static long round(long seconds, double unitSeconds)
    {
        return Math.round(seconds / unitSeconds);
    }
}
