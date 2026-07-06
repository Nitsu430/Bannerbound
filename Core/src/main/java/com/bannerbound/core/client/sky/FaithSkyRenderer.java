package com.bannerbound.core.client.sky;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.celestial.Planet;
import com.bannerbound.core.celestial.SkyField;

import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Draws the faith sky (FAITH_PLAN.md Part 3) over the vanilla one: typed star clusters, the
 * wandering planets, believer constellations, the Pantheon-mode star-picking overlay, and ambient
 * cosmetic meteors. Registered on the CLIENT dist event bus; onRenderLevelStage does all the work.
 *
 * Drawn POST-terrain at RenderLevelStageEvent.AFTER_WEATHER, NOT inside renderSky -- the central
 * design decision. The celestial layer is additive geometry that must be OCCLUDED by terrain, and
 * only AFTER_WEATHER has the real depth buffer populated (terrain + entities drawn). A plain LEQUAL
 * depth test then makes terrain occlude the stars in BOTH the vanilla and Iris paths. The old
 * inside-renderSky approach ran before terrain existed, so under an Iris shaderpack the deferred
 * pipeline composited stars with no depth to test against and they bled through hills; no
 * render-state tweak can reach that. LevelRendererMixin suppresses vanilla's own star pass, so we
 * own all stars (typed + the 780 commons) and they wheel together.
 *
 * The whole field shares one celestial sphere that wheels once per calendar YEAR (-observerLonDeg),
 * not vanilla's daily spin: within a night the stars are fixed landmarks (celestial navigation,
 * pole stars) and across the year the constellations turn seasonally (deliberate artistic license).
 * The sun/moon keep vanilla's daily arc. Star alpha uses vanilla's getStarBrightness x (1 - rain)
 * so the sky rises, sets and rains out with the vanilla stars; planets use a steeper ramp so they
 * appear in twilight before the stars (the Venus-at-dusk effect).
 *
 * Matrices: the event hands us the same camera modelview renderSky used (rotation, no translation:
 * stars at infinity) plus the world projection. We force-SET that onto the global modelview stack
 * and draw vertices carrying ONLY the celestial frame; we must NOT also bake the camera into the
 * vertices, because under Iris that double-applies it and the whole sky swims with the camera. The
 * unit-100 dome is uniformly scaled out to just inside the projection far plane so stars project to
 * depth ~1.0 and any terrain occludes them, while angular sizes are preserved. Because we control
 * the matrices and draw through the depth-testing world program, the old Iris STARS-phase override
 * is neither needed nor wanted here (it would re-route into Iris's non-depth-testing sky pass); see
 * IrisSkyCompat for the retired rationale. SkyRenderTypes.SKY_ADDITIVE = additive blend, LEQUAL
 * depth test on, depth WRITE off so overlapping glows blend instead of z-fighting.
 *
 * celestialSpeed / meteorAmount gamerules scale the model clock and meteor rate for testing.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class FaithSkyRenderer {
    private static final float SKY_RADIUS = 100.0f;

    private FaithSkyRenderer() {
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientSkyState.clear();
        com.bannerbound.core.client.ClientFaithState.clear();
        com.bannerbound.core.client.ClientFaithTreeState.clear();
        ClientConstellationState.clear();
        PantheonMode.exit();
        METEORS.clear();
        nextMeteorAt = -1.0f;
    }

    private record Meteor(float sx, float sy, float sz, float velx, float vely, float velz,
                          float spawnSec, float lifeSec, float width) {
    }

    private static final java.util.Random METEOR_RND = new java.util.Random();
    private static final java.util.List<Meteor> METEORS = new java.util.ArrayList<>();
    private static float nextMeteorAt = -1.0f;
    private static float[] radiant;
    private static long radiantDay = Long.MIN_VALUE;

    private static void tickAndRenderMeteors(VertexConsumer vc, Matrix4f mat,
                                             float animSec, float starBrightness, long dayIndex) {
        if (radiant == null || dayIndex != radiantDay) {
            radiantDay = dayIndex;
            float ry = 0.35f + METEOR_RND.nextFloat() * 0.5f;
            float rr = (float) Math.sqrt(1.0f - ry * ry);
            float raz = METEOR_RND.nextFloat() * (float) (Math.PI * 2.0);
            radiant = new float[]{(float) Math.cos(raz) * rr, ry, (float) Math.sin(raz) * rr};
        }
        int amount = ClientSkyState.meteorAmount();
        float baseInterval = amount > 0 ? 60.0f / amount : Float.MAX_VALUE;
        if (nextMeteorAt < 0 || nextMeteorAt - animSec > baseInterval * 4.0f + 120.0f) {
            nextMeteorAt = animSec + baseInterval * (0.6f + METEOR_RND.nextFloat() * 0.8f);
        }
        if (amount > 0 && starBrightness > 0.1f && animSec >= nextMeteorAt
                && METEORS.size() < Math.min(32, Math.max(2, amount))) {
            METEORS.add(spawnMeteor(animSec));
            nextMeteorAt = animSec + baseInterval * (0.6f + METEOR_RND.nextFloat() * 0.8f);
        }
        METEORS.removeIf(m -> animSec - m.spawnSec() > m.lifeSec() || animSec < m.spawnSec());

        for (Meteor m : METEORS) {
            float t = animSec - m.spawnSec();
            if (t < 0.02f) continue;
            float[] head = pointAt(m, t);
            float[] tail = pointAt(m, Math.max(0.0f, t - 0.25f));
            float wx = head[1] * tail[2] - head[2] * tail[1];
            float wy = head[2] * tail[0] - head[0] * tail[2];
            float wz = head[0] * tail[1] - head[1] * tail[0];
            float wl = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (wl < 1.0e-6f) continue;
            float w = m.width() / wl;
            wx *= w; wy *= w; wz *= w;
            float alpha = (float) Math.sin(Math.PI * t / m.lifeSec())
                    * 0.6f * Math.min(1.0f, starBrightness * 1.4f);
            int a = (int) (Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
            float hx = head[0] * SKY_RADIUS, hy = head[1] * SKY_RADIUS, hz = head[2] * SKY_RADIUS;
            float tx = tail[0] * SKY_RADIUS, ty = tail[1] * SKY_RADIUS, tz = tail[2] * SKY_RADIUS;
            vc.addVertex(mat, hx + wx, hy + wy, hz + wz).setColor(255, 244, 220, a);
            vc.addVertex(mat, hx - wx, hy - wy, hz - wz).setColor(255, 244, 220, a);
            vc.addVertex(mat, tx - wx, ty - wy, tz - wz).setColor(255, 244, 220, 0);
            vc.addVertex(mat, tx + wx, ty + wy, tz + wz).setColor(255, 244, 220, 0);
        }
    }

    private static float[] pointAt(Meteor m, float t) {
        float x = m.sx() + m.velx() * t;
        float y = m.sy() + m.vely() * t;
        float z = m.sz() + m.velz() * t;
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / len, y / len, z / len};
    }

    private static Meteor spawnMeteor(float animSec) {
        float omega = (float) Math.toRadians(18.0 + METEOR_RND.nextFloat() * 17.0);
        float life = 0.8f + METEOR_RND.nextFloat() * 0.6f;
        float width = 0.10f + METEOR_RND.nextFloat() * 0.08f;

        if (radiant != null && METEOR_RND.nextFloat() < 0.95f) {
            for (int attempt = 0; attempt < 6; attempt++) {
                float[] start = offsetFromRadiant(
                        (float) Math.toRadians(10.0 + METEOR_RND.nextFloat() * 35.0));
                if (start[1] < 0.08f) continue;
                float dot = radiant[0] * start[0] + radiant[1] * start[1] + radiant[2] * start[2];
                float ax = radiant[0] - dot * start[0];
                float ay = radiant[1] - dot * start[1];
                float az = radiant[2] - dot * start[2];
                float al = (float) Math.sqrt(ax * ax + ay * ay + az * az);
                if (al < 1.0e-5f) continue;
                return new Meteor(start[0], start[1], start[2],
                        -ax / al * omega, -ay / al * omega, -az / al * omega,
                        animSec, life, width);
            }
        }
        float y = 0.25f + METEOR_RND.nextFloat() * 0.6f;
        float r = (float) Math.sqrt(1.0f - y * y);
        float az = METEOR_RND.nextFloat() * (float) (Math.PI * 2.0);
        float x = (float) Math.cos(az) * r;
        float z = (float) Math.sin(az) * r;
        float t1x = -z, t1z = x; // cross(dir, Y-up); never degenerate only because y <= 0.85
        float t1l = (float) Math.sqrt(t1x * t1x + t1z * t1z);
        t1x /= t1l; t1z /= t1l;
        float t2x = y * t1z, t2y = z * t1x - x * t1z, t2z = -y * t1x;
        float ang = METEOR_RND.nextFloat() * (float) (Math.PI * 2.0);
        float ca = (float) Math.cos(ang) * omega, sa = (float) Math.sin(ang) * omega;
        return new Meteor(x, y, z,
                t1x * ca + t2x * sa, t2y * sa, t1z * ca + t2z * sa,
                animSec, life, width);
    }

    private static float[] offsetFromRadiant(float angRad) {
        float rx = radiant[0], ry = radiant[1], rz = radiant[2];
        float t1x = -rz, t1z = rx; // cross(radiant, Y-up); never degenerate only because y <= 0.85
        float t1l = (float) Math.sqrt(t1x * t1x + t1z * t1z);
        t1x /= t1l; t1z /= t1l;
        float t2x = ry * t1z, t2y = rz * t1x - rx * t1z, t2z = -ry * t1x;
        float az = METEOR_RND.nextFloat() * (float) (Math.PI * 2.0);
        float ca = (float) Math.cos(az), sa = (float) Math.sin(az);
        float cA = (float) Math.cos(angRad), sA = (float) Math.sin(angRad);
        return new float[]{
                rx * cA + (t1x * ca + t2x * sa) * sA,
                ry * cA + (t2y * sa) * sA,
                rz * cA + (t1z * ca + t2z * sa) * sA};
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) return;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        SkyField sky = ClientSkyState.field();
        if (level == null || sky == null) return;
        if (level.dimension() != Level.OVERWORLD) return;

        Matrix4f frustumMatrix = event.getModelViewMatrix();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        float far = event.getProjectionMatrix().perspectiveFar();
        float targetRadius = (far > SKY_RADIUS * 1.5f && Float.isFinite(far))
                ? far * 0.98f : SKY_RADIUS;
        float domeScale = targetRadius / SKY_RADIUS;

        float clearSky = 1.0f - level.getRainLevel(partial);
        float starBrightness = level.getStarBrightness(partial) * clearSky;
        float planetBrightness = Math.min(1.0f, level.getStarBrightness(partial) * 1.7f) * clearSky;
        if (planetBrightness <= 0.02f) return;

        double days = level.getDayTime() / 24000.0 * ClientSkyState.celestialSpeed();
        float wheelDeg = (float) -sky.observerLonDeg(days);
        float animSec = (level.getGameTime() % 240000L + partial) / 20.0f;

        // Force-SET (not multiply) the camera modelview; vertices carry ONLY the celestial frame,
        // else Iris double-applies the camera and the whole sky swims with it.
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        mvStack.set(frustumMatrix);
        RenderSystem.applyModelViewMatrix();

        PoseStack pose = new PoseStack();
        pose.scale(domeScale, domeScale, domeScale);
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffer.getBuffer(SkyRenderTypes.SKY_ADDITIVE);

        tickAndRenderMeteors(vc, pose.last().pose(), animSec, starBrightness,
                Math.floorDiv(level.getDayTime(), 24000L));

        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(-90.0f));
        pose.mulPose(Axis.XP.rotationDegrees(wheelDeg));

        Matrix4f planetMat = pose.last().pose();
        for (Planet p : sky.planets) {
            SkyField.PlanetView view = sky.view(p, days);
            double phi = Math.toRadians(view.eclipticLonDeg());
            double lat = Math.toRadians(view.eclipticLatDeg());
            float dx = (float) Math.sin(lat);
            float dy = (float) (Math.cos(lat) * Math.cos(phi));
            float dz = (float) (Math.cos(lat) * Math.sin(phi));
            float ul = (float) Math.sqrt(dz * dz + dy * dy);
            float ux = 0, uy = dz / ul, uz = -dy / ul;
            float vx = dy * uz - dz * uy;
            float vy = dz * ux - dx * uz;
            float vz = dx * uy - dy * ux;
            float size = 1.25f * p.baseSize() * (float) clamp(1.0 / view.distance(), 0.45, 1.8);
            float alpha = planetBrightness * (float) clamp(1.15 / view.distance(), 0.55, 1.0);
            quad(vc, planetMat, dx, dy, dz, ux, uy, uz, vx, vy, vz, size, p.rgb(), alpha);
            glow(vc, planetMat, dx, dy, dz, ux, uy, uz, vx, vy, vz,
                    size * 3.0f, p.rgb(), alpha * 0.55f, animSec * 0.12f);
        }

        if (PantheonMode.isActive() && starBrightness <= 0.05f) {
            PantheonMode.exit();
        }
        if (PantheonMode.isActive()) {
            for (int id : PantheonMode.chain()) {
                if (!sky.isValidStarId(id)) {
                    PantheonMode.exit();
                    break;
                }
            }
        }
        if (com.bannerbound.core.client.ClientFaithState.hasFaith()) {
            for (com.bannerbound.core.network.ConstellationsSyncPayload.Entry entry
                    : ClientConstellationState.entries()) {
                int[] ids = entry.starIds();
                boolean allValid = true;
                for (int id : ids) {
                    if (!sky.isValidStarId(id)) {
                        allValid = false;
                        break;
                    }
                }
                if (!allValid) continue;
                float lineAlpha = starBrightness * 0.45f;
                for (int i = 0; i + 1 < ids.length; i++) {
                    lineQuad(vc, planetMat, sky.starCelestialDir(ids[i], days),
                        sky.starCelestialDir(ids[i + 1], days), 0.10f, 0xE8E2D0, lineAlpha);
                }
                for (int id : ids) {
                    double[] d = sky.starCelestialDir(id, days);
                    float[] uv = basisFor(d);
                    glow(vc, planetMat, (float) d[0], (float) d[1], (float) d[2],
                        uv[0], uv[1], uv[2], uv[3], uv[4], uv[5],
                        0.7f, 0xE8E2D0, starBrightness * 0.30f, 0.0f);
                }
            }
        }
        if (PantheonMode.isActive()) {
            double[] lookCel = null;
            if (mc.screen == null && mc.player != null) {
                net.minecraft.world.phys.Vec3 look = mc.player.getViewVector(partial);
                double ang = Math.toRadians(wheelDeg);
                double x1 = look.z, y1 = look.y, z1 = -look.x;
                double c = Math.cos(-ang), s = Math.sin(-ang);
                lookCel = new double[]{x1, y1 * c - z1 * s, y1 * s + z1 * c};
                PantheonMode.setHovered(sky.pickStar(lookCel, days,
                    PantheonMode.PICK_CONE_DEG, ClientConstellationState::starUsed));
            }
            java.util.List<Integer> chain = PantheonMode.chain();
            for (int i = 0; i + 1 < chain.size(); i++) {
                lineQuad(vc, planetMat, sky.starCelestialDir(chain.get(i), days),
                    sky.starCelestialDir(chain.get(i + 1), days),
                    0.14f, 0xFFE08A, starBrightness * 0.9f);
            }
            for (int id : chain) {
                double[] d = sky.starCelestialDir(id, days);
                float[] uv = basisFor(d);
                glow(vc, planetMat, (float) d[0], (float) d[1], (float) d[2],
                    uv[0], uv[1], uv[2], uv[3], uv[4], uv[5],
                    0.6f, 0xFFF2C8, starBrightness * 0.85f, 0.0f);
                glow(vc, planetMat, (float) d[0], (float) d[1], (float) d[2],
                    uv[0], uv[1], uv[2], uv[3], uv[4], uv[5],
                    1.3f, 0xFFE08A, starBrightness * 0.4f, animSec * 0.5f);
            }
            int hovered = PantheonMode.hoveredStarId();
            if (hovered >= 0 && !sky.isValidStarId(hovered)) hovered = -1;
            if (!chain.isEmpty() && (hovered >= 0 || lookCel != null)) {
                double[] from = sky.starCelestialDir(chain.get(chain.size() - 1), days);
                double[] to = hovered >= 0 ? sky.starCelestialDir(hovered, days) : lookCel;
                lineQuad(vc, planetMat, from, to, 0.08f, 0xFFE08A, starBrightness * 0.45f);
            }
            if (hovered >= 0) {
                double[] d = sky.starCelestialDir(hovered, days);
                float[] uv = basisFor(d);
                float pulse = 0.55f + 0.25f * (float) Math.sin(animSec * 5.0);
                glow(vc, planetMat, (float) d[0], (float) d[1], (float) d[2],
                    uv[0], uv[1], uv[2], uv[3], uv[4], uv[5],
                    1.4f, 0xFFD27A, starBrightness * pulse, animSec * 0.8f);
            }
        }

        if (starBrightness > 0.02f) {
            int i = 0;
            for (SkyField.Star s : sky.stars) {
                float twinkle = 0.78f + 0.22f * (float) Math.sin(
                        animSec * (1.5f + (i % 7) * 0.35f) + i * 2.1f);
                quad(vc, planetMat, s.dx, s.dy, s.dz, s.ux, s.uy, s.uz, s.vx, s.vy, s.vz,
                        s.size, s.rgb, starBrightness * s.alphaMul * twinkle);
                i++;
            }
            int commonCount = com.bannerbound.core.celestial.VanillaStars.count();
            for (int ci = 0; ci < commonCount; ci++) {
                com.bannerbound.core.celestial.VanillaStars.CommonStar s =
                    com.bannerbound.core.celestial.VanillaStars.get(ci);
                float twinkle = 0.80f + 0.20f * (float) Math.sin(
                        animSec * (1.4f + (ci % 5) * 0.3f) + ci * 1.3f);
                double[] d = {s.dx, s.dy, s.dz};
                float[] uv = basisFor(d);
                quad(vc, planetMat, s.dx, s.dy, s.dz, uv[0], uv[1], uv[2], uv[3], uv[4], uv[5],
                        s.size, 0xEAEAEA, starBrightness * twinkle);
            }
        }

        pose.popPose();
        try {
            buffer.endBatch(SkyRenderTypes.SKY_ADDITIVE);
        } finally {
            mvStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void glow(VertexConsumer vc, Matrix4f mat,
                             float dx, float dy, float dz,
                             float ux, float uy, float uz,
                             float vx, float vy, float vz,
                             float s, int rgb, float alpha, float rotRad) {
        float cx = dx * SKY_RADIUS, cy = dy * SKY_RADIUS, cz = dz * SKY_RADIUS;
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int a = (int) (Math.min(1.0f, alpha) * 255.0f);
        float[] rim = new float[12];
        for (int i = 0; i < 4; i++) {
            double ang = rotRad + i * (Math.PI / 2.0);
            float cu = (float) Math.cos(ang) * s, cv = (float) Math.sin(ang) * s;
            rim[i * 3] = cx + cu * ux + cv * vx;
            rim[i * 3 + 1] = cy + cu * uy + cv * vy;
            rim[i * 3 + 2] = cz + cu * uz + cv * vz;
        }
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            vc.addVertex(mat, cx, cy, cz).setColor(r, g, b, a);
            vc.addVertex(mat, rim[i * 3], rim[i * 3 + 1], rim[i * 3 + 2]).setColor(r, g, b, 0);
            vc.addVertex(mat, rim[j * 3], rim[j * 3 + 1], rim[j * 3 + 2]).setColor(r, g, b, 0);
            vc.addVertex(mat, cx, cy, cz).setColor(r, g, b, a);
        }
    }

    private static float[] basisFor(double[] d) {
        double rx = Math.abs(d[1]) < 0.99 ? 0 : 1;
        double ry = Math.abs(d[1]) < 0.99 ? 1 : 0;
        double ux = ry * d[2], uy = -rx * d[2], uz = rx * d[1] - ry * d[0];
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;
        double vx = d[1] * uz - d[2] * uy;
        double vy = d[2] * ux - d[0] * uz;
        double vz = d[0] * uy - d[1] * ux;
        return new float[]{(float) ux, (float) uy, (float) uz, (float) vx, (float) vy, (float) vz};
    }

    private static void lineQuad(VertexConsumer vc, Matrix4f mat, double[] a, double[] b,
                                 float width, int rgb, float alpha) {
        double cx = a[1] * b[2] - a[2] * b[1];
        double cy = a[2] * b[0] - a[0] * b[2];
        double cz = a[0] * b[1] - a[1] * b[0];
        double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (len < 1.0e-6) return;
        float wx = (float) (cx / len * width);
        float wy = (float) (cy / len * width);
        float wz = (float) (cz / len * width);
        float ax = (float) a[0] * SKY_RADIUS, ay = (float) a[1] * SKY_RADIUS, az = (float) a[2] * SKY_RADIUS;
        float bx = (float) b[0] * SKY_RADIUS, by = (float) b[1] * SKY_RADIUS, bz = (float) b[2] * SKY_RADIUS;
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, bl = rgb & 0xFF;
        int al = (int) (Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        vc.addVertex(mat, ax + wx, ay + wy, az + wz).setColor(r, g, bl, al);
        vc.addVertex(mat, ax - wx, ay - wy, az - wz).setColor(r, g, bl, al);
        vc.addVertex(mat, bx - wx, by - wy, bz - wz).setColor(r, g, bl, al);
        vc.addVertex(mat, bx + wx, by + wy, bz + wz).setColor(r, g, bl, al);
    }

    private static void quad(VertexConsumer vc, Matrix4f mat,
                             float dx, float dy, float dz,
                             float ux, float uy, float uz,
                             float vx, float vy, float vz,
                             float s, int rgb, float alpha) {
        float cx = dx * SKY_RADIUS, cy = dy * SKY_RADIUS, cz = dz * SKY_RADIUS;
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int a = (int) (Math.min(1.0f, alpha) * 255.0f);
        vc.addVertex(mat, cx - s * ux - s * vx, cy - s * uy - s * vy, cz - s * uz - s * vz).setColor(r, g, b, a);
        vc.addVertex(mat, cx + s * ux - s * vx, cy + s * uy - s * vy, cz + s * uz - s * vz).setColor(r, g, b, a);
        vc.addVertex(mat, cx + s * ux + s * vx, cy + s * uy + s * vy, cz + s * uz + s * vz).setColor(r, g, b, a);
        vc.addVertex(mat, cx - s * ux + s * vx, cy - s * uy + s * vy, cz - s * uz + s * vz).setColor(r, g, b, a);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static final class SkyRenderTypes extends RenderType {
        private SkyRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                               boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear) {
            super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
        }

        static final RenderType SKY_ADDITIVE = create(
                "bannerbound_sky_additive",
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                4096, false, false,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_SHADER)
                        .setTransparencyState(LIGHTNING_TRANSPARENCY)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_WRITE)
                        .createCompositeState(false));
    }
}
