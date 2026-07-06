package com.bannerbound.core.social;

/**
 * The four equal pillars of citizen happiness. Every ThoughtType belongs to exactly one; each
 * pillar's satisfaction is computed independently (0..100), and overall happiness is the average of
 * the four (each worth 25). The Citizen screen renders one filling ring per pillar, coloured by
 * {@link #color} (ARGB) and named via {@link #labelKey} (translation key).
 * <ul>
 *   <li>FOOD - nourishment and meals (hunger, eating well, variety, drink).</li>
 *   <li>CULTURE - beauty, family and identity (appeal, children, research, faith).</li>
 *   <li>COMFORT - shelter, health and safety (home, injury, crises, weather).</li>
 *   <li>SOCIETY - work, governance and relationships (employment, policies, friends).</li>
 * </ul>
 */
public enum HappinessCategory {
    FOOD("bannerbound.happiness.food", 0xFF4ECB3B),
    CULTURE("bannerbound.happiness.culture", 0xFFC04EE0),
    COMFORT("bannerbound.happiness.comfort", 0xFF3BA8CB),
    SOCIETY("bannerbound.happiness.society", 0xFFE0A93B);

    public final String labelKey;
    public final int color;

    HappinessCategory(String labelKey, int color) {
        this.labelKey = labelKey;
        this.color = color;
    }
}
