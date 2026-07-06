package com.bannerbound.core.celestial;

/**
 * The world calendar (FAITH_PLAN.md): 12 months whose lengths come from config (default 7 days each
 * -> an 84-day year) - and the year IS one observer orbit: {@link SkyField} takes its year length
 * from the calendar, so calendar, seasons and sky are one machine and shortening a month genuinely
 * speeds up the heavens. Pure functions of {@code dayTime}, immutable; the server's month lengths
 * reach clients through the sky payload, and {@link #DEFAULT} (all sevens) is the client fallback
 * until that config arrives.
 *
 * <p>The constructor clamps each of the 12 month lengths to 1..31; a null or short input array falls
 * back to 7s. {@link CalendarDate} month/day are 1-based for display. Month NAMES are a later
 * faith/culture feature (named via the same governance flow as constellations); display falls back
 * to "Month N". Season-mod integration (Serene Seasons / Ecliptic Seasons reading this calendar) is
 * a planned compat layer - see the plan doc.
 */
public final class WorldCalendar {
    public static final int MONTHS = 12;
    public static final int DEFAULT_DAYS_PER_MONTH = 7;
    public static final WorldCalendar DEFAULT = new WorldCalendar(null);

    private final int[] monthDays;
    private final int yearDays;

    public record CalendarDate(long year, int month, int day, int dayOfYear) {
    }

    public WorldCalendar(int[] monthDaysIn) {
        this.monthDays = new int[MONTHS];
        int total = 0;
        for (int i = 0; i < MONTHS; i++) {
            int v = (monthDaysIn != null && i < monthDaysIn.length) ? monthDaysIn[i] : DEFAULT_DAYS_PER_MONTH;
            this.monthDays[i] = Math.max(1, Math.min(31, v));
            total += this.monthDays[i];
        }
        this.yearDays = total;
    }

    public int yearDays() {
        return yearDays;
    }

    public int[] monthDays() {
        return monthDays.clone();
    }

    public CalendarDate fromDayTime(long dayTime) {
        long absDay = Math.floorDiv(dayTime, 24000L);
        long year = Math.floorDiv(absDay, yearDays);
        int dayOfYear = (int) Math.floorMod(absDay, yearDays);
        int rest = dayOfYear;
        for (int m = 0; m < MONTHS; m++) {
            if (rest < monthDays[m]) {
                return new CalendarDate(year, m + 1, rest + 1, dayOfYear);
            }
            rest -= monthDays[m];
        }
        return new CalendarDate(year, MONTHS, monthDays[MONTHS - 1], dayOfYear);
    }
}
