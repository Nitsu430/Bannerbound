package com.bannerbound.core.chat;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.world.level.GameRules;

/**
 * Custom game rules owned by the mod, registered vanilla-style. NeoForge's bundled access transformer
 * exposes {@code GameRules.register} and {@code BooleanValue/IntegerValue.create} publicly, so these
 * register directly. {@link #register()} is idempotent and MUST run before any level loads - it is
 * called from the mod constructor.
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code globalChat} (bool, default false): when true, proximity-chat handling
 *       ({@link com.bannerbound.core.event.ChatEvents}) is off and chat / {@code /msg|/tell|/w}
 *       behave exactly like vanilla; when false they are range-limited and fade with distance
 *       ({@link ProximityChat}).</li>
 *   <li>{@code celestialSpeed} (int, default 1): faith-sky orbital/seasonal time multiplier for
 *       testing (0 = frozen heavens, big = time-lapse). Synced to clients via
 *       {@link com.bannerbound.core.api.faith.SkyStateSync}.</li>
 *   <li>{@code meteorAmount} (int, default 2): ambient meteors per minute (0 = none, big = storm);
 *       synced like celestialSpeed.</li>
 *   <li>{@code allowOfflineWar} (bool, default false): when false, declaring war needs an online
 *       target member and the warning countdown pauses if they log out.</li>
 *   <li>{@code useCustomLanguage} (bool, default false): when true, known item titles and controlled
 *       job/entity labels use generated language (citizen names always do); change fires
 *       {@link com.bannerbound.core.language.CustomLanguageSync}.</li>
 *   <li>{@code forceMaxAge} (era): hard cap on how far any settlement / the world age may progress -
 *       a Bannerbound {@link com.bannerbound.core.api.settlement.Era} name (ancient, classical, ...,
 *       future) plus alias {@code none} (= last era = no cap), tab-completed as friendly presets
 *       ({@link ForceMaxAgeGameRule} / {@link com.bannerbound.core.command.EraGameRuleArgument}).
 *       While capped, research that would advance past the cap is a no-op and capped nodes can't be
 *       started/queued (enforced in {@link com.bannerbound.core.api.research.ResearchManager}); the
 *       cap is forward-only - lowering it freezes further progress, it does not roll settlements
 *       back. Friendly setter: {@code /bannerbound force_max_age <era>}.</li>
 * </ul>
 *
 * <p>forceMaxAge's default is the LAST era (no cap), derived dynamically from
 * {@code Era.values().length - 1} so inserting an era never silently caps the game.
 */
@ApiStatus.Internal
public final class BannerboundGameRules {
    private BannerboundGameRules() {
    }

    public static GameRules.Key<GameRules.BooleanValue> GLOBAL_CHAT;

    public static GameRules.Key<GameRules.IntegerValue> CELESTIAL_SPEED;

    public static GameRules.Key<GameRules.IntegerValue> METEOR_AMOUNT;

    public static GameRules.Key<GameRules.BooleanValue> ALLOW_OFFLINE_WAR;

    public static GameRules.Key<GameRules.BooleanValue> USE_CUSTOM_LANGUAGE;

    public static GameRules.Key<ForceMaxAgeGameRule> FORCE_MAX_AGE;

    public static void register() {
        if (GLOBAL_CHAT != null) {
            return;
        }
        GLOBAL_CHAT = GameRules.register(
            "globalChat",
            GameRules.Category.CHAT,
            GameRules.BooleanValue.create(false));
        CELESTIAL_SPEED = GameRules.register(
            "celestialSpeed",
            GameRules.Category.MISC,
            GameRules.IntegerValue.create(1, (server, value) ->
                com.bannerbound.core.api.faith.SkyStateSync.broadcast(server)));
        METEOR_AMOUNT = GameRules.register(
            "meteorAmount",
            GameRules.Category.MISC,
            GameRules.IntegerValue.create(2, (server, value) ->
                com.bannerbound.core.api.faith.SkyStateSync.broadcast(server)));
        ALLOW_OFFLINE_WAR = GameRules.register(
            "allowOfflineWar",
            GameRules.Category.PLAYER,
            GameRules.BooleanValue.create(false));
        USE_CUSTOM_LANGUAGE = GameRules.register(
            "useCustomLanguage",
            GameRules.Category.MISC,
            GameRules.BooleanValue.create(false, (server, value) ->
                com.bannerbound.core.language.CustomLanguageSync.onRuleChanged(server)));
        FORCE_MAX_AGE = GameRules.register(
            "forceMaxAge",
            GameRules.Category.MISC,
            ForceMaxAgeGameRule.type(lastEra(), (server, value) -> {}));
    }

    private static com.bannerbound.core.api.settlement.Era lastEra() {
        com.bannerbound.core.api.settlement.Era[] vals =
            com.bannerbound.core.api.settlement.Era.values();
        return vals[vals.length - 1];
    }
}
