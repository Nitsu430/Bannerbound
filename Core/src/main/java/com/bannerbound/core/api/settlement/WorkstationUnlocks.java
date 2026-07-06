package com.bannerbound.core.api.settlement;

import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

/**
 * Research-gating for worker units and their workstations. A gated unit cannot have its workstation
 * block opened, nor be chosen on the Foreman's Rod, until the settlement has researched the unlock
 * flag bannerbound.unlock.<unit>. That flag is an ordinary research flag (lives in unlocks.flags of a
 * research JSON, queried via ResearchManager.hasFlag / ClientResearchState.hasFlag) and is
 * deliberately SEPARATE from item-unlocking: knowing a workstation block as an item does not let the
 * settlement operate it. Resolution consults the built-in WORKSTATION_UNIT map first, then
 * CitizenJobRegistry jobs; a type absent from both is ungated. Workshop TYPES (e.g. "carpentry") are
 * gated instead by the crafter-profession unit declared via WorkBlockRegistry.registerTypeUnit -- the
 * specialty and its gate come from the workshop, not the generic Crafter job. workstationForUnit is
 * the reverse lookup used to find a migration successor (unit "fisher" -> "fishers_creel"). To gate a
 * future unit: add its workstation TYPE_ID -> short unit-name entry to WORKSTATION_UNIT and put
 * bannerbound.unlock.<unit> in that unit's research JSON.
 */
@ApiStatus.Internal
public final class WorkstationUnlocks {
    private WorkstationUnlocks() {
    }

    private static final Map<String, String> WORKSTATION_UNIT = Map.of(
        "foresters_log",   "forester",
        "diggers_slab",    "digger",
        "farmers_granary", "farmer",
        "fishers_creel",   "fisher",
        "stockpile_rack",  "stocker",
        "foragers_basket", "forager",
        "herders_pen",     "herder"
    );

    public static String flagForUnit(String unitName) {
        return "bannerbound.unlock." + unitName;
    }

    public static String unitForWorkstation(String workstationTypeId) {
        String unit = WORKSTATION_UNIT.get(workstationTypeId);
        return unit != null ? unit : com.bannerbound.core.api.job.CitizenJobRegistry.unitFor(workstationTypeId);
    }

    public static String flagForWorkstation(String workstationTypeId) {
        String unit = unitForWorkstation(workstationTypeId);
        return unit == null ? null : flagForUnit(unit);
    }

    public static String flagForWorkshopType(String workshopTypeId) {
        String unit = com.bannerbound.core.api.workshop.WorkBlockRegistry.unitForType(workshopTypeId);
        return unit == null ? null : flagForUnit(unit);
    }

    public static String workstationForUnit(String unitName) {
        if (unitName == null) return null;
        for (Map.Entry<String, String> e : WORKSTATION_UNIT.entrySet()) {
            if (unitName.equals(e.getValue())) return e.getKey();
        }
        for (com.bannerbound.core.api.job.CitizenJobRegistry.JobDef d
                : com.bannerbound.core.api.job.CitizenJobRegistry.all()) {
            if (unitName.equals(d.unitName())) return d.jobTypeId();
        }
        return null;
    }
}
