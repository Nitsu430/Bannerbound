package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The poison-vision screen post-process: a GUI-stage framebuffer pass run at
 * {@code RenderGuiEvent.Pre}, i.e. after the world and any shader pack (this is what makes it
 * Iris-safe -- never a world-stage PostChain) and before the HUD, so the HUD stays crisp. Each
 * pass blits the main render target into a temp target, redraws it through the
 * {@code poison_vision} core shader with per-effect uniforms (desat / blur / vignette / tint /
 * warp / chroma / smear / goo), then blits the final output into a history target that feeds next
 * frame's afterimage ("vision lag") trails. All entry points reuse that one pass:
 * render(type, stage, fraction, pulse) for poisons -- belladonna is a subtle sickly-violet
 * deliriant (warp + double-vision chroma; deliberately toned WAY down after it proved
 * overwhelming, not wolfsbane's heavy blur/cold), oleander is a continuous cardiac clock rather
 * than staged (blood-red wash deepening with {@code fraction}, 0 infection -> 1 arrest, pumping
 * with the per-beat {@code pulse} envelope from {@link PoisonHeartbeat}, tunneling/swimming only
 * near blackout), wolfsbane is the staged body-shutdown (blur, desaturate, cold-blue tunnel,
 * smear trails). renderStun is the blunt-crit daze (brief blur + soft vignette, no tint).
 * renderDrunk / renderHangover / renderGoo are GROG_PLAN.md Phase 3.5: drunkenness driven by a
 * client-eased 0..1 intensity (gentle sway escalating to violent seasick warp/chroma; our own
 * effect, no vanilla Nausea), the morning-after throbbing vignette (no warp -- headache, not
 * swim), and the lumpy vomit-goo overlay whose seed varies the blob layout per splat.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class PoisonPostProcessor {
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static ShaderInstance shader;
    private static RenderTarget temp;
    private static RenderTarget history;
    private static float lastTime = -1.0E9F;
    private static float gooSeed;

    private PoisonPostProcessor() {}

    public static void setShader(ShaderInstance s) {
        shader = s;
    }

    public static void render(com.bannerbound.antiquity.poison.PoisonType type, int stage, float time,
                              float fraction, float pulse) {
        if (shader == null || stage <= 0 || type == null) {
            return;
        }
        float desat, blur, vignette, tintAmt, warp, chroma, smear;
        float tintR, tintG, tintB;
        if (type == com.bannerbound.antiquity.poison.PoisonType.BELLADONNA) {
            desat = Math.min(0.38F, 0.08F * stage);
            blur = 0.0F;
            vignette = Math.min(0.85F, 0.22F + 0.15F * stage);
            tintAmt = Math.min(0.60F, 0.13F * stage);
            tintR = 0.88F; tintG = 0.74F; tintB = 1.08F;
            warp = Math.min(0.80F, 0.20F * stage);
            chroma = Math.min(0.90F, 0.22F * stage);
            smear = stage <= 1 ? 0.0F : Math.min(0.30F, 0.09F * (stage - 1));
        } else if (type == com.bannerbound.antiquity.poison.PoisonType.OLEANDER) {
            float f = Math.max(0.0F, Math.min(1.0F, fraction));
            desat = 0.35F * f;
            blur = f > 0.78F ? (f - 0.78F) * 4.5F : 0.0F;
            vignette = Math.min(0.96F, 0.12F + 0.55F * f + pulse * (0.18F + 0.45F * f));
            tintAmt = Math.min(0.85F, 0.18F + 0.50F * f + pulse * 0.25F);
            tintR = 1.25F; tintG = 0.50F; tintB = 0.52F;
            warp = 0.0F;
            chroma = 0.0F;
            smear = 0.0F;
        } else {
            desat = Math.min(0.85F, 0.12F + 0.16F * stage);
            blur = stage <= 1 ? 0.0F : 0.8F * (stage - 1);
            vignette = Math.min(0.92F, 0.30F + 0.16F * stage);
            tintAmt = Math.min(1.0F, 0.20F + 0.22F * stage);
            tintR = 0.74F; tintG = 0.88F; tintB = 1.10F;
            warp = 0.0F;
            chroma = 0.0F;
            smear = stage <= 1 ? 0.0F : Math.min(0.62F, 0.18F * (stage - 1));
        }
        pass(desat, blur, vignette, tintAmt, tintR, tintG, tintB, warp, chroma, smear, 0.0F, time);
    }

    public static void renderStun(float intensity, float time) {
        if (shader == null || intensity <= 0.0F) {
            return;
        }
        float i = Math.min(1.0F, intensity);
        pass(0.18F * i, 2.4F * i, 0.40F * i, 0.0F, 1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, time);
    }

    public static void renderDrunk(float intensity, float time) {
        if (shader == null || intensity <= 0.001F) {
            return;
        }
        float i = Math.min(1.0F, intensity);
        float hard = i * i;
        float harder = hard * i;
        float warp = Math.min(1.6F, 0.25F * i + 1.45F * harder);
        float chroma = Math.min(1.1F, 0.15F * i + 1.0F * hard);
        float blur = i > 0.35F ? Math.min(2.2F, (i - 0.35F) * 4.5F) : 0.0F;
        // Smear MUST stay hard-capped below 1.0: at ~1 the history feedback blends ~100% of the previous frame and the world view freezes.
        float smear = i > 0.4F ? Math.min(0.6F, (i - 0.4F) * 1.3F) : 0.0F;
        float vignette = 0.16F * i + 0.55F * hard;
        float desat = 0.12F * i;
        float tintAmt = 0.15F * i;
        pass(desat, blur, vignette, tintAmt, 1.10F, 1.00F, 0.85F, warp, chroma, smear, 0.0F, time);
    }

    public static void renderHangover(float intensity, float time) {
        if (shader == null || intensity <= 0.001F) {
            return;
        }
        float i = Math.min(1.0F, intensity);
        float throb = 0.5F + 0.5F * (float) Math.sin(time * 0.45F);
        float vignette = Math.min(0.95F, (0.42F + 0.34F * throb) * i);
        pass(0.22F * i, 0.5F * i, vignette, 0.18F * i, 1.16F, 0.86F, 0.80F, 0.0F, 0.0F, 0.0F, 0.0F, time);
    }

    public static void renderGoo(float intensity, float seed, float time) {
        if (shader == null || intensity <= 0.001F) {
            return;
        }
        gooSeed = seed;
        pass(0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F, Math.min(1.0F, intensity), time);
    }

    private static void pass(float desat, float blur, float vignette, float tintAmt,
                             float tintR, float tintG, float tintB,
                             float warp, float chroma, float smear, float goo, float time) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        int w = main.width;
        int h = main.height;
        if (w <= 0 || h <= 0) {
            return;
        }
        boolean freshHistory = false;
        if (temp == null || temp.width != w || temp.height != h) {
            if (temp != null) {
                temp.destroyBuffers();
            }
            temp = new TextureTarget(w, h, false, Minecraft.ON_OSX);
        }
        if (history == null || history.width != w || history.height != h) {
            if (history != null) {
                history.destroyBuffers();
            }
            history = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            freshHistory = true;
        }
        // Fresh affliction or resize: clear history or the smear feedback replays a stale ghost of an old scene.
        if (freshHistory || time - lastTime > 2.0F) {
            history.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
            history.clear(Minecraft.ON_OSX);
            main.bindWrite(false);
        }
        lastTime = time;

        // Blit main -> temp first: GL cannot sample the same framebuffer it is writing to.
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, temp.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        main.bindWrite(false);

        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderTexture(0, temp.getColorTextureId());
        RenderSystem.setShaderTexture(1, history.getColorTextureId());
        RenderSystem.setShader(() -> shader);
        setUniform("ScreenSize", u -> u.set((float) w, (float) h));
        setUniform("Desat", u -> u.set(desat));
        setUniform("BlurRadius", u -> u.set(blur));
        setUniform("Vignette", u -> u.set(vignette));
        setUniform("ColdTint", u -> u.set(tintAmt));
        setUniform("TintColor", u -> u.set(tintR, tintG, tintB));
        setUniform("Warp", u -> u.set(warp));
        setUniform("Chroma", u -> u.set(chroma));
        setUniform("Smear", u -> u.set(smear));
        setUniform("Goo", u -> u.set(goo));
        setUniform("GooSeed", u -> u.set(gooSeed));
        setUniform("PoisonTime", u -> u.set(time));

        // Force identity matrices: the GUI ortho projection is active here and would shrink the NDC quad.
        Matrix4f prevProj = RenderSystem.getProjectionMatrix();
        RenderSystem.setProjectionMatrix(IDENTITY, VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        mv.identity();
        RenderSystem.applyModelViewMatrix();

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        bb.addVertex(-1.0F, -1.0F, 0.0F).setUv(0.0F, 0.0F);
        bb.addVertex(1.0F, -1.0F, 0.0F).setUv(1.0F, 0.0F);
        bb.addVertex(1.0F, 1.0F, 0.0F).setUv(1.0F, 1.0F);
        bb.addVertex(-1.0F, 1.0F, 0.0F).setUv(0.0F, 1.0F);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        mv.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(prevProj, VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, history.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        main.bindWrite(false);
    }

    private static void setUniform(String name, java.util.function.Consumer<com.mojang.blaze3d.shaders.Uniform> setter) {
        com.mojang.blaze3d.shaders.Uniform u = shader.getUniform(name);
        if (u != null) {
            setter.accept(u);
        }
    }
}
