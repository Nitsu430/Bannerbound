package com.bannerbound.antiquity.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Player;

/**
 * Drunk controls (GROG_PLAN.md Phase 3.5), client-side on the local player: injected at the TAIL of
 * {@link KeyboardInput#tick} so it flips/augments the already-read keys. Effects escalate with the
 * INTOXICATION_LEVEL attachment: below STUMBLE_MIN (4) input is untouched (tipsy/drunk still steer
 * true); at 4+ an involuntary stumble is added to the impulses - two out-of-phase sines per axis (no
 * obvious loop period), scaled by drunkenness and clamped to walk range so it is a drift, not a
 * launch, swaying even while standing still; at INVERT_STRAFE (5) left/right invert; at INVERT_FULL
 * (7) forward/back invert too. While PASS_OUT_UNTIL is in the future the player is out cold and all
 * movement/jump input is zeroed before any of that runs.
 */
@Mixin(KeyboardInput.class)
public class DrunkInputMixin {
    private static final int STUMBLE_MIN = 4;
    private static final int INVERT_STRAFE = 5;
    private static final int INVERT_FULL = 7;

    @Inject(method = "tick", at = @At("TAIL"))
    private void bannerbound$drunkenInput(boolean movingSlowly, float strafeMultiplier, CallbackInfo ci) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Input input = (Input) (Object) this;

        long passOut = player.getData(BannerboundAntiquity.PASS_OUT_UNTIL.get());
        if (passOut > 0L && player.level().getGameTime() < passOut) {
            input.up = input.down = input.left = input.right = input.jumping = false;
            input.forwardImpulse = 0.0F;
            input.leftImpulse = 0.0F;
            return;
        }

        int level = player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get());
        if (level < STUMBLE_MIN) {
            return;
        }

        if (level >= INVERT_STRAFE) {
            boolean l = input.left;
            input.left = input.right;
            input.right = l;
            input.leftImpulse = -input.leftImpulse;
            if (level >= INVERT_FULL) {
                boolean u = input.up;
                input.up = input.down;
                input.down = u;
                input.forwardImpulse = -input.forwardImpulse;
            }
        }

        float strength = Math.min(0.65F, 0.13F * (level - 3));
        double t = System.nanoTime() / 1.0E9;
        float sway = (float) (Math.sin(t * 1.3) + 0.5 * Math.sin(t * 2.7 + 1.0));
        float lurch = (float) (Math.sin(t * 0.85 + 2.0) + 0.5 * Math.sin(t * 1.9));
        input.leftImpulse = clamp(input.leftImpulse + sway * strength);
        input.forwardImpulse = clamp(input.forwardImpulse + lurch * strength * 0.6F);
    }

    private static float clamp(float v) {
        return Math.max(-1.0F, Math.min(1.0F, v));
    }
}
