package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.hunter.HunterHooks;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Hunter GathererWorkGoal -- hunts wild (undomesticated) animals OUTSIDE the settlement's claims,
 * the field counterpart to the herder (who tends domesticated stock inside a pen). Huntable
 * species are data-driven via the #bannerbound:huntable entity-type tag, so modded animals opt in
 * from a datapack; the Job-tab prey toggle (isHunterPreyEnabled) narrows that per citizen.
 *
 * <p>Like the forager it does no giant prey scans: it roams the wild band (unclaimed chunks within
 * HUNT_BAND_CHUNKS of our border, a deeper ring than the forager's) and watches a small radius
 * around itself. An animal that flees INTO any settlement's claimed land gets sanctuary -- the
 * hunter only ever kills on unclaimed ground. A per-mob hunt claim (expiry-stamped persistent-data
 * tags) stops two hunters converging on one animal; a dead hunter's claim simply lapses. The prey
 * scan is throttled and rescanCooldown is seeded from the entity id so a batch of hunters hired at
 * once don't all sweep the same tick.
 *
 * <p>Weapons resolve through the "hunt" tool-age role (swords in the Core ages, a spear in
 * Antiquity's bone age). Once the settlement researches Archery the hunter swaps its melee tool for
 * a stored bow (#bannerbound:hunter_bows) and shoots from range; its arrows are skeleton-style
 * DISALLOWED pickup so they can't be farmed. With a throwable spear the HunterHooks extension opens
 * each engagement with a throw before the melee kill -- a planted stealth throw while the prey is
 * still calm (standing up is what spooks it), or a snap running throw once it has bolted and is
 * pulling away. Tool damage scales by craftsmanship quality (effectiveness only, never durability
 * -- NPC tools don't wear); bow shots apply quality through the arrow velocity factor so it isn't
 * double-counted. The bow upgrade is government-only; anarchy hunters keep self-organizing
 * bare-handed.
 *
 * <p>Prey dies a normal death; HunterKillEvents reroutes the known-set-filtered death drops
 * straight into the hunter's drop-off, so the hunter never hauls meat home by hand.
 */
@ApiStatus.Internal
public class HunterWorkGoal extends GathererWorkGoal {
    public static final String JOB_TYPE_ID = "hunters_camp";

    public static final TagKey<EntityType<?>> HUNTABLE_TAG = TagKey.create(Registries.ENTITY_TYPE,
        ResourceLocation.fromNamespaceAndPath("bannerbound", "huntable"));

    public static final TagKey<net.minecraft.world.item.Item> HUNTER_BOWS_TAG = TagKey.create(
        Registries.ITEM, ResourceLocation.fromNamespaceAndPath("bannerbound", "hunter_bows"));

    private static final int LEASH_RADIUS = 80;
    private static final int HUNT_BAND_CHUNKS = 6;
    private static final int ROAM_TIMEOUT_TICKS = 300;
    private static final double ARRIVE_SQ = 2.2 * 2.2;
    private static final int BARREN_YIELD_STREAK = 4;
    private static final int BARREN_COOLDOWN_TICKS = 300;
    private static final int RESCAN_COOLDOWN_TICKS = 20;

    private static final int PREY_SCAN_RADIUS = 24;
    private static final int PREY_SCAN_HEIGHT = 8;

    private static final double MELEE_REACH_SQ = 4.0;
    private static final double CHASE_SPEED_FACTOR = 1.5;
    private static final double STEALTH_SPEED_FACTOR = 0.55;
    private static final int REPATH_INTERVAL = 10;
    private static final int ENGAGE_TIMEOUT_TICKS = 600;
    private static final int AVOID_PREY_TICKS = 1200;
    private static final int CLAIM_TICKS = 100;
    private static final int STAMINA_PER_KILL = 8;

    private static final double BOW_RANGE = 14.0;
    private static final int BOW_DRAW_TICKS = 25;
    private static final int BOW_COOLDOWN_TICKS = 20;
    private static final double ARROW_VELOCITY = 1.6;
    private static final float ARROW_INACCURACY = 2.0F;

    private static final double SPEAR_RANGE = 14.0;
    private static final int SPEAR_WINDUP_TICKS = 20;
    private static final double RUNNING_THROW_MIN_SQ = 6.0 * 6.0;
    private static final int RUNNING_THROW_WINDUP_TICKS = 12;

    private static final String CLAIM_ID_TAG = "BannerboundHuntedBy";
    private static final String CLAIM_UNTIL_TAG = "BannerboundHuntClaimUntil";

    private static final double DEFAULT_BARE_HAND_DAMAGE = 1.0;
    private static final double DEFAULT_ATTACK_SPEED = 1.0;

    private enum Phase { ROAM, HUNT }

    private enum Windup { NONE, BOW, SPEAR }

    private Phase phase = Phase.ROAM;
    private Mob target;
    private BlockPos roamPos;
    private int roamAge;
    private int rescanCooldown;
    private int barrenStreak;
    private int engageAge;
    private int attackCooldown;
    private int repathCooldown;
    private Windup windup = Windup.NONE;
    private int windupTicks;
    private boolean windupMoving;
    private boolean spearThrown;
    private int avoidEntityId = -1;
    private long avoidUntilGameTime;

    public HunterWorkGoal(CitizenEntity citizen, double speedModifier) {
        super(citizen, speedModifier);
        this.rescanCooldown = citizen.getId() % RESCAN_COOLDOWN_TICKS;
    }

    @Override
    protected String workstationTypeId() {
        return JOB_TYPE_ID;
    }

    private Container resolveDepot() {
        return DropOffContainers.resolveJobDepot(citizen);
    }

    @Override
    protected boolean canStartWork() {
        citizen.validateJobStorage();
        if (!citizen.isGatherJobReady(JOB_TYPE_ID)) return false;
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        Container depot = resolveDepot();
        if (depot == null || !DropOffContainers.hasFreeSlot(depot)) return false;
        maybeUpgradeToBow(sl);

        if (target != null && isValidTarget(sl, target)) {
            phase = Phase.HUNT;
            return true;
        }
        target = null;
        if (rescanCooldown-- > 0) return false;

        Mob prey = findPreyNear(sl);
        if (prey != null) {
            setTarget(sl, prey);
            return true;
        }
        BlockPos roam = pickRoamPos(sl);
        if (roam == null) {
            rescanCooldown = BARREN_COOLDOWN_TICKS;
            return false;
        }
        roamPos = roam;
        roamAge = 0;
        phase = Phase.ROAM;
        return true;
    }

    @Override
    protected boolean canKeepWorking() {
        if (!citizen.isGatherJobReady(JOB_TYPE_ID)) return false;
        Container depot = resolveDepot();
        return depot != null && DropOffContainers.hasFreeSlot(depot);
    }

    @Override
    public void start() {
        citizen.setWorking(true);
        equipWeapon();
        citizen.setAvoidWaterPathing(true);
        attackCooldown = 0;
        repathCooldown = 0;
        if (phase == Phase.HUNT && target != null) {
            citizen.getNavigation().moveTo(target, skilledSpeed());
        } else if (roamPos != null) {
            moveTo(roamPos, skilledSpeed());
        }
    }

    @Override
    public void stop() {
        citizen.setWorking(false);
        stopWindup();
        standUp();
        citizen.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.setAvoidWaterPathing(false);
        target = null;
        roamPos = null;
        spearThrown = false;
        phase = Phase.ROAM;
    }

    @Override
    public void tick() {
        if (!citizen.isGatherJobReady(JOB_TYPE_ID) || !(citizen.level() instanceof ServerLevel sl)) return;
        if (attackCooldown > 0) attackCooldown--;
        switch (phase) {
            case ROAM -> tickRoam(sl);
            case HUNT -> tickHunt(sl);
        }
    }

    private void tickRoam(ServerLevel sl) {
        if (rescanCooldown-- <= 0) {
            rescanCooldown = RESCAN_COOLDOWN_TICKS;
            Mob prey = findPreyNear(sl);
            if (prey != null) {
                setTarget(sl, prey);
                barrenStreak = 0;
                return;
            }
        }
        if (roamPos == null) { roamPos = pickRoamPos(sl); roamAge = 0; return; }
        double d = citizen.position().distanceToSqr(roamPos.getX() + 0.5, roamPos.getY(), roamPos.getZ() + 0.5);
        if (d <= ARRIVE_SQ || ++roamAge > ROAM_TIMEOUT_TICKS) {
            if (++barrenStreak >= BARREN_YIELD_STREAK) {
                barrenStreak = 0;
                roamPos = null;
                rescanCooldown = BARREN_COOLDOWN_TICKS;
                return;
            }
            roamPos = pickRoamPos(sl);
            roamAge = 0;
            if (roamPos != null) moveTo(roamPos, skilledSpeed());
            return;
        }
        if (citizen.getNavigation().isDone()) moveTo(roamPos, skilledSpeed());
    }

    private BlockPos pickRoamPos(ServerLevel sl) {
        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return null;
        BlockPos near = pickBandPointNear(sl, settlement, citizen.blockPosition());
        if (near != null) return near;
        BlockPos drop = citizen.getDropOff();
        return drop != null ? pickBandPointNear(sl, settlement, drop) : null;
    }

    private BlockPos pickBandPointNear(ServerLevel sl, Settlement settlement, BlockPos anchor) {
        for (int attempt = 0; attempt < 12; attempt++) {
            int x = anchor.getX() + citizen.getRandom().nextInt(LEASH_RADIUS * 2 + 1) - LEASH_RADIUS;
            int z = anchor.getZ() + citizen.getRandom().nextInt(LEASH_RADIUS * 2 + 1) - LEASH_RADIUS;
            if (!inHuntBandColumn(sl, settlement, x, z)) continue;
            int y = sl.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (WorkerPathing.isWalkable(sl, p) || WorkerPathing.isWalkable(sl, p.above())) {
                return p;
            }
        }
        return null;
    }

    private static boolean inHuntBandColumn(ServerLevel sl, Settlement settlement, int x, int z) {
        if (settlement == null) return false;
        int cx = x >> 4;
        int cz = z >> 4;
        if (SettlementData.get(sl).getByChunk(ChunkPos.asLong(cx, cz)) != null) return false;
        java.util.Set<Long> ours = settlement.claimedChunks();
        for (int dx = -HUNT_BAND_CHUNKS; dx <= HUNT_BAND_CHUNKS; dx++) {
            for (int dz = -HUNT_BAND_CHUNKS; dz <= HUNT_BAND_CHUNKS; dz++) {
                if (ours.contains(ChunkPos.asLong(cx + dx, cz + dz))) return true;
            }
        }
        return false;
    }

    private void tickHunt(ServerLevel sl) {
        if (target == null) { phase = Phase.ROAM; return; }
        if (!target.isAlive()) { onKill(); return; }
        if (!isValidTarget(sl, target) || ++engageAge > ENGAGE_TIMEOUT_TICKS) {
            giveUpOnTarget(sl);
            return;
        }
        refreshClaim(sl, target);
        citizen.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (windup != Windup.NONE) {
            tickWindup(sl);
            return;
        }

        ItemStack weapon = citizen.getJobTool();
        double dSq = citizen.distanceToSqr(target);

        if (isBowWeapon(weapon)) {
            if (dSq <= BOW_RANGE * BOW_RANGE && citizen.hasLineOfSight(target)) {
                citizen.getNavigation().stop();
                if (attackCooldown <= 0) beginWindup(Windup.BOW, BOW_DRAW_TICKS, false, false);
                return;
            }
            approach(sl, dSq);
            return;
        }

        if (!spearThrown && HunterHooks.get().isThrowableSpear(weapon)
                && dSq <= SPEAR_RANGE * SPEAR_RANGE && dSq > MELEE_REACH_SQ * 2.0
                && citizen.hasLineOfSight(target)) {
            if (!HunterHooks.get().isPreyScared(target)) {
                citizen.getNavigation().stop();
                beginWindup(Windup.SPEAR, SPEAR_WINDUP_TICKS,
                    HunterHooks.get().wantsStealth(citizen, target), false);
                return;
            }
            if (dSq > RUNNING_THROW_MIN_SQ) {
                beginWindup(Windup.SPEAR, RUNNING_THROW_WINDUP_TICKS, false, true);
                return;
            }
        }

        if (dSq <= MELEE_REACH_SQ) {
            standUp();
            citizen.getNavigation().stop();
            if (attackCooldown <= 0) {
                citizen.swing(InteractionHand.MAIN_HAND);
                target.hurt(citizen.damageSources().mobAttack(citizen), (float) qualityScaledDamage());
                attackCooldown = meleeCooldownTicks();
                if (!target.isAlive()) onKill();
            }
        } else {
            approach(sl, dSq);
        }
    }

    private void approach(ServerLevel sl, double dSq) {
        HunterHooks.Extension ext = HunterHooks.get();
        double speed;
        if (ext.isPreyScared(target)) {
            standUp();
            speed = skilledSpeed() * CHASE_SPEED_FACTOR;
        } else if (ext.wantsStealth(citizen, target)) {
            crouch();
            speed = skilledSpeed() * STEALTH_SPEED_FACTOR;
        } else {
            standUp();
            speed = skilledSpeed();
        }
        if (--repathCooldown <= 0 || citizen.getNavigation().isDone()) {
            citizen.getNavigation().moveTo(target, speed);
            repathCooldown = REPATH_INTERVAL;
        }
    }

    private void beginWindup(Windup kind, int ticks, boolean keepCrouch, boolean moving) {
        if (!keepCrouch) standUp();
        windup = kind;
        windupTicks = ticks;
        windupMoving = moving;
        citizen.startUsingItem(InteractionHand.MAIN_HAND);   // held item's UseAnim drives the client arm pose (BOW_AND_ARROW / THROW_SPEAR)
    }

    private void tickWindup(ServerLevel sl) {
        double rangeSq = (windup == Windup.BOW ? BOW_RANGE * BOW_RANGE : SPEAR_RANGE * SPEAR_RANGE) * 1.5;
        if (target == null || !target.isAlive()
                || !citizen.hasLineOfSight(target)
                || citizen.distanceToSqr(target) > rangeSq) {
            stopWindup();
            return;
        }
        citizen.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (windupMoving && (--repathCooldown <= 0 || citizen.getNavigation().isDone())) {
            citizen.getNavigation().moveTo(target, skilledSpeed() * CHASE_SPEED_FACTOR);
            repathCooldown = REPATH_INTERVAL;
        }
        if (--windupTicks > 0) return;
        Windup kind = windup;
        Mob prey = target;
        stopWindup();
        if (kind == Windup.BOW) {
            shootArrow(sl, prey);
            attackCooldown = BOW_COOLDOWN_TICKS;
        } else if (kind == Windup.SPEAR) {
            if (HunterHooks.get().throwSpear(citizen, prey, citizen.getJobTool().copy(), qualityScaledDamage())) {
                spearThrown = true;
            } else {
                spearThrown = true;
            }
        }
    }

    private void stopWindup() {
        if (citizen.isUsingItem()) citizen.stopUsingItem();
        windup = Windup.NONE;
        windupTicks = 0;
        windupMoving = false;
    }

    private void shootArrow(ServerLevel sl, Mob prey) {
        ItemStack bow = citizen.getJobTool();
        AbstractArrow arrow = HunterHooks.get().createArrow(citizen, bow);
        if (arrow == null) {
            arrow = new Arrow(sl, citizen, new ItemStack(Items.ARROW), citizen.getMainHandItem());
        }
        arrow.setBaseDamage(Math.max(2.0, weaponDamage() / ARROW_VELOCITY));
        arrow.pickup = AbstractArrow.Pickup.DISALLOWED;   // skeleton-style: never collectible, so hunters can't be farmed for arrows
        float velocity = (float) ARROW_VELOCITY * HunterHooks.get().bowVelocityFactor(bow);
        double dx = prey.getX() - citizen.getX();
        double dy = prey.getY(0.3333) - arrow.getY();
        double dz = prey.getZ() - citizen.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        arrow.shoot(dx, dy + horiz * 0.2, dz, velocity, ARROW_INACCURACY);
        sl.addFreshEntity(arrow);
        sl.playSound(null, citizen.blockPosition(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL,
            1.0F, 1.0F / (citizen.getRandom().nextFloat() * 0.4F + 0.8F));
    }

    private void setTarget(ServerLevel sl, Mob prey) {
        target = prey;
        engageAge = 0;
        spearThrown = false;
        phase = Phase.HUNT;
        refreshClaim(sl, prey);
        citizen.getNavigation().moveTo(prey, skilledSpeed());
    }

    private void onKill() {
        citizen.grantJobXp(JOB_TYPE_ID, 1.0F, "hunt");
        citizen.consumeStamina(STAMINA_PER_KILL);
        target = null;
        spearThrown = false;
        engageAge = 0;
        standUp();
        rescanCooldown = 0;
        phase = Phase.ROAM;
    }

    private void giveUpOnTarget(ServerLevel sl) {
        if (target != null) {
            avoidEntityId = target.getId();
            avoidUntilGameTime = sl.getGameTime() + AVOID_PREY_TICKS;
        }
        stopWindup();
        standUp();
        target = null;
        spearThrown = false;
        engageAge = 0;
        phase = Phase.ROAM;
        rescanCooldown = RESCAN_COOLDOWN_TICKS;
    }

    private Mob findPreyNear(ServerLevel sl) {
        long now = sl.getGameTime();
        AABB box = citizen.getBoundingBox().inflate(PREY_SCAN_RADIUS, PREY_SCAN_HEIGHT, PREY_SCAN_RADIUS);
        Mob best = null;
        double bestSq = Double.MAX_VALUE;
        for (Mob m : sl.getEntitiesOfClass(Mob.class, box, m -> isHuntable(sl, m, now))) {
            double d = citizen.distanceToSqr(m);
            if (d < bestSq) { best = m; bestSq = d; }
        }
        return best;
    }

    private boolean isValidTarget(ServerLevel sl, Mob m) {
        return isHuntable(sl, m, sl.getGameTime());
    }

    private boolean isHuntable(ServerLevel sl, Mob m, long now) {
        if (!m.isAlive() || m.isBaby()) return false;
        if (!m.getType().is(HUNTABLE_TAG)) return false;
        if (!citizen.isHunterPreyEnabled(m.getType())) return false;
        if (m.getId() == avoidEntityId && now < avoidUntilGameTime) return false;
        if (m.isLeashed()) return false;
        if (m.getPersistentData().getBoolean(HerderWorkGoal.DOMESTICATED_TAG)) return false;
        if (m instanceof TamableAnimal t && t.isTame()) return false;
        if (m instanceof AbstractHorse h && h.isTamed()) return false;
        Integer herded = m.getExistingDataOrNull(BannerboundCore.HERDED_BY.get());
        if (herded != null && herded != 0) return false;
        if (HunterHooks.get().isDomesticated(m)) return false;
        if (claimedByOtherHunter(m, now)) return false;
        return SettlementData.get(sl).getByChunk(new ChunkPos(m.blockPosition()).toLong()) == null;
    }

    private boolean claimedByOtherHunter(Mob m, long now) {
        CompoundTag t = m.getPersistentData();
        return t.getLong(CLAIM_UNTIL_TAG) >= now && t.getInt(CLAIM_ID_TAG) != citizen.getId();
    }

    private void refreshClaim(ServerLevel sl, Mob m) {
        CompoundTag t = m.getPersistentData();
        t.putInt(CLAIM_ID_TAG, citizen.getId());
        t.putLong(CLAIM_UNTIL_TAG, sl.getGameTime() + CLAIM_TICKS);
    }

    private void equipWeapon() {
        ItemStack tool = citizen.getJobTool();
        citizen.setItemSlot(EquipmentSlot.MAINHAND, tool.isEmpty() ? ItemStack.EMPTY : tool.copy());
    }

    private static boolean isBowWeapon(ItemStack stack) {
        return stack.is(HUNTER_BOWS_TAG) || stack.getItem() instanceof BowItem;
    }

    private void maybeUpgradeToBow(ServerLevel sl) {
        if (citizen.isAnarchy()) return;
        if (isBowWeapon(citizen.getJobTool())) return;
        Settlement s = citizen.getSettlement();
        if (s == null || !ResearchManager.hasFlag(s, HunterHooks.FLAG_ARCHERY)) return;
        Container storage = DropOffContainers.resolvePreferredStorage(citizen);
        if (storage == null) return;
        ItemStack bow = ItemStack.EMPTY;
        for (int i = 0; i < storage.getContainerSize() && bow.isEmpty(); i++) {
            if (isBowWeapon(storage.getItem(i))) {
                bow = storage.removeItem(i, 1);
            }
        }
        if (bow.isEmpty()) return;
        storage.setChanged();
        ItemStack old = citizen.getJobTool();
        if (!old.isEmpty()) {
            ItemStack leftover = DropOffContainers.insert(storage, old);
            if (!leftover.isEmpty()) citizen.spawnAtLocation(leftover);
        }
        citizen.setJobTool(bow);
        if (citizen.isWorking()) equipWeapon();
    }

    private double weaponDamage() {
        Settlement s = citizen.getSettlement();
        return s == null ? DEFAULT_BARE_HAND_DAMAGE : s.getWeaponDamageOrDefault(DEFAULT_BARE_HAND_DAMAGE);
    }

    private double qualityScaledDamage() {
        return weaponDamage()
            * com.bannerbound.core.api.quality.QualityTier.of(citizen.getJobTool()).statMultiplier();
    }

    private int meleeCooldownTicks() {
        Settlement s = citizen.getSettlement();
        double atkSpeed = s == null ? DEFAULT_ATTACK_SPEED : s.getWeaponAttackSpeedOrDefault(DEFAULT_ATTACK_SPEED);
        if (atkSpeed <= 0.0) return 20;
        return Math.max(5, (int) Math.round(20.0 / atkSpeed));
    }

    private void crouch() {
        if (citizen.getPose() != Pose.CROUCHING) citizen.setPose(Pose.CROUCHING);
    }

    private void standUp() {
        if (citizen.getPose() == Pose.CROUCHING) citizen.setPose(Pose.STANDING);
    }

    private void moveTo(BlockPos p, double speed) {
        if (p == null) return;
        citizen.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, speed);
    }
}
