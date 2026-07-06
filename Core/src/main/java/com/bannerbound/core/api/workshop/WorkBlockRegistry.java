package com.bannerbound.core.api.workshop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.bannerbound.core.api.settlement.Workshop;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Public registry of <b>work blocks</b> - the blocks that make an enclosed building a Workshop
 * (see {@code CRAFTER_PLAN.md}). Expansions register their stations during common setup
 * ({@code FMLCommonSetupEvent.enqueueWork}, like {@code CitizenJobRegistry}):
 *
 * <pre>
 * WorkBlockRegistry.register(new WorkBlockDef(
 *     BannerboundAntiquity.FLETCHING_STATION.get(), "fletchery"));
 * </pre>
 *
 * <p>A workshop's TYPE is derived from the work blocks it contains: one distinct
 * {@code workshopTypeId} -> that type; several -> the generic {@link #TYPE_MIXED} "workshop" type;
 * none -> {@link #TYPE_NONE} (invalid). Its CAPACITY (max assigned crafters) is the number of work
 * blocks, but a multiblock station supplies a {@code WorkBlockDef.anchorTest} so only its
 * anchor/master cell counts - one work-slot per multiblock, not one per sub-block (shell cells are
 * still recognized as station, so they are not mistaken for storage, but add no capacity). Display
 * names come from lang keys {@code bannerbound.workshop.type.<typeId>} (+ {@code .mixed} / {@code
 * .none}).
 *
 * <p>A {@code WorkBlockDef} carries the block, its {@code workshopTypeId}, the {@link WorkExecutor}
 * that drives an NPC craft (null = players-only station), the type's display icon (null = iconless,
 * notably mixed/none), and the optional {@code anchorTest}. Every registration here is idempotent
 * per key (first registration wins) across all maps.
 *
 * <p>Crafter professions live in {@code UNIT_BY_TYPE}: a single generic Crafter job staffs EVERY
 * workshop, and its specialty (executor, icon, research unlock) is derived from the workshop's
 * type, so the per-type unlock unit lives here rather than on a separate job id
 * ({@link #crafterUnits} drives the "is the Crafter job available at all" check - any one
 * researched is enough). When a Crafter cannot resolve a station family its icon falls back to the
 * declared {@link #defaultCrafterType}'s icon (an expansion sets this once, Antiquity -> the
 * crafting stone), else the Core {@link #setDefaultCrafterIconBaseline baseline} (a vanilla
 * crafting table). Modules may layer extra per-type structure rules via {@link WorkshopRequirement}
 * (returns null when satisfied, else the {@code Workshop.Status} that invalidates the workshop).
 *
 * <p>Phase 2 attaches a {@code WorkExecutor} per type; Phase 1 only needs the block -> type
 * mapping. Icon and type lookups must resolve on BOTH client and server - registrations run in
 * common setup.
 */
public final class WorkBlockRegistry {
    public static final String TYPE_MIXED = "mixed";
    public static final String TYPE_NONE = "none";

    private static volatile String defaultCrafterType = null;
    private static volatile net.minecraft.world.item.Item defaultCrafterIconBaseline = null;

    public record WorkBlockDef(Block block, String workshopTypeId, @Nullable WorkExecutor executor,
                               @Nullable net.minecraft.world.item.Item icon,
                               @Nullable java.util.function.Predicate<BlockState> anchorTest) {
        public WorkBlockDef(Block block, String workshopTypeId) {
            this(block, workshopTypeId, null, null, null);
        }

        public WorkBlockDef(Block block, String workshopTypeId, @Nullable WorkExecutor executor) {
            this(block, workshopTypeId, executor, null, null);
        }

        public WorkBlockDef(Block block, String workshopTypeId, @Nullable WorkExecutor executor,
                            @Nullable net.minecraft.world.item.Item icon) {
            this(block, workshopTypeId, executor, icon, null);
        }

        public boolean countsAt(BlockState state) {
            return anchorTest == null || anchorTest.test(state);
        }
    }

    private static final Map<Block, WorkBlockDef> BY_BLOCK = new LinkedHashMap<>();
    private static final Map<String, net.minecraft.world.item.Item> ICON_BY_TYPE = new LinkedHashMap<>();
    private static final Map<String, WorkshopRequirement> REQUIREMENT_BY_TYPE = new LinkedHashMap<>();
    private static final Map<String, String> UNIT_BY_TYPE = new LinkedHashMap<>();

    @FunctionalInterface
    public interface WorkshopRequirement {
        @Nullable
        Workshop.Status validate(ServerLevel sl, Workshop workshop, Set<BlockPos> marked,
                                 List<BlockPos> reachableWork,
                                 List<BlockPos> reachableStorage);
    }

    private WorkBlockRegistry() {
    }

    public static synchronized void register(WorkBlockDef def) {
        BY_BLOCK.putIfAbsent(def.block(), def);
        if (def.icon() != null) {
            ICON_BY_TYPE.putIfAbsent(def.workshopTypeId(), def.icon());
        }
    }

    public static synchronized void registerTypeUnit(String typeId, String unitName) {
        if (typeId != null && unitName != null) UNIT_BY_TYPE.putIfAbsent(typeId, unitName);
    }

    @Nullable
    public static String unitForType(String typeId) {
        return UNIT_BY_TYPE.get(typeId);
    }

    public static synchronized Set<String> crafterUnits() {
        return Set.copyOf(UNIT_BY_TYPE.values());
    }

    public static synchronized void registerRequirement(String typeId, WorkshopRequirement requirement) {
        REQUIREMENT_BY_TYPE.put(typeId, requirement);
    }

    public static synchronized void registerRequirementIfAbsent(String typeId,
                                                                WorkshopRequirement requirement) {
        REQUIREMENT_BY_TYPE.putIfAbsent(typeId, requirement);
    }

    @Nullable
    public static Workshop.Status validateRequirements(Set<String> typeIds, ServerLevel sl,
                                                       Workshop workshop, Set<BlockPos> marked,
                                                       List<BlockPos> reachableWork,
                                                       List<BlockPos> reachableStorage) {
        for (String typeId : typeIds) {
            WorkshopRequirement requirement = REQUIREMENT_BY_TYPE.get(typeId);
            if (requirement == null) continue;
            Workshop.Status status = requirement.validate(
                sl, workshop, marked, reachableWork, reachableStorage);
            if (status != null) return status;
        }
        return null;
    }

    public static synchronized void setDefaultCrafterType(String typeId) {
        if (typeId != null && !typeId.isBlank() && defaultCrafterType == null) {
            defaultCrafterType = typeId;
        }
    }

    public static synchronized void setDefaultCrafterIconBaseline(net.minecraft.world.item.Item item) {
        if (item != null && defaultCrafterIconBaseline == null) {
            defaultCrafterIconBaseline = item;
        }
    }

    @Nullable
    public static String defaultCrafterType() {
        return defaultCrafterType;
    }

    @Nullable
    public static net.minecraft.world.item.Item defaultCrafterIcon() {
        if (defaultCrafterType != null) {
            net.minecraft.world.item.Item icon = ICON_BY_TYPE.get(defaultCrafterType);
            if (icon != null) return icon;
        }
        return defaultCrafterIconBaseline;
    }

    @Nullable
    public static net.minecraft.world.item.Item iconForType(String typeId) {
        return ICON_BY_TYPE.get(typeId);
    }

    @Nullable
    public static WorkBlockDef of(BlockState state) {
        return BY_BLOCK.get(state.getBlock());
    }

    public static boolean isWorkBlock(BlockState state) {
        return BY_BLOCK.containsKey(state.getBlock());
    }

    public static String displayKey(String typeId) {
        return "bannerbound.workshop.type." + typeId;
    }
}
