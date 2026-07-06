package com.bannerbound.core.api.walls;

/**
 * Auto-derived design VARIANTS (playtest 2026-06-12: "a player could replace a wall segment with
 * a step wall segment so they could create stairs there"). A variant is generated FROM the base
 * design - same footprint, same palette, no extra authoring - and is addressed by an id suffix the
 * variant-aware resolver ({@code WallService.resolver}) understands: {@code <baseId>#steps} ramps
 * tops down stepwise along +length (stair silhouette) and {@code <baseId>#steps_r} is the mirror.
 *
 * <p>Same-footprint is what keeps run tiling intact: swapping a piece's designId never changes the
 * layout geometry, only the blocks it expands to. Derivation is deterministic - client and server
 * derive the identical variant from the synced base design, and kept cells are the base design's
 * own blocks so materials and details carry over.
 */
public final class WallVariants {

    public static final String STEPS = "#steps";
    public static final String STEPS_R = "#steps_r";

    public static final int ORDINAL_BASE = 0;
    public static final int ORDINAL_STEPS = 1;
    public static final int ORDINAL_STEPS_R = 2;

    private WallVariants() {
    }

    public static String baseId(String designId) {
        if (designId.endsWith(STEPS_R)) return designId.substring(0, designId.length() - STEPS_R.length());
        if (designId.endsWith(STEPS)) return designId.substring(0, designId.length() - STEPS.length());
        return designId;
    }

    public static int ordinalOf(String designId) {
        if (designId.endsWith(STEPS_R)) return ORDINAL_STEPS_R;
        if (designId.endsWith(STEPS)) return ORDINAL_STEPS;
        return ORDINAL_BASE;
    }

    public static String idFor(String baseId, int ordinal) {
        return switch (ordinal) {
            case ORDINAL_STEPS -> baseId + STEPS;
            case ORDINAL_STEPS_R -> baseId + STEPS_R;
            default -> baseId;
        };
    }

    public static String label(int ordinal) {
        return switch (ordinal) {
            case ORDINAL_STEPS -> "Steps ▲";
            case ORDINAL_STEPS_R -> "Steps ▼";
            default -> "Default";
        };
    }

    public static WallDesign stepped(WallDesign base, boolean reversed) {
        int length = base.length();
        int depth = base.depth();
        int height = base.height();
        WallDesign.Builder b = WallDesign.builder(
            base.id() + (reversed ? STEPS_R : STEPS),
            base.name() + " (steps)", base.kind(), length, depth, height);
        for (int l = 0; l < length; l++) {
            int rank = reversed ? length - l : l + 1;
            int target = Math.max(1, (int) Math.round((double) height * rank / length));
            for (int d = 0; d < depth; d++) {
                for (int h = 0; h < height && h < target; h++) {
                    net.minecraft.world.level.block.state.BlockState state = base.stateAt(l, d, h);
                    if (state != null) {
                        b.set(l, d, h, state);
                    }
                }
            }
        }
        return b.foundation(base.foundation()).build();
    }

    @org.jetbrains.annotations.Nullable
    public static WallDesign resolve(String designId,
                                     java.util.function.Function<String, WallDesign> baseResolver) {
        int ordinal = ordinalOf(designId);
        WallDesign base = baseResolver.apply(baseId(designId));
        if (base == null || ordinal == ORDINAL_BASE) return base;
        return stepped(base, ordinal == ORDINAL_STEPS_R);
    }
}
