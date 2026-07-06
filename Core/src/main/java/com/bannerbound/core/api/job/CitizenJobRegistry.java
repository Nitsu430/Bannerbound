package com.bannerbound.core.api.job;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Public extension point for adding a citizen <b>job</b> from any mod - Core or an expansion -
 * without editing Core's hardcoded job sites. A single {@link #register(JobDef)} call wires the
 * whole job: its work-goal AI, anarchy participation/order, research-unlock unit, and Job-tab icon.
 * The five places Core used to hardcode each job ({@code CitizenEntity.registerGoals},
 * {@link com.bannerbound.core.entity.AnarchyJobs},
 * {@link com.bannerbound.core.api.settlement.WorkstationUnlocks},
 * {@link com.bannerbound.core.social.JobIcons}, and {@code ServerPayloadHandler}'s job list) now
 * consult this registry as {@code built-ins + registry}, so a new job needs no Core change.
 *
 * <p>Crucially {@link JobDef#goalFactory()} is a {@code (citizen, speed) -> Goal} lambda, so Core
 * never references the consumer's goal class - an expansion's goal can live entirely in the
 * expansion while still being attached to Core's {@link CitizenEntity}. The Antiquity spear fisher
 * is the first consumer (see {@code SpearFisherWorkGoal}). Register during common setup (e.g.
 * {@code FMLCommonSetupEvent.enqueueWork}); registration is synchronized, thread-safe, and
 * idempotent (a duplicate {@code jobTypeId} is ignored, so a double commonSetup is safe), and
 * {@link #all()} returns an immutable snapshot.
 *
 * <p>The nested {@link JobDef} record carries everything Core needs to wire one job; build it via
 * {@link JobDef#builder(String)}. Non-obvious fields: {@code gatherer} + {@code anarchyOrder}
 * control anarchy self-employment and gatherer ordering (lower = earlier; ignored when not a
 * gatherer); {@code unitName} maps to research flag {@code bannerbound.unlock.<unitName>} (null =
 * ungated); {@code iconBaseline} is only a {@code minecraft:} fallback, the real item comes from
 * tool_ages; {@code toolRequired} false = forager-style tool-free readiness; and
 * {@code obsoletedByUnit} retires this job when the settlement researches
 * {@code bannerbound.unlock.<obsoletedByUnit>} - it is hidden from the Job tab and its holders
 * migrate to that unit's job (e.g. spear fisher -&gt; fisher when the rod unlocks; null = never
 * obsoleted).
 */
public final class CitizenJobRegistry {
    private static final List<JobDef> DEFS = new ArrayList<>();

    private CitizenJobRegistry() {
    }

    public static synchronized void register(JobDef def) {
        if (def == null || def.jobTypeId() == null) return;
        if (byId(def.jobTypeId()) != null) return;   // idempotent -- survive a double commonSetup
        DEFS.add(def);
    }

    public static synchronized List<JobDef> all() {
        return List.copyOf(DEFS);
    }

    @Nullable
    public static synchronized JobDef byId(@Nullable String jobTypeId) {
        if (jobTypeId == null) return null;
        for (JobDef d : DEFS) {
            if (d.jobTypeId().equals(jobTypeId)) return d;
        }
        return null;
    }

    @Nullable
    public static String unitFor(String jobTypeId) {
        JobDef d = byId(jobTypeId);
        return d == null ? null : d.unitName();
    }

    @Nullable
    public static String iconRoleFor(String jobTypeId) {
        JobDef d = byId(jobTypeId);
        return d == null ? null : d.iconRole();
    }

    public static boolean isWorkshopBound(String jobTypeId) {
        JobDef d = byId(jobTypeId);
        return d != null && d.workshopBound();
    }

    @Nullable
    public static String workshopTypeFor(String jobTypeId) {
        JobDef d = byId(jobTypeId);
        return d != null && d.workshopBound() ? d.workshopTypeId() : null;
    }

    @Nullable
    public static String workshopJobForType(String workshopTypeId) {
        if (workshopTypeId == null) return null;
        for (JobDef d : all()) {
            if (d.workshopBound() && workshopTypeId.equals(d.workshopTypeId())) {
                return d.jobTypeId();
            }
        }
        return null;
    }

    @Nullable
    public static Item baselineForRole(String role) {
        if (role == null) return null;
        for (JobDef d : all()) {
            if (role.equals(d.iconRole())) return d.iconBaseline();
        }
        return null;
    }

    public record JobDef(
        String jobTypeId,
        boolean gatherer,
        int anarchyOrder,
        @Nullable String unitName,
        @Nullable String iconRole,
        Item iconBaseline,
        boolean toolRequired,
        boolean workshopBound,
        @Nullable String workshopTypeId,
        boolean jobPickerVisible,
        @Nullable String obsoletedByUnit,
        BiFunction<CitizenEntity, Double, Goal> goalFactory) {

        public static Builder builder(String jobTypeId) {
            return new Builder(jobTypeId);
        }

        public static final class Builder {
            private final String jobTypeId;
            private boolean gatherer = true;
            private int anarchyOrder = 100;
            private String unitName;
            private String iconRole;
            private Item iconBaseline = Items.AIR;
            private boolean toolRequired = true;
            private boolean workshopBound;
            private String workshopTypeId;
            private boolean jobPickerVisible = true;
            private String obsoletedByUnit;
            private BiFunction<CitizenEntity, Double, Goal> goalFactory;

            private Builder(String jobTypeId) {
                this.jobTypeId = jobTypeId;
            }

            public Builder gatherer(boolean v) { this.gatherer = v; return this; }
            public Builder anarchyOrder(int v) { this.anarchyOrder = v; return this; }
            public Builder unit(String v) { this.unitName = v; return this; }
            public Builder icon(String role, Item baseline) { this.iconRole = role; this.iconBaseline = baseline; return this; }
            public Builder toolRequired(boolean v) { this.toolRequired = v; return this; }
            public Builder workshopBound(@Nullable String typeId) {
                this.workshopBound = true;
                this.workshopTypeId = typeId;
                return this;
            }
            public Builder jobPickerVisible(boolean v) { this.jobPickerVisible = v; return this; }
            public Builder obsoletedBy(String unit) { this.obsoletedByUnit = unit; return this; }
            public Builder goal(BiFunction<CitizenEntity, Double, Goal> f) { this.goalFactory = f; return this; }

            public JobDef build() {
                return new JobDef(jobTypeId, gatherer, anarchyOrder, unitName, iconRole,
                    iconBaseline, toolRequired, workshopBound, workshopTypeId, jobPickerVisible,
                    obsoletedByUnit, goalFactory);
            }
        }
    }
}
