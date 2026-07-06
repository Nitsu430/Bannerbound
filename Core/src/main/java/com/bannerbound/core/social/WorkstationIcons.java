package com.bannerbound.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.bannerbound.core.api.job.CitizenJobRegistry;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Two-way table between workstation type-id strings (e.g. "farmers_granary") and the small positive
 * integers carried as the JOB topic's subType in CitizenEntity's DATA_BUBBLE synched slot. The server
 * encodes with ordinalOf when writing the bubble; the client decodes with typeIdOf / itemOrdinal when
 * rendering it, so both sides must agree on the mapping. Ordinal 0 is the "no job" sentinel (renders
 * bubble-only, no inner item icon); built-in TYPE_IDS occupy 1..N and registry-defined jobs follow.
 * itemOrdinal resolves an ordinal to a fresh representative ItemStack (built-ins hardcode a tool item;
 * registry jobs draw their declared iconBaseline) now that the workstation blocks are gone.
 *
 * <p>Wire-format contract: the ordinal is what an existing world's queued bubble payload decodes back
 * through, so the mapping must stay stable -- see the two inline notes on ordering below.
 */
public final class WorkstationIcons {
    // Index+1 IS the wire subType ordinal; APPEND-ONLY, never reorder (breaks queued bubble payloads).
    private static final String[] TYPE_IDS = {
        "foresters_log",
        "diggers_slab",
        "farmers_granary",
        "fishers_creel",
        "stockpile_rack",
        "foragers_basket",
    };

    private WorkstationIcons() {}

    private static List<String> registryJobIds() {
        List<String> ids = new ArrayList<>();
        for (CitizenJobRegistry.JobDef d : CitizenJobRegistry.all()) ids.add(d.jobTypeId());
        Collections.sort(ids); // sort so the ordinal is stable across client/server regardless of registration order.
        return ids;
    }

    public static int ordinalOf(String typeId) {
        if (typeId == null) return 0;
        for (int i = 0; i < TYPE_IDS.length; i++) {
            if (TYPE_IDS[i].equals(typeId)) return i + 1;
        }
        int idx = registryJobIds().indexOf(typeId);
        return idx >= 0 ? TYPE_IDS.length + 1 + idx : 0;
    }

    public static String typeIdOf(int ordinal) {
        if (ordinal <= 0) return null;
        if (ordinal <= TYPE_IDS.length) return TYPE_IDS[ordinal - 1];
        List<String> reg = registryJobIds();
        int idx = ordinal - TYPE_IDS.length - 1;
        return idx < reg.size() ? reg.get(idx) : null;
    }

    public static ItemStack itemOrdinal(int ordinal) {
        String id = typeIdOf(ordinal);
        if (id == null) return ItemStack.EMPTY;
        Item item = switch (id) {
            case "foresters_log"   -> Items.IRON_AXE;
            case "diggers_slab"    -> Items.IRON_SHOVEL;
            case "farmers_granary" -> Items.IRON_HOE;
            case "fishers_creel"   -> Items.FISHING_ROD;
            case "stockpile_rack"  -> Items.CHEST;
            case "foragers_basket" -> Items.POPPY;
            default -> null;
        };
        if (item == null) {
            CitizenJobRegistry.JobDef d = CitizenJobRegistry.byId(id);
            if (d != null) item = d.iconBaseline();
        }
        if (item == null || item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item);
    }
}
