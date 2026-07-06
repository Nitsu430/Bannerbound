package com.bannerbound.core.api.settlement;

/**
 * Refusal-probability tables keyed on compliance (0..100), one probability per refusal scenario:
 * refuseWorkstation (workstation assignment, the common case), refuseRodTask (Foreman's Rod task
 * pick - same shape but a touch sharper at the low end to keep on-the-fly orders snappy), and
 * refuseFullDay (dawn full-day-strike - caller must gate it to compliance &lt;= 30 since the
 * FULL_DAY anchors go to zero above 30). All three are step-functions on hand-picked compliance
 * breakpoints with linear interpolation between adjacent anchors, so compliance 53 reads between
 * the 50 and 55 anchors rather than stair-stepping. Caller rolls a uniform RNG 0..1 and compares.
 *
 * <p>Tuning intent: at compliance &gt;= 61 nothing ever refuses; mid (30-60) is the friction
 * band; below ~25 workstation assignments refuse most of the time AND the dawn strike starts
 * firing; sub-15 is "essentially on permanent strike" - both the workstation and rod tables hit
 * 1.00 there. Anchor rows are (compliance, probability) in ascending compliance and must stay so;
 * interpolate does a linear scan (tables are tiny, no binary search needed) and clamps below the
 * first / above the last anchor rather than throwing.
 */
public final class ComplianceTables {
    private ComplianceTables() {
    }

    public static double refuseWorkstation(int compliance) {
        return interpolate(compliance, WORKSTATION);
    }

    public static double refuseRodTask(int compliance) {
        return interpolate(compliance, ROD_TASK);
    }

    public static double refuseFullDay(int compliance) {
        return interpolate(compliance, FULL_DAY);
    }

    private static final double[][] WORKSTATION = {
        {  0.0, 1.00 }, { 15.0, 1.00 }, { 20.0, 0.75 }, { 25.0, 0.60 },
        { 30.0, 0.40 }, { 35.0, 0.30 }, { 40.0, 0.20 }, { 45.0, 0.15 },
        { 50.0, 0.10 }, { 55.0, 0.05 }, { 60.0, 0.02 }, { 61.0, 0.00 },
        {100.0, 0.00 }
    };
    private static final double[][] ROD_TASK = {
        {  0.0, 1.00 }, { 15.0, 1.00 }, { 20.0, 0.75 }, { 25.0, 0.60 },
        { 30.0, 0.40 }, { 35.0, 0.30 }, { 40.0, 0.25 }, { 45.0, 0.20 },
        { 50.0, 0.15 }, { 55.0, 0.10 }, { 60.0, 0.05 }, { 61.0, 0.00 },
        {100.0, 0.00 }
    };
    private static final double[][] FULL_DAY = {
        {  0.0, 1.00 }, { 10.0, 0.75 }, { 15.0, 0.75 }, { 20.0, 0.50 },
        { 25.0, 0.50 }, { 30.0, 0.20 }, { 31.0, 0.00 }, {100.0, 0.00 }
    };

    private static double interpolate(int compliance, double[][] table) {
        double c = Math.max(0, Math.min(100, compliance));
        if (c <= table[0][0]) return table[0][1];
        for (int i = 1; i < table.length; i++) {
            if (c <= table[i][0]) {
                double lo = table[i - 1][0], loP = table[i - 1][1];
                double hi = table[i][0],     hiP = table[i][1];
                if (hi == lo) return hiP;
                double t = (c - lo) / (hi - lo);
                return loP + t * (hiP - loP);
            }
        }
        return table[table.length - 1][1];
    }
}
