package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.antiquity.combat.BluntStun;
import com.bannerbound.antiquity.entity.BlowdartProjectile;
import com.bannerbound.antiquity.item.AntidoteItem;
import com.bannerbound.antiquity.item.PoisonedFoodData;
import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.antiquity.poison.Poisons;
import com.bannerbound.antiquity.tannery.WeaponCategory;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.HerderWorkGoal;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.CriticalHitEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Drives the poison lifecycle for EVERY living entity — player, wild animal, or citizen — so a single
 * shared path handles escalation, damage-over-time and each poison's signature effect (vanilla's
 * {@link HuntingEvents} bleed handler is {@code Animal}-only, hence a separate subscriber here).
 *
 * <p>Hot path: this fires for every {@link LivingEntity} every tick, so the not-poisoned case is a
 * single attachment read returning the {@code NONE} sentinel and an immediate bail with no allocation.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID)
@ApiStatus.Internal
public final class PoisonEvents {
    private PoisonEvents() {}

    @SubscribeEvent
    static void onLivingPoisonTick(EntityTickEvent.Post event) {
        if (!Config.POISON_ENABLED.get() || !(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        if (!(living.level() instanceof ServerLevel level)) {
            return;
        }
        tickFoodPoison(living, level); // a delayed dose from laced food may now be due
        if (Poisons.isPoisoned(living)) {
            Poisons.tickPoison(living, level);
        }
    }

    /** Apply a pending food-poison dose once its delay elapses (set when the victim ate laced food). */
    private static void tickFoodPoison(LivingEntity living, ServerLevel level) {
        long applyAt = living.getData(BannerboundAntiquity.POISON_FOOD_APPLY_AT.get());
        if (applyAt <= 0L || level.getGameTime() < applyAt) {
            return;
        }
        PoisonType type = PoisonType.fromId(living.getData(BannerboundAntiquity.POISON_FOOD_TYPE.get()));
        int stage = living.getData(BannerboundAntiquity.POISON_FOOD_STAGE.get());
        living.setData(BannerboundAntiquity.POISON_FOOD_APPLY_AT.get(), 0L);
        living.setData(BannerboundAntiquity.POISON_FOOD_TYPE.get(), "");
        living.setData(BannerboundAntiquity.POISON_FOOD_STAGE.get(), 0);
        if (type != null && stage > 0) {
            Poisons.applyPoisonAtStage(living, type, stage); // dose → starting stage
        }
    }

    /** Eating laced food schedules its poison a short while later (so the meal isn't the obvious cause).
     *  Fires for any entity that finishes eating; only laced stacks carry the {@code POISONED_FOOD}
     *  component, so the clean-food case is one component read. */
    @SubscribeEvent
    static void onFinishEating(LivingEntityUseItemEvent.Finish event) {
        if (!Config.POISON_ENABLED.get() || event.getEntity().level().isClientSide) {
            return;
        }
        PoisonedFoodData laced = event.getItem().get(BannerboundAntiquity.POISONED_FOOD.get());
        if (laced == null || PoisonType.fromId(laced.poisonId()) == null) {
            return;
        }
        LivingEntity eater = event.getEntity();
        eater.setData(BannerboundAntiquity.POISON_FOOD_APPLY_AT.get(),
            eater.level().getGameTime() + Config.POISON_FOOD_DELAY_TICKS.get());
        eater.setData(BannerboundAntiquity.POISON_FOOD_TYPE.get(), laced.poisonId());
        eater.setData(BannerboundAntiquity.POISON_FOOD_STAGE.get(), laced.dose());
    }

    /** Oleander attacks the healing system: while it's active, ALL healing is blocked — natural regen,
     *  golden apples, regen potions, everything — so any damage taken sticks while its cardiac clock
     *  runs down. Fires for every healing entity, so the not-oleander case is one attachment read. */
    @SubscribeEvent
    static void onLivingHeal(LivingHealEvent event) {
        if (Config.POISON_ENABLED.get() && Poisons.blocksHealing(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** Shift-right-click any poisoned creature (mob, player, or citizen) WITH an antidote to cure THEM
     *  of that antidote's poison. Handled on {@code EntityInteract}, which fires BEFORE the target's own
     *  interaction — so it works even on entities that open a menu on right-click (citizens). Only fires
     *  when the target actually has the matching poison; otherwise it passes through (no wasted antidote,
     *  normal interaction still happens). */
    @SubscribeEvent
    static void onAntidoteOnEntity(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (!player.isShiftKeyDown() || !Config.POISON_ENABLED.get()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof AntidoteItem antidote)
            || !(event.getTarget() instanceof LivingEntity target)
            || Poisons.getPoison(target).type() != antidote.cures()) {
            return;
        }
        if (!player.level().isClientSide) {
            Poisons.cure(target, antidote.cures()); // clears only this antidote's poison + plays the heal cue
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(player.level().isClientSide));
    }

    /** A curare-unconscious player can't act — cancel their own attacks and interactions (the dragger
     *  is never unconscious, so the kidnap grab / antidote-on-target are unaffected). NeoForge forbids
     *  subscribing to the abstract {@link PlayerInteractEvent} base, so each concrete right/left-click
     *  subclass gets a thin handler delegating to {@link #cancelIfUnconscious}. */
    @SubscribeEvent
    static void onUnconsciousRightClickBlock(PlayerInteractEvent.RightClickBlock event) { cancelIfUnconscious(event); }

    @SubscribeEvent
    static void onUnconsciousRightClickItem(PlayerInteractEvent.RightClickItem event) { cancelIfUnconscious(event); }

    @SubscribeEvent
    static void onUnconsciousLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) { cancelIfUnconscious(event); }

    @SubscribeEvent
    static void onUnconsciousEntityInteract(PlayerInteractEvent.EntityInteract event) { cancelIfUnconscious(event); }

    @SubscribeEvent
    static void onUnconsciousEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) { cancelIfUnconscious(event); }

    private static void cancelIfUnconscious(PlayerInteractEvent event) {
        if (curareUnconscious(event.getEntity()) && event instanceof ICancellableEvent cancellable) {
            cancellable.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onUnconsciousAttack(AttackEntityEvent event) {
        if (curareUnconscious(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** No fall damage while curare-unconscious — honours the non-lethal promise and avoids the tow/
     *  pin velocity spiking into a fall hit. */
    @SubscribeEvent
    static void onUnconsciousFall(LivingIncomingDamageEvent event) {
        if (event.getSource().is(DamageTypeTags.IS_FALL) && curareUnconscious(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private static boolean curareUnconscious(LivingEntity entity) {
        return Poisons.isCurareUnconscious(entity, entity.level().getGameTime());
    }

    /*
     * Poison ARROWS aren't a special item — ANY arrow (the {@code #minecraft:arrows} tag) is "tipped" by
     * coating it with a poison paste, which stamps the {@code ARROW_POISON} component (see
     * {@link com.bannerbound.antiquity.item.PoisonPasteItem}). This delivers it on the fired arrow ENTITY,
     * read off the ammo it was shot from: a colour trail in flight, and the poison applied on a creature
     * hit. Works for vanilla AND flint arrows alike (the renderer/model are untouched — the only tell is
     * the open tooltip + the trail).
     */

    /** The poison coating the arrow (from the ammo stack it carries), or {@code null}. */
    @Nullable
    private static PoisonType arrowPoison(AbstractArrow arrow) {
        String id = arrow.getPickupItemStackOrigin().get(BannerboundAntiquity.ARROW_POISON.get());
        return id == null ? null : PoisonType.fromId(id);
    }

    /** Apply the coating's poison when a poison-tipped arrow strikes a creature (vanilla damage still
     *  runs — the event isn't cancelled). */
    @SubscribeEvent
    static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!Config.POISON_ENABLED.get()
            || !(event.getProjectile() instanceof AbstractArrow arrow)
            || arrow.level().isClientSide
            || !(event.getRayTraceResult() instanceof EntityHitResult hit)) {
            return;
        }
        PoisonType poison = arrowPoison(arrow);
        if (poison != null && hit.getEntity() instanceof LivingEntity living && living.isAlive()) {
            // Same friendly-fire rule as curare darts: the kidnap poison never lands on the
            // shooter's own settlement (the arrow itself still hits — only the coating is inert).
            if (poison == PoisonType.CURARE && arrow.getOwner() instanceof LivingEntity shooter
                && PoisonEvents.sameSettlement(shooter, living)) {
                return;
            }
            Poisons.applyPoison(living, poison, arrow.getOwner()); // credit the eventual poison kill to the shooter
        }
    }

    /** A poison-tipped arrow trails its poison's colour in flight — server-spawned, so every client
     *  sees the right colour without syncing anything. */
    @SubscribeEvent
    static void onArrowTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof AbstractArrow arrow)
            || !(arrow.level() instanceof ServerLevel server)
            || arrow.getDeltaMovement().lengthSqr() < 0.02) { // only while actually flying, not stuck
            return;
        }
        PoisonType poison = arrowPoison(arrow);
        if (poison == null) {
            return;
        }
        int c = poison.tintColor();
        server.sendParticles(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT,
                ((c >> 16) & 0xFF) / 255.0F, ((c >> 8) & 0xFF) / 255.0F, (c & 0xFF) / 255.0F),
            arrow.getX(), arrow.getY(), arrow.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
    }

    /** Show the coating on any tipped arrow's tooltip — openly (it's a weapon, not a hidden lace). */
    @SubscribeEvent
    static void onTooltip(ItemTooltipEvent event) {
        String id = event.getItemStack().get(BannerboundAntiquity.ARROW_POISON.get());
        PoisonType poison = id == null ? null : PoisonType.fromId(id);
        if (poison != null) {
            event.getToolTip().add(Component.translatable("bannerboundantiquity.poison_arrow.tooltip",
                    Component.translatable("poison.bannerboundantiquity." + poison.id()))
                .withStyle(ChatFormatting.DARK_GREEN));
        }
    }

    /*
     * Blunt weapons stagger on a crit. When a player lands a critical hit with anything in
     * {@code #bannerboundantiquity:blunt_weapons} (the bone club, the smithing hammers), the struck
     * creature is stunned for 1s — half movement speed plus, for a struck player, a blurred-vision daze
     * (see {@link BluntStun}). A single shared handler so every present and future blunt weapon gets it
     * for free by sitting in the tag.
     */

    /** A crit with a blunt weapon staggers the target. Fires on both sides; {@link BluntStun#stun}
     *  is server-gated, so the client call is a harmless no-op. */
    @SubscribeEvent
    static void onBluntCrit(CriticalHitEvent event) {
        if (!event.isCriticalHit()
            || !event.getEntity().getMainHandItem().is(WeaponCategory.BLUNT_WEAPONS)
            || !(event.getTarget() instanceof LivingEntity target)) {
            return;
        }
        BluntStun.stun(target);
    }

    /** Expire the stagger once its deadline passes (clears the half-speed modifier). Cheap bail for
     *  the common un-stunned entity: a single attachment read returning the 0L default. */
    @SubscribeEvent
    static void onLivingTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof LivingEntity living && !living.level().isClientSide
            && living.getData(BannerboundAntiquity.STUN_UNTIL.get()) > 0L) {
            BluntStun.tick(living);
        }
    }

    /*
     * The curare "kidnap" drag: right-click a curare-UNCONSCIOUS creature with a fiber rope (any
     * {@code #bannerbound:herder_rope} item) to tether it, then walk — it's towed behind you. Works on
     * animals, citizens (clean, server-authoritative), and players (immobilised first so they have no
     * input authority, then towed via velocity packets + a teleport catch-up for big gaps — slightly
     * rubber-bandy, as players can't be vanilla-leashed). The link is the synced {@code DRAGGED_BY} (on
     * the victim, drives the rope render) + server-only {@code DRAGGING} (on the dragger, drives the tow).
     */
    private static final double FOLLOW_DIST = 2.0;    // the victim trails this far behind
    private static final double DRAG_SPEED = 0.4;     // blocks/tick pull cap
    private static final double TELEPORT_DIST = 4.0;  // beyond this, snap a player closer (client authority)
    private static final double MAX_TETHER = 8.0;     // beyond this the rope "snaps" and releases

    /** Grab/release: a PLAIN (non-shift) rope click on a curare-unconscious target toggles the drag.
     *  Shift is reserved for FiberRopeItem's spear-reel, and the antidote-cure uses shift too. */
    @SubscribeEvent
    static void onRopeGrab(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (player.isShiftKeyDown() || !HerderWorkGoal.isRope(event.getItemStack())
            || !(event.getTarget() instanceof LivingEntity target)
            || !Poisons.isCurareUnconscious(target, target.level().getGameTime())) {
            return;
        }
        // No kidnapping your own settlement's members — cross-settlement/neutral targets only.
        // The click is still consumed so the rope doesn't fall through to another interaction.
        if (!player.level().isClientSide && !sameSettlement(player, target)) {
            int cur = target.getData(BannerboundAntiquity.DRAGGED_BY.get());
            if (cur == player.getId()) {
                release(player, target); // re-click my own victim → let go
            } else {
                if (cur != 0 && target.level().getEntity(cur) instanceof LivingEntity prev) {
                    prev.setData(BannerboundAntiquity.DRAGGING.get(), 0); // steal from a previous dragger
                }
                target.setData(BannerboundAntiquity.DRAGGED_BY.get(), player.getId());
                player.setData(BannerboundAntiquity.DRAGGING.get(), target.getId());
            }
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(player.level().isClientSide));
    }

    /** Friendly-fire guard on the kidnap poison's delivery: a curare dart shot at a member of the
     *  shooter's OWN settlement (player or citizen) passes through harmlessly — same rule as the
     *  drag grab above, so members can't be put under by their own. Other poisons are unaffected. */
    @SubscribeEvent
    static void onDartImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof BlowdartProjectile dart) || dart.level().isClientSide
            || dart.getPoison() != PoisonType.CURARE
            || !(event.getRayTraceResult() instanceof EntityHitResult hit)
            || !(hit.getEntity() instanceof LivingEntity target)
            || !(dart.getOwner() instanceof LivingEntity shooter)
            || !sameSettlement(shooter, target)) {
            return;
        }
        event.setCanceled(true);
    }

