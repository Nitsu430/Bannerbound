package com.bannerbound.core.api.settlement;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/**
 * A workstation registered on a settlement: a placed workstation block (Forester's Log etc.) plus
 * the citizen assigned to work it. Inventory lives on the block entity -- this record is the
 * "assignment" half, so workstations can be enumerated for the assignment-picker GUIs even when
 * their chunks aren't loaded. buildingValid caches a BuildingValidator result re-evaluated
 * periodically by ImmigrationManager. active is a player toggle that pauses a station (its gatherer
 * goals yield; the worker patrols + regens stamina) without losing the assignment. useFertilizer is
 * farmer-only opt-in bone-mealing (sourced from the granary or a stockpile), honoured ONLY when the
 * settlement has researched Fertilization -- the work goal re-checks the flag and the network
 * handler refuses to set it true without the research. All three round-trip through NBT with
 * opt-out/opt-in defaults chosen so older saves keep working.
 */
public final class Workstation {
    private final BlockPos pos;
    private final String type;
    private UUID assignedCitizenId;
    private boolean buildingValid;
    private boolean active;
    private boolean useFertilizer;

    public Workstation(BlockPos pos, String type, UUID assignedCitizenId) {
        this.pos = pos;
        this.type = type;
        this.assignedCitizenId = assignedCitizenId;
        this.buildingValid = true;
        this.active = true;
        this.useFertilizer = false;
    }

    public BlockPos pos() { return pos; }
    public String type() { return type; }
    public UUID assignedCitizenId() { return assignedCitizenId; }
    public void setAssignedCitizenId(UUID id) { this.assignedCitizenId = id; }
    public boolean hasWorker() { return assignedCitizenId != null; }

    public boolean buildingValid() { return buildingValid; }
    public void setBuildingValid(boolean v) { this.buildingValid = v; }

    public boolean active() { return active; }
    public void setActive(boolean v) { this.active = v; }

    public boolean useFertilizer() { return useFertilizer; }
    public void setUseFertilizer(boolean v) { this.useFertilizer = v; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        tag.putString("Type", type);
        if (assignedCitizenId != null) {
            tag.putUUID("AssignedCitizen", assignedCitizenId);
        }
        tag.putBoolean("BuildingValid", buildingValid);
        tag.putBoolean("Active", active);
        tag.putBoolean("UseFertilizer", useFertilizer);
        return tag;
    }

    public static Workstation load(CompoundTag tag) {
        BlockPos pos = new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
        String type = tag.contains("Type") ? tag.getString("Type") : "unknown";
        UUID assigned = tag.hasUUID("AssignedCitizen") ? tag.getUUID("AssignedCitizen") : null;
        Workstation ws = new Workstation(pos, type, assigned);
        if (tag.contains("BuildingValid")) {
            ws.setBuildingValid(tag.getBoolean("BuildingValid"));
        }
        // Absent Active key = active (opt-out toggle); absent UseFertilizer = off (opt-in) - keep these save defaults.
        ws.setActive(!tag.contains("Active") || tag.getBoolean("Active"));
        ws.setUseFertilizer(tag.getBoolean("UseFertilizer"));
        return ws;
    }
}
