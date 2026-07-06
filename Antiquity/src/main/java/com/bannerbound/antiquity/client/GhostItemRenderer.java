package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Ghost-item rendering for the workstation recipe previews (crafting stone / fletching station).
 * The vanilla item renderer has no alpha knob, so {@link #wrap} reroutes every requested render
 * type onto the translucent entity sheet and scales each vertex's alpha down, the standard
 * JEI/Create "ghost ingredient" trick. Works for flat sprites and block items alike (both live on
 * the block atlas). Glint passes get flattened onto the atlas too, so ghosts never glint; that is
 * fine because recipe ingredients here are never enchanted. DEFAULT_ALPHA (100/255) is tuned to
 * read as "not placed yet" while staying identifiable; the two-arg overload lets a preview pick
 * its own alpha.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class GhostItemRenderer {
    private static final int DEFAULT_ALPHA = 100;

    private GhostItemRenderer() {}

    public static MultiBufferSource wrap(MultiBufferSource buffers) {
        return wrap(buffers, DEFAULT_ALPHA);
    }

    public static MultiBufferSource wrap(MultiBufferSource buffers, int alpha) {
        int clamped = Math.max(0, Math.min(255, alpha));
        return type -> new GhostVertexConsumer(buffers.getBuffer(Sheets.translucentItemSheet()), clamped);
    }

    // Every method must return this (not inner) or chained pipelines lose the alpha override.
    private record GhostVertexConsumer(VertexConsumer inner, int alpha) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            inner.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            inner.setColor(r, g, b, a * alpha / 255);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            inner.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            inner.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            inner.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            inner.setNormal(x, y, z);
            return this;
        }
    }
}
