package com.bannerbound.antiquity.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.antiquity.combat.BluntStun;
import com.bannerbound.antiquity.entity.BlowdartProjectile;
import com.bannerbound.antiquity.item.AntidoteItem;
import com.bannerbound.antiquity.item.PoisonedFoodData;
import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.antiquity.poison.Poisons;
import com.bannerbound.antiquity.workshop.WeaponCategory;
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
import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;

/**
 * Drives the poison lifecycle for EVERY living entity -- player, wild animal, or citizen -- so one
 * shared path handles escalation, damage-over-time and each poison's signature effect
 * ({@link HuntingEvents}' bleed handler is {@code Animal}-only, hence a separate subscriber). Hot
 * path: the tick handlers fire for every {@link LivingEntity} every tick, so each not-affected case
 * is a single attachment read returning a sentinel (NONE / 0L) and an immediate bail, no allocation.
 *
 * <p>Delivery: laced food stamps POISON_FOOD_* attachments on the eater and the dose lands only
 * after a config delay, so the meal isn't the obvious cause. Any {@code #minecraft:arrows} item is
 * "tipped" by poison paste stamping the ARROW_POISON component; it is read off the fired arrow's
 * origin ammo stack to poison a struck creature (vanilla damage still runs; the poison kill credits
 * the shooter), spawn a server-side colour trail (no sync needed), and show openly on the tooltip.
 * Antidotes cure via shift-right-click, handled on {@code EntityInteract} which fires BEFORE the
 * target's own interaction so it works on menu-opening entities (citizens); the antidote is consumed
 * only when the target has its matching poison. Oleander blocks ALL healing (regen, potions,
 * everything) while active. A crit with any {@code #bannerboundantiquity:blunt_weapons} item
 * staggers the target ~1s via {@link BluntStun} (handler fires both sides; stun/expire server-gated).
 *
 * <p>Curare is the non-lethal kidnap poison: an unconscious victim cannot attack or interact and
 * takes no fall damage (the tow could spike velocity into a fall hit). A PLAIN rope click (shift is
 * reserved for FiberRopeItem's spear-reel and the antidote cure) toggles the drag, linking synced
 * DRAGGED_BY on the victim (drives the rope render) to server-only DRAGGING on the dragger (drives
 * the tow); the grab click is consumed even when blocked so the rope never falls through to another
 * interaction. The victim trails FOLLOW_DIST behind, pulled at up to DRAG_SPEED blocks/tick; dragged
 * players are pre-immobilised and towed via velocity packets plus a teleport catch-up beyond
 * TELEPORT_DIST; the rope snaps past MAX_TETHER. Friendly-fire on every curare delivery (dart,
 * arrow coating, grab): it never lands on the shooter's own settlement, and {@link #sameSettlement}
 * treats unresolvable entities as unsettled so neutral kidnapping keeps working.
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
        tickFoodPoison(living, level);
        if (Poisons.isPoisoned(living)) {
            Poisons.tickPoison(living, level);
        }
    }

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
            Poisons.applyPoisonAtStage(living, type, stage);
        }
    }

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

    @SubscribeEvent
    static void onLivingHeal(LivingHealEvent event) {
        if (Config.POISON_ENABLED.get() && Poisons.blocksHealing(event.getEntity())) {
            event.setCanceled(true);
        }
    }

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
            Poisons.cure(target, antidote.cures());
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(player.level().isClientSide));
    }

    // NeoForge rejects @SubscribeEvent on the abstract PlayerInteractEvent base -> one thin handler per concrete subclass
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

    @SubscribeEvent
    static void onUnconsciousFall(LivingIncomingDamageEvent event) {
        if (event.getSource().is(DamageTypeTags.IS_FALL) && curareUnconscious(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private static boolean curareUnconscious(LivingEntity entity) {
        return Poisons.isCurareUnconscious(entity, entity.level().getGameTime());
    }

    @Nullable
    private static PoisonType arrowPoison(AbstractArrow arrow) {
        String id = arrow.getPickupItemStackOrigin().get(BannerboundAntiquity.ARROW_POISON.get());
        return id == null ? null : PoisonType.fromId(id);
    }

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
            if (poison == PoisonType.CURARE && arrow.getOwner() instanceof LivingEntity shooter
                && PoisonEvents.sameSettlement(shooter, living)) {
                return;
            }
            Poisons.applyPoison(living, poison, arrow.getOwner());
        }
    }

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

    @SubscribeEvent
    static void onBluntCrit(CriticalHitEvent event) {
        if (!event.isCriticalHit()
            || !event.getEntity().getMainHandItem().is(WeaponCategory.BLUNT_WEAPONS)
            || !(event.getTarget() instanceof LivingEntity target)) {
            return;
        }
        BluntStun.stun(target);
    }

    @SubscribeEvent
    static void onLivingTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof LivingEntity living && !living.level().isClientSide
            && living.getData(BannerboundAntiquity.STUN_UNTIL.get()) > 0L) {
            BluntStun.tick(living);
        }
    }

    private static final double FOLLOW_DIST = 2.0;
    private static final double DRAG_SPEED = 0.4;
    private static final double TELEPORT_DIST = 4.0;
    private static final double MAX_TETHER = 8.0;

    @SubscribeEvent
    static void onRopeGrab(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (player.isShiftKeyDown() || !HerderWorkGoal.isRope(event.getItemStack())
            || !(event.getTarget() instanceof LivingEntity target)
            || !Poisons.isCurareUnconscious(target, target.level().getGameTime())) {
            return;
        }
        if (!player.level().isClientSide && !sameSettlement(player, target)) {
            int cur = target.getData(BannerboundAntiquity.DRAGGED_BY.get());
            if (cur == player.getId()) {
                release(player, target);
            } else {
                if (cur != 0 && target.level().getEntity(cur) instanceof LivingEntity prev) {
                    prev.setData(BannerboundAntiquity.DRAGGING.get(), 0);
                }
                target.setData(BannerboundAntiquity.DRAGGED_BY.get(), player.getId());
                player.setData(BannerboundAntiquity.DRAGGING.get(), target.getId());
            }
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(player.level().isClientSide));
    }

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
                return null;
            }
        }
        return null;
    }

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
            return;
        }
        Vec3 dir = toDragger.scale(1.0 / dist);
        Vec3 pull = dir.scale(Math.min(DRAG_SPEED, dist - FOLLOW_DIST));
        if (victim instanceof ServerPlayer sp) {
            // hurtMarked forces the velocity packet; players keep client authority, so big gaps need the teleport
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

    static void release(Player dragger, LivingEntity victim) {
        dragger.setData(BannerboundAntiquity.DRAGGING.get(), 0);
        if (victim != null) {
            victim.setData(BannerboundAntiquity.DRAGGED_BY.get(), 0);
        }
    }
}
