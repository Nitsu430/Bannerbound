package com.bannerbound.core.client.sky;

import java.lang.reflect.Method;

/**
 * RETIRED reflective Iris shim -- no longer called by FaithSkyRenderer; kept as documentation of
 * the approach the post-terrain (RenderLevelStageEvent.AFTER_WEATHER) render flow superseded, and
 * because Iris stays a soft dependency should a future iteration need it again.
 *
 * History: when the faith sky drew inside renderSky (before terrain), Iris overrode our
 * POSITION_COLOR shader with its world program (gbuffers_basic), which transforms vertices by the
 * captured camera modelview (gbufferModelView) -- but our vertices already baked the camera matrix,
 * so the rotation applied twice and the whole sky swam with the camera. Vanilla's own stars escape
 * this by drawing in the STARS phase, where Iris binds its per-draw SKY program. This class
 * reflectively force-set the STARS phase around endBatch so Iris treated our geometry like vanilla
 * stars. It is no longer used because the celestial layer moved POST-terrain: there we set the
 * global modelview ourselves and draw against the real depth buffer, so the double-transform cannot
 * occur AND we deliberately want Iris's depth-testing world program (not its sky pass, which would
 * skip the terrain depth test and resurrect the stars-through-terrain bug). All access is reflective
 * (net.irisshaders.iris.api.v0.IrisApi + GbufferPrograms.setOverridePhase) so Iris being absent or
 * its internals shifting simply disables the shim.
 */
@SuppressWarnings("unused")
final class IrisSkyCompat {
    private static boolean initialized;
    private static boolean usable;
    private static Object irisApi;
    private static Method isShaderPackInUse;
    private static Method setOverridePhase;
    private static Object phaseStars;

    private IrisSkyCompat() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void init() {
        initialized = true;
        try {
            Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisApi = apiClass.getMethod("getInstance").invoke(null);
            isShaderPackInUse = apiClass.getMethod("isShaderPackInUse");

            Class<?> gbufferPrograms = Class.forName("net.irisshaders.iris.layer.GbufferPrograms");
            Class<?> phaseEnum = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPhase");
            setOverridePhase = gbufferPrograms.getMethod("setOverridePhase", phaseEnum);
            phaseStars = Enum.valueOf((Class<? extends Enum>) phaseEnum, "STARS");

            usable = true;
        } catch (Throwable absentOrChanged) {
            usable = false;
        }
    }

    static boolean begin() {
        if (!initialized) {
            init();
        }
        if (!usable) {
            return false;
        }
        try {
            if (!Boolean.TRUE.equals(isShaderPackInUse.invoke(irisApi))) {
                return false;
            }
            setOverridePhase.invoke(null, phaseStars);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static void end() {
        if (!usable) {
            return;
        }
        try {
            setOverridePhase.invoke(null, new Object[]{null});
        } catch (Throwable ignored) {
        }
    }
}