    /** True when both entities resolve to the SAME settlement (players via the member roster,
     *  citizens via their own link). Unsettled/unresolvable entities are never "same settlement",
     *  so neutral kidnapping keeps working. Mirrors {@link HerdingEvents#leashingUnlocked}'s
     *  defensive settlement resolution. */
    static boolean sameSettlement(LivingEntity a, LivingEntity b) {
        Settlement sa = settlementOf(a);
        if (sa == null) {
            return false;
        }
        Settlement sb = settlementOf(b);
        return sb != null && sa.id().equals(sb.id());
    }

    private static Settlement settlementOf(LivingEntity e) {
        if (e instanceof CitizenEntity citizen) {
            return citizen.getSettlement();
        }
        if (e instanceof ServerPlayer sp) {
            MinecraftServer server = sp.getServer();
            if (server == null) {
                return null;
            }
            try {
                return SettlementData.get(server.overworld()).getByPlayer(sp.getUUID());
            } catch (Exception ex) {
                return null; // no settlement / not loaded → treat as unsettled
            }
        }
        return null;
    }

    /** Tow the dragged victim toward the dragger each tick (run from the dragger's own tick). */
    @SubscribeEvent
    static void onDraggerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Player dragger) || dragger.level().isClientSide) {
            return;
        }
        int victimId = dragger.getData(BannerboundAntiquity.DRAGGING.get());
        if (victimId == 0) {
            return;
        }
        Entity e = dragger.level().getEntity(victimId);
        long now = dragger.level().getGameTime();
        if (!(e instanceof LivingEntity victim) || !victim.isAlive()
            || !Poisons.isCurareUnconscious(victim, now)
            || !holdsRope(dragger)
            || dragger.distanceTo(victim) > MAX_TETHER) {
            release(dragger, e instanceof LivingEntity lv ? lv : null);
            return;
        }
        tow(dragger, victim);
    }

    private static void tow(Player dragger, LivingEntity victim) {
        Vec3 toDragger = new Vec3(dragger.getX() - victim.getX(), 0.0, dragger.getZ() - victim.getZ());
        double dist = toDragger.length();
        if (dist <= FOLLOW_DIST) {
            return; // close enough — let it rest (trailing behind)
        }
        Vec3 dir = toDragger.scale(1.0 / dist);
        Vec3 pull = dir.scale(Math.min(DRAG_SPEED, dist - FOLLOW_DIST));
        if (victim instanceof ServerPlayer sp) {
            // The player is already rooted (speed -1) so they can't fight this; nudge, and for a big
            // gap force a teleport to a trailing point (velocity packets alone lag/rubber-band).
            sp.setDeltaMovement(pull.x, sp.getDeltaMovement().y, pull.z);
            sp.hurtMarked = true;
            if (dist > TELEPORT_DIST) {
                Vec3 tp = dragger.position().subtract(dir.scale(FOLLOW_DIST));
                sp.connection.teleport(tp.x, dragger.getY(), tp.z, sp.getYRot(), sp.getXRot());
            }
        } else {
            if (victim instanceof PathfinderMob mob) {
                mob.getNavigation().stop();
            }
            victim.setDeltaMovement(pull.x, victim.getDeltaMovement().y, pull.z);
            victim.hurtMarked = true;
        }
    }

    private static boolean holdsRope(Player dragger) {
        return HerderWorkGoal.isRope(dragger.getMainHandItem())
            || HerderWorkGoal.isRope(dragger.getOffhandItem());
    }

    /** Drop both ends of the rope link. */
    static void release(Player dragger, LivingEntity victim) {
        dragger.setData(BannerboundAntiquity.DRAGGING.get(), 0);
        if (victim != null) {
            victim.setData(BannerboundAntiquity.DRAGGED_BY.get(), 0);
        }
    }
}
