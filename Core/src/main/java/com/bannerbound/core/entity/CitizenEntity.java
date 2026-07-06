package com.bannerbound.core.entity;

import java.util.UUID;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.CitizenTrait;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.social.Relationships;
import com.bannerbound.core.social.ThoughtKind;
import com.bannerbound.core.social.Thoughts;
import com.bannerbound.core.api.settlement.ChunkBeauty;
import com.bannerbound.core.api.settlement.ChunkBeautyManager;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * The settlement citizen: a PathfinderMob soft-bound to its Settlement by UUID and reattached to the
 * roster on chunk reload. It persists its own settlement + identity in NBT and, with no player within
 * AI_ACTIVE_RANGE, drops to an idle activation tier (still a real, loaded, punchable entity that falls
 * and takes damage) so a settlement with nobody home costs almost nothing. Heavy decisions (pathfinding
 * kick-offs) are further staggered onto a per-entity "think tick" so the fleet's A* calls spread across
 * ticks instead of spiking on one; movement is never gated, so the staggering is invisible.
 *
 * <p>Identity is synced through entity-data (gender, traits, texture variant, era, happiness, pregnancy,
 * job ordinal, poison stage, ...) so the renderer always agrees with the server. The given name is baked
 * into the settlement's language ONCE at initializeCitizen and stored verbatim; refreshDisplayName
 * rebuilds the visible name-tag (optional pregnancy glyph + gender glyph + settlement-colored name +
 * job-icon suffix) whenever pregnancy, gender, job, era, or banner color change -- never re-derive the
 * baked name from the displayed component. Era tracks the settlement advancing; the name pool stays
 * fixed at the era of immigration, so a citizen restyles their look but not their name.
 *
 * <p>Employment lives ON the citizen, not on workstation blocks: a job type id + held tool(s) + a marked
 * drop-off + per-profession XP. setJobType is the single choke point every job change passes through
 * (workshop unbind, courier opt-out, tool return on reskill, fishing-boat bail-out, name rebuild), so new
 * job logic hangs off it rather than the call sites. Workers carry no inventory -- gathered drops are
 * routed to the drop-off / workstation BE via a capture window. In anarchy (no government) citizens
 * self-organize: they work tool-free but slower (ANARCHY_TOOLFREE_WORK_FACTOR) and pile yield into a
 * town-hall carry pack until DeliverHaulGoal hauls it home. A per-citizen stamina pool throttles work
 * (1 log = -1); the "resting" flag latches at 0 and clears only at full, and regen pauses only while a
 * work goal is actively running.
 *
 * <p>Mood: the Thoughts list aggregates into the synced happiness score (recomputeHappiness after every
 * change); the happiness band drives one performance multiplier applied to walk speed, work-step speed,
 * and XP so tooltip promises and in-world effect can't drift. Compliance + per-player resentment (the
 * "Step 9-13" data layer) tick once per in-game hour: sustained misery erodes compliance directly, and
 * low compliance surfaces as work-refusal thoughts + a dawn full-day strike.
 *
 * <p>Combat: guards fight hostiles always; ordinary adults only while the settlement is rallying;
 * children never (engagesHostiles gates the target selectors). Priority-0 goals compete for the
 * MOVE+LOOK flags by INSERTION order -- the flee / combat / guard / brawl / panic ordering in
 * registerGoals is deliberate.
 *
 * <p>Cross-module contract: the POISON_STAGE_TAG and TELEPORT_AT_KEY persistent-data string literals are
 * mirrored by Antiquity (which cannot import Core) -- do not rename them. Any deliberate position jump of
 * an already-existing citizen must call tagDeliberateTeleport in the same tick or a rope fence bounces it
 * back. On load, setJobType runs before the vehicle mounts passengers, so getVehicle() is null then.
 *
 * <p>Open: AI_LOD_INTERVAL whole-step tick-LOD is disabled (=1) because skipping the whole step also
 * skips movement and stutters; smooth tick-LOD would need a mixin throttling only decisions/repaths.
 */
public class CitizenEntity extends PathfinderMob {
    private static final String TAG_SETTLEMENT_ID = "SettlementId";
    private static final String TAG_STAMINA = "Stamina";
    private static final String TAG_STAMINA_TIMER = "StaminaTimer";
    private static final String TAG_RESTING = "Resting";

    public static final net.minecraft.resources.ResourceLocation SPEED_MODIFIER_ID =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("bannerbound", "civic_speed");

    public static final int MAX_STAMINA = 100;

    private static final int RECHARGE_TICKS_PER_POINT = 12;

    private static final double BASE_MOVEMENT_SPEED = 0.4;

    private static final int AI_LOD_INTERVAL = 1;

    private static final double AI_ACTIVE_RANGE = 64.0;

    private static final int AI_ACTIVATION_RECHECK_TICKS = 10;

    private boolean aiActive = true;
    private int aiActivationRecheckAt = 0;

    private static final int THINK_PERIOD = 8;

    private static final EntityDataAccessor<Integer> DATA_STAMINA =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Boolean> DATA_CASTING =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<Integer> DATA_GENDER =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_TRAITS =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_TEXTURE_VARIANT =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_ERA =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_BUBBLE =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_HAPPINESS =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Boolean> DATA_PREGNANT =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<Boolean> DATA_IS_CHILD =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);

    private static final EntityDataAccessor<Long> DATA_PREGNANT_SINCE_TICK =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.LONG);

    private static final EntityDataAccessor<Integer> DATA_TOOL_SHOVEL =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_TOOL_HOE =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_JOB =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Integer> DATA_POISON_STAGE =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.INT);

    private static final EntityDataAccessor<Boolean> DATA_WORK_BLOCKED =
        SynchedEntityData.defineId(CitizenEntity.class, EntityDataSerializers.BOOLEAN);

    public static final String POISON_STAGE_TAG = "BannerboundPoisonStage";

    private static final String TAG_GENDER = "Gender";
    private static final String TAG_TRAITS = "Traits";
    private static final String TAG_TEXTURE_VARIANT = "TextureVariant";
    private static final String TAG_ERA = "Era";
    private static final String TAG_RELATIONS = "Relations";
    private static final String TAG_THOUGHTS = "Thoughts";

    private static final String TAG_CHUNK_SAMPLES = "ChunkSamples";

    private static final String TAG_LAST_CHUNK_EVAL_DAY = "ChunkEvalDay";

    private static final ResourceLocation ICONS_FONT =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "icons");

    private static final String MALE_GLYPH = Character.toString(0xE100);
    private static final String FEMALE_GLYPH = Character.toString(0xE101);

    private static final String PREGNANT_GLYPH = Character.toString(0xE102);

    private static final String TAG_PREGNANT = "Pregnant";
    private static final String TAG_PREGNANT_SINCE_TICK = "PregnantSinceTick";
    private static final String TAG_PREGNANCY_FATHER = "PregnancyFather";
    private static final String TAG_IS_CHILD = "IsChild";
    private static final String TAG_BORN_AT_TICK = "BornAtTick";
    private static final String TAG_MOTHER = "Mother";

    private static final String TAG_CITIZEN_NAME = "CitizenName";

    private static final String TAG_NAME_BAKED = "NameBaked";
    private static final String TAG_EARNED_SURNAME_CONCEPT = "EarnedSurnameConcept";
    private static final String TAG_EARNED_SURNAME_JOB = "EarnedSurnameJob";
    private static final String TAG_EARNED_SURNAME_TICK = "EarnedSurnameTick";
    private static final int SURNAME_XP_THRESHOLD = 80;

    private static final String TAG_COMPLIANCE = "Compliance";

    private static final String TAG_RESENTMENT_MAP = "ResentmentByPlayer";
    private static final String TAG_RESENTMENT_UUID = "Uuid";
    private static final String TAG_RESENTMENT_VALUE = "Value";

    private static final String TAG_JOB_TYPE = "JobType";
    private static final String TAG_JOB_PINNED = "JobPinned";
    private static final String TAG_WORKSHOP_ID = "WorkshopId";
    private static final String TAG_JOB_TOOL = "JobTool";
    private static final String TAG_JOB_PICKAXE = "JobPickaxe";
    private static final String TAG_PREFERRED_LOG = "PreferredLog";
    private static final String TAG_DROP_OFF = "DropOff";
    private static final String TAG_SEED_SOURCE = "SeedSource";
    private static final String TAG_FORAGE_TARGETS = "ForageTargets";
    private static final String TAG_HUNTER_PREY_OFF = "HunterPreyOff";
    private static final String TAG_FORESTER_KEEP_EXTRAS = "ForesterKeepExtras";
    private static final String TAG_SEED_CACHE = "SeedCache";
    private static final String TAG_ANARCHY_HAUL = "AnarchyHaul";

    private static final int COMPLIANCE_MIN = 0;
    private static final int COMPLIANCE_MAX = 100;
    private static final int DEFAULT_COMPLIANCE = COMPLIANCE_MAX;

    private static final int UNEMPLOYED_COMPLIANCE_FLOOR = 30;

    private UUID settlementId;
    private int staminaRechargeTimer = 0;

    private int compliance = DEFAULT_COMPLIANCE;

    private final java.util.Map<UUID, Integer> resentmentByPlayer = new java.util.HashMap<>();

    private transient double complianceFractional = 0.0;

    private final Relationships relationships = new Relationships();

    private final Thoughts thoughts = new Thoughts();

    private final int[] chunkSamples = new int[ChunkBeauty.values().length];

    private long lastChunkEvalDay = -1L;

    private int conversationCooldown = 0;

    private boolean resting = false;

    private boolean working = false;

    private String citizenName;

    private boolean nameBaked;

    private String earnedSurnameConcept;
    private String earnedSurnameJob;
    private long earnedSurnameTick = -1L;

    private ChatFormatting nameColor = ChatFormatting.WHITE;

    private UUID pregnancyFatherId;

    private long bornAtTick = -1L;

    private UUID motherId;

    @org.jetbrains.annotations.Nullable
    private UUID lastBrawlOpponentId = null;

    private long lastBrawlTick = 0L;

    @org.jetbrains.annotations.Nullable
    private UUID pendingRetaliationTargetId = null;

    private long pendingRetaliationTick = 0L;

    private double stuckLastX, stuckLastY, stuckLastZ;
    private int stuckTicks = 0;

    private static final double STUCK_RADIUS_SQ = 0.25;

    private static final int STUCK_SAMPLE_INTERVAL = 40; 

    private static final int STUCK_TICK_THRESHOLD = 8 * STUCK_SAMPLE_INTERVAL; 

    private long captureWindowEndTick = -1;

    private BlockPos captureCenter;

    private String jobTypeId = null;

    private boolean jobPinned = false;

    private java.util.UUID assignedWorkshopId = null;

    private final java.util.Map<String, Float> jobXp = new java.util.HashMap<>();

    private int lastJobIconItemId = Integer.MIN_VALUE;

    private net.minecraft.world.item.ItemStack jobTool = net.minecraft.world.item.ItemStack.EMPTY;

    private net.minecraft.world.item.ItemStack jobPickaxe = net.minecraft.world.item.ItemStack.EMPTY;

    private net.minecraft.world.level.block.Block preferredLog = net.minecraft.world.level.block.Blocks.OAK_LOG;

    private int forageTargetBits = com.bannerbound.core.api.forager.ForageCategory.ALL_BITS;

    private final java.util.Set<String> hunterPreyOff = new java.util.HashSet<>();

    private boolean foresterKeepExtras = true;

    private CitizenWorkStatus currentWorkStatus = CitizenWorkStatus.IDLE;

    private final net.minecraft.world.SimpleContainer seedCache = new net.minecraft.world.SimpleContainer(6);

    private final net.minecraft.world.SimpleContainer anarchyHaul = new net.minecraft.world.SimpleContainer(64);

    private BlockPos dropOffPos = null;

    private BlockPos seedSourcePos = null;

    private boolean simulated = false;

    private boolean tradingCourier = false;

    @Nullable
    private UUID tradeJourneyId = null;

    public boolean isTradingCourier() {
        return tradingCourier;
    }

    public void setTradingCourier(boolean value) {
        this.tradingCourier = value;
    }

    @Nullable
    public UUID getTradeJourneyId() {
        return tradeJourneyId;
    }

    public void setTradeJourneyId(@Nullable UUID id) {
        this.tradeJourneyId = id;
    }

    public boolean isOnTradeJourney() {
        return tradeJourneyId != null;
    }

    public CitizenEntity(EntityType<? extends CitizenEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setCustomNameVisible(true);

        if (this.getNavigation() instanceof GroundPathNavigation gpn) {
            gpn.setCanOpenDoors(true);
            gpn.setCanPassDoors(true);
            gpn.setCanFloat(true); 
        }
    }

    @Override
    public boolean isPushable() {
        return !HerderWorkGoal.JOB_TYPE_ID.equals(jobTypeId) && super.isPushable();
    }

    @Override
    protected PathNavigation createNavigation(Level level) {

        return new CitizenGroundNavigation(this, level);
    }

    @Override
    public HumanoidArm getMainArm() {

        return hasTrait(CitizenTrait.LEFT_HANDED) ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.4)

            .add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    public float getPathfindingMalus(PathType type) {
        return switch (type) {
            case DAMAGE_FIRE, LAVA -> -1.0f;
            case DANGER_FIRE -> 24.0f;
            case DAMAGE_OTHER, DANGER_OTHER -> 16.0f;
            default -> super.getPathfindingMalus(type);
        };
    }

    private boolean deepDigDescent = false;

    public void setDeepDigDescent(boolean on) {
        this.deepDigDescent = on;
    }

    private boolean avoidWaterPathing = false;

    public void setAvoidWaterPathing(boolean on) {
        this.avoidWaterPathing = on;
    }

    public boolean isAvoidWaterPathing() {
        return avoidWaterPathing;
    }

    private BlockPos outpostSite;

    public void setOutpostSite(BlockPos site) {
        this.outpostSite = site;
    }

    public BlockPos getOutpostSite() {
        return outpostSite;
    }

    private boolean roadBuilding;

    public void setRoadBuilding(boolean on) {
        this.roadBuilding = on;
    }

    public boolean isRoadBuilding() {
        return roadBuilding;
    }

    @Override
    public int getMaxFallDistance() {

        return deepDigDescent ? 24 : super.getMaxFallDistance();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypes.CAMPFIRE)) {
            return false;
        }

        return super.hurt(source, amount);
    }

    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, net.minecraft.world.damagesource.DamageSource source) {
        if (deepDigDescent) return false; 
        return super.causeFallDamage(fallDistance, multiplier, source);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_STAMINA, MAX_STAMINA);
        builder.define(DATA_CASTING, false);
        builder.define(DATA_GENDER, CitizenGender.MALE.ordinal());
        builder.define(DATA_TRAITS, 0);
        builder.define(DATA_TEXTURE_VARIANT, 0);
        builder.define(DATA_ERA, Era.ANCIENT.ordinal());
        builder.define(DATA_BUBBLE, 0);
        builder.define(DATA_HAPPINESS, Thoughts.BASE_HAPPINESS);
        builder.define(DATA_PREGNANT, false);
        builder.define(DATA_IS_CHILD, false);
        builder.define(DATA_PREGNANT_SINCE_TICK, -1L);
        builder.define(DATA_TOOL_SHOVEL, 0);
        builder.define(DATA_TOOL_HOE, 0);
        builder.define(DATA_JOB, 0);
        builder.define(DATA_POISON_STAGE, 0);
        builder.define(DATA_WORK_BLOCKED, false);
    }

    public boolean isWorkBlocked() { return this.entityData.get(DATA_WORK_BLOCKED); }

    public void setWorkBlocked(boolean blocked) {
        if (this.entityData.get(DATA_WORK_BLOCKED) != blocked) {
            this.entityData.set(DATA_WORK_BLOCKED, blocked);
        }
    }

    public net.minecraft.world.item.Item getToolShovelItem() {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(this.entityData.get(DATA_TOOL_SHOVEL));
    }
    public net.minecraft.world.item.Item getToolHoeItem() {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(this.entityData.get(DATA_TOOL_HOE));
    }

    public CitizenGender getGender() {
        return CitizenGender.fromOrdinalOrMale(this.entityData.get(DATA_GENDER));
    }

    public boolean hasTrait(CitizenTrait trait) {
        return (this.entityData.get(DATA_TRAITS) & trait.bit()) != 0;
    }

    public int getTextureVariant() {
        return this.entityData.get(DATA_TEXTURE_VARIANT);
    }

    public Era getEra() {
        return Era.fromOrdinalOrDefault(this.entityData.get(DATA_ERA));
    }

    public boolean isCasting() { return this.entityData.get(DATA_CASTING); }
    public void setCasting(boolean casting) { this.entityData.set(DATA_CASTING, casting); }

    public int getCompliance() { return compliance; }

    public void setCompliance(int value) {
        compliance = Math.max(COMPLIANCE_MIN, Math.min(COMPLIANCE_MAX, value));
    }

    public int getResentment(UUID player) {
        if (player == null) return 0;
        Integer v = resentmentByPlayer.get(player);
        return v == null ? 0 : v;
    }

    public void addResentment(UUID player, int delta) {
        if (player == null) return;
        int next = Math.max(0, getResentment(player) + delta);
        if (next == 0) resentmentByPlayer.remove(player);
        else resentmentByPlayer.put(player, next);
    }

    public java.util.Map<UUID, Integer> resentmentByPlayer() {
        return java.util.Collections.unmodifiableMap(resentmentByPlayer);
    }

    public int getLeaderResentmentMax(java.util.Collection<UUID> leaders) {
        if (leaders == null || leaders.isEmpty()) return 0;
        int max = 0;
        for (UUID leader : leaders) {
            int v = getResentment(leader);
            if (v > max) max = v;
        }
        return max;
    }

    private static final int HOURLY_TICK_PERIOD_TICKS = 1000;

    private static final int RESENTMENT_GAIN_HAPPINESS_THRESHOLD = 50;

    private static final int RESENTMENT_GAIN_DIVISOR = 15;

    private static final int RESENTMENT_DECAY_HAPPINESS_THRESHOLD = 60;

    private static final int COMPLIANCE_GAIN_HAPPINESS_THRESHOLD = 60;

    private static final double COMPLIANCE_GAIN_PER_STEP = 1.5;

    private static final int COMPLIANCE_LOSS_HAPPINESS_THRESHOLD = 60;
    private static final double COMPLIANCE_LOSS_PER_STEP = 0.4;

    private static final int COMPLIANCE_LOSS_RESENTMENT_THRESHOLD = 20;
    private static final double COMPLIANCE_RESENTMENT_LOSS_PER_STEP = 0.25;

    private static final int BRINK_AVG_RESENTMENT = 80;

    private void tickComplianceResentmentHourly(Settlement settlement) {
        java.util.Set<UUID> leaders = settlement.leaderPlayerIds();
        int happiness = getHappiness();

        if (!leaders.isEmpty()) {

            if (happiness < RESENTMENT_GAIN_HAPPINESS_THRESHOLD) {
                int steps = (RESENTMENT_GAIN_HAPPINESS_THRESHOLD - happiness) / RESENTMENT_GAIN_DIVISOR;
                if (steps > 0) for (UUID leader : leaders) addResentment(leader, steps);
            }

            if (happiness > RESENTMENT_DECAY_HAPPINESS_THRESHOLD) {
                int steps = 2 * (happiness - RESENTMENT_DECAY_HAPPINESS_THRESHOLD) / 5;
                if (steps > 0) for (UUID leader : leaders) addResentment(leader, -steps);
            }
        }

        double delta = 0.0;
        if (happiness > COMPLIANCE_GAIN_HAPPINESS_THRESHOLD) {
            int steps = (happiness - COMPLIANCE_GAIN_HAPPINESS_THRESHOLD) / 5;
            delta += steps * COMPLIANCE_GAIN_PER_STEP;
        } else {

            if (happiness < COMPLIANCE_LOSS_HAPPINESS_THRESHOLD) {
                int steps = (COMPLIANCE_LOSS_HAPPINESS_THRESHOLD - happiness) / 5;
                delta -= steps * COMPLIANCE_LOSS_PER_STEP;
            }
            if (!leaders.isEmpty()) {

                int leaderResentment = getLeaderResentmentMax(leaders);
                if (leaderResentment > COMPLIANCE_LOSS_RESENTMENT_THRESHOLD) {
                    int steps = (leaderResentment - COMPLIANCE_LOSS_RESENTMENT_THRESHOLD) / 5;
                    delta -= steps * COMPLIANCE_RESENTMENT_LOSS_PER_STEP;
                }

                double avgLeaderResentment = averageLeaderResentment(leaders);
                boolean brink = avgLeaderResentment > BRINK_AVG_RESENTMENT
                    || settlement.isCoupSuppressed();
                if (brink && delta < 0) {
                    delta *= 2.0;
                }
            }
        }
        if (delta != 0.0) {
            complianceFractional += delta;

            int wholeStep = (int) complianceFractional;
            if (wholeStep != 0) {
                setCompliance(getCompliance() + wholeStep);
                complianceFractional -= wholeStep;
            }
        }
    }

    private double averageLeaderResentment(java.util.Set<UUID> leaders) {
        if (leaders.isEmpty()) return 0.0;
        int total = 0;
        for (UUID leader : leaders) total += getResentment(leader);
        return total / (double) leaders.size();
    }

    public int getBubbleTopic() { return this.entityData.get(DATA_BUBBLE); }

    public void setBubbleTopic(int id) { this.entityData.set(DATA_BUBBLE, id); }

    public Relationships getRelationships() { return relationships; }
    public Thoughts getThoughts() { return thoughts; }

    private boolean hasWorkRefusalThought() {
        if (thoughts.has(ThoughtKind.NO_WORK_RIGHT_NOW, null)) return true;
        if (thoughts.has(ThoughtKind.NO_WORK_TODAY, null)) return true;
        for (com.bannerbound.core.social.Thought th : thoughts.entries()) {
            if (th.kind() == ThoughtKind.NO_WORK_AS_JOB) return true;
        }
        return false;
    }

    private void setHappinessInternal(int value) {
        int clamped = Math.max(Thoughts.MIN_HAPPINESS, Math.min(Thoughts.MAX_HAPPINESS, value));
        if (this.entityData.get(DATA_HAPPINESS) != clamped) {
            this.entityData.set(DATA_HAPPINESS, clamped);
        }
    }

    public int getHappiness() { return this.entityData.get(DATA_HAPPINESS); }

    public int getPoisonStage() { return this.entityData.get(DATA_POISON_STAGE); }

    public boolean isPoisoned() { return getPoisonStage() > 0; }

    public void setPoisonStage(int stage) {
        int clamped = Math.max(0, stage);
        if (this.entityData.get(DATA_POISON_STAGE) != clamped) {
            this.entityData.set(DATA_POISON_STAGE, clamped);
        }
    }

    public int getHappinessMax() { return Thoughts.MAX_HAPPINESS; }

    public static final float HAPPINESS_GREEN_RATIO = 0.70f; 
    public static final float HAPPINESS_RED_RATIO   = 0.40f; 

    public static int happinessBand(int happiness, int max) {
        double ratio = max > 0 ? (double) happiness / (double) max : 0.5;
        if (ratio >= HAPPINESS_GREEN_RATIO) return 1;
        if (ratio <  HAPPINESS_RED_RATIO)   return -1;
        return 0;
    }

    public float happinessPerformanceMultiplier() {
        return switch (happinessBand(getHappiness(), getHappinessMax())) {
            case 1  -> 1.15f;
            case -1 -> 0.70f;
            default -> 1.0f;
        };
    }

    public float happinessSpeedMultiplier() {
        return switch (happinessBand(getHappiness(), getHappinessMax())) {
            case 1  -> 1.15f;
            case -1 -> 0.90f;
            default -> 1.0f;
        };
    }

    public void recomputeHappiness() {

        long now = this.level() != null ? this.level().getGameTime() : 0L;
        setHappinessInternal(thoughts.aggregateHappiness(now));
    }

    public int getConversationCooldown() { return conversationCooldown; }
    public void setConversationCooldown(int ticks) { this.conversationCooldown = Math.max(0, ticks); }

    public static boolean isHostileToCitizens(net.minecraft.world.entity.LivingEntity entity) {
        if (entity instanceof net.minecraft.world.entity.monster.EnderMan) return false;
        return entity instanceof net.minecraft.world.entity.monster.Zombie
            || entity instanceof net.minecraft.world.entity.monster.Skeleton
            || entity instanceof net.minecraft.world.entity.monster.AbstractSkeleton
            || entity instanceof net.minecraft.world.entity.monster.Spider
            || entity instanceof net.minecraft.world.entity.monster.Creeper
            || entity instanceof net.minecraft.world.entity.monster.Witch
            || entity instanceof net.minecraft.world.entity.monster.AbstractIllager
            || entity instanceof net.minecraft.world.entity.monster.Vex;
    }

    @Override
    protected void registerGoals() {
        // Priority-0 goals compete for MOVE+LOOK by INSERTION order; this flee/combat/guard/brawl order is load-bearing.
        this.goalSelector.addGoal(0, new FloatGoal(this));

        this.goalSelector.addGoal(0, new TradeCourierGoal(this));

        this.goalSelector.addGoal(0, new net.minecraft.world.entity.ai.goal.AvoidEntityGoal<>(
            this, net.minecraft.world.entity.monster.Creeper.class,
            (java.util.function.Predicate<net.minecraft.world.entity.LivingEntity>)
                living -> living instanceof net.minecraft.world.entity.monster.Creeper c && c.getSwellDir() > 0,
            8.0f, 1.1, 1.25,
            (java.util.function.Predicate<net.minecraft.world.entity.LivingEntity>)
                living -> living instanceof net.minecraft.world.entity.monster.Creeper c && c.getSwellDir() > 0));

        this.goalSelector.addGoal(0, new CitizenCombatGoal(this, 1.1));

        this.goalSelector.addGoal(0, new GuardCombatGoal(this, 1.1));

        this.goalSelector.addGoal(0, new BrawlRetaliationGoal(this));

        this.goalSelector.addGoal(1, new CitizenPanicGoal(this, 1.5));

        this.goalSelector.addGoal(1, new GuardMusterGoal(this, 1.1));

        this.targetSelector.addGoal(1,
            new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<net.minecraft.world.entity.Mob>(
                this, net.minecraft.world.entity.Mob.class, 10, true, false,
                (java.util.function.Predicate<net.minecraft.world.entity.LivingEntity>)
                    m -> engagesHostiles() && !isGuard() && isHostileToCitizens(m)));
        this.targetSelector.addGoal(0,
            new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<Player>(
                this, Player.class, 5, true, false,
                (java.util.function.Predicate<net.minecraft.world.entity.LivingEntity>)
                    target -> com.bannerbound.core.api.settlement.DiplomacyManager.isRallyTarget(this, target)));

        this.targetSelector.addGoal(2,
            new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<CitizenEntity>(
                this, CitizenEntity.class, 10, true, false,
                (java.util.function.Predicate<net.minecraft.world.entity.LivingEntity>)
                    t -> engagesHostiles() && !isGuard() && isHostileBarbarianToMe(t)));

        this.targetSelector.addGoal(2,
            new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<MercenaryEntity>(
                this, MercenaryEntity.class, 10, true, false,
                (java.util.function.Predicate<net.minecraft.world.entity.LivingEntity>)
                    e -> engagesHostiles() && !isGuard() && isHostileMercenaryToMe(e)));

        this.targetSelector.addGoal(1, new GuardTargetingGoal(this));

        this.goalSelector.addGoal(2, new OpenDoorGoal(this, true));

        this.goalSelector.addGoal(2, new OpenFenceGateGoal(this));

        this.goalSelector.addGoal(2, new OutpostCommuteGoal(this, 0.85));

        this.goalSelector.addGoal(2, new SleepGoal(this, 0.9));

        this.goalSelector.addGoal(3, new DeliverHaulGoal(this, 0.85));

        this.goalSelector.addGoal(3, new ForesterPlantationGoal(this, 0.8));
        this.goalSelector.addGoal(3, new ForesterWorkGoal(this, 0.8));
        this.goalSelector.addGoal(3, new DiggerWorkGoal(this, 0.8));
        this.goalSelector.addGoal(3, new FarmerWorkGoal(this, 0.8));
        this.goalSelector.addGoal(3, new FisherWorkGoal(this, 0.8));
        this.goalSelector.addGoal(3, new ForagerWorkGoal(this, 0.8));
        this.goalSelector.addGoal(3, new HerderWorkGoal(this, 0.8));

        for (com.bannerbound.core.api.job.CitizenJobRegistry.JobDef def
                : com.bannerbound.core.api.job.CitizenJobRegistry.all()) {
            if (def.goalFactory() == null) continue;
            net.minecraft.world.entity.ai.goal.Goal g = def.goalFactory().apply(this, 0.8);
            if (g != null) this.goalSelector.addGoal(3, g);
        }

        this.goalSelector.addGoal(3, new ConversationGoal(this, 0.8));

        this.goalSelector.addGoal(3, new CitizenAdoptPetGoal(this, 0.8));
        this.goalSelector.addGoal(4, new SettlementPatrolGoal(this, 0.8));

        this.goalSelector.addGoal(4, new AnarchyWorkGoal(this, 0.8));

        for (com.bannerbound.core.api.entity.CitizenGoalRegistry.Entry e
                : com.bannerbound.core.api.entity.CitizenGoalRegistry.all()) {
            net.minecraft.world.entity.ai.goal.Goal g = e.factory().apply(this, 0.8);
            if (g != null) this.goalSelector.addGoal(e.priority(), g);
        }
        this.goalSelector.addGoal(5, new LookAtPlayerGoal(this, Player.class, 6.0f));
        this.goalSelector.addGoal(6, new RandomLookAroundGoal(this));
    }

    public void initializeCitizen(UUID settlementId, String name, CitizenGender gender,
                                   Era era, ChatFormatting nameColor) {
        this.settlementId = settlementId;
        this.entityData.set(DATA_GENDER, gender.ordinal());
        this.entityData.set(DATA_ERA, era.ordinal());
        this.entityData.set(DATA_TEXTURE_VARIANT, this.random.nextInt(256));
        rollTraits();

        bakeNameInLanguage(name);
        this.nameColor = nameColor;
        refreshDisplayName();
        this.setCustomNameVisible(true);
    }

    @Override
    public void tick() {

        if (level().isClientSide) {
            super.tick();
            return;
        }
        long start = System.nanoTime();
        super.tick();
        com.bannerbound.core.sim.CitizenAiProfiler.add(System.nanoTime() - start);
    }

    public void markSimulated() { this.simulated = true; }

    public boolean isSimulated() { return simulated; }

    private boolean isHostileBarbarianToMe(net.minecraft.world.entity.LivingEntity target) {

        if (!(target instanceof BarbarianEntity b) || b.isMessenger() || b.campId() == null
                || !(level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return false;
        }
        com.bannerbound.core.barbarian.BarbarianCamp camp =
            com.bannerbound.core.barbarian.BarbarianData.get(sl).getById(b.campId());
        return camp != null && BarbarianEntity.campHostileTo(camp, this.getSettlement());
    }

    private boolean isHostileMercenaryToMe(net.minecraft.world.entity.LivingEntity target) {
        if (!(target instanceof MercenaryEntity m) || m.cityStateId() == null || getSettlementId() == null
                || !(level() instanceof net.minecraft.server.level.ServerLevel sl)) {
            return false;
        }
        com.bannerbound.core.citystate.CityState cs =
            com.bannerbound.core.citystate.CityStateData.get(sl).getById(m.cityStateId());
        return cs != null && cs.isActiveEnemy(getSettlementId());
    }

    public boolean isHostileToMe(net.minecraft.world.entity.LivingEntity target) {
        return isHostileToCitizens(target) || isHostileBarbarianToMe(target) || isHostileMercenaryToMe(target);
    }

    public boolean usesAmbientBrain() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel)) return false;
        com.bannerbound.core.api.settlement.Settlement s = getSettlement();
        return s != null
            && s.stage().ordinal() >= com.bannerbound.core.api.settlement.SettlementStage.VILLAGE.ordinal();
    }

    public boolean isGuard() {
        return GuardWorkGoal.JOB_TYPE_ID.equals(jobTypeId);
    }

    public boolean isSettlementRallying() {
        return level() instanceof net.minecraft.server.level.ServerLevel sl && settlementId != null
            && com.bannerbound.core.api.settlement.SettlementData.get(sl).isRallying(settlementId);
    }

    public boolean engagesHostiles() {
        if (isChild()) return false;
        return isGuard() || isSettlementRallying();
    }

    @Override
    public boolean shouldBeSaved() {

        return !simulated && super.shouldBeSaved();
    }

    private void refreshAiActivation() {
        if (this.tickCount < this.aiActivationRecheckAt) return;
        this.aiActivationRecheckAt = this.tickCount + AI_ACTIVATION_RECHECK_TICKS;

        this.aiActive = this.outpostSite != null
            || this.level().getNearestPlayer(this, AI_ACTIVE_RANGE) != null;
    }

    public boolean isAiActive() { return this.aiActive; }

    public boolean isThinkTick() {
        return (this.tickCount + Math.floorMod(this.getId(), THINK_PERIOD)) % THINK_PERIOD == 0;
    }

    private void rollTraits() {
        int mask = 0;
        for (CitizenTrait trait : CitizenTrait.values()) {
            if (this.random.nextFloat() < trait.chance()) {
                mask |= trait.bit();
            }
        }
        this.entityData.set(DATA_TRAITS, mask);
    }

    public void refreshNameColor() {
        Settlement s = getSettlement();
        this.nameColor = s != null ? s.identityFormatting() : ChatFormatting.WHITE;
        refreshDisplayName();
    }

    public void refreshDisplayName() {

        if (citizenName == null) {
            Settlement s = getSettlement();
            if (s != null) {
                for (com.bannerbound.core.api.settlement.Citizen c : s.citizens()) {
                    if (c.entityId().equals(this.getUUID())) {
                        this.citizenName = c.name();
                        break;
                    }
                }
            }
        }
        if (citizenName == null) return;

        this.addTag(citizenName);
        Style iconStyle = Style.EMPTY.withFont(ICONS_FONT).withColor(TextColor.fromRgb(0xFFFFFF));
        String genderGlyph = getGender() == CitizenGender.FEMALE ? FEMALE_GLYPH : MALE_GLYPH;
        net.minecraft.network.chat.MutableComponent full = Component.empty();
        if (isPregnant()) {
            full.append(Component.literal(PREGNANT_GLYPH).withStyle(iconStyle));
        }
        String visibleName = displayCitizenName();
        full.append(Component.literal(genderGlyph).withStyle(iconStyle))
            .append(Component.literal(" "))
            .append(Component.literal(visibleName).withStyle(nameColor));

        String jobGlyph = com.bannerbound.core.social.JobIcons.jobGlyph(getSettlement(), jobTypeId);
        if (!jobGlyph.isEmpty()) {
            full.append(Component.literal(" "))
                .append(Component.literal(jobGlyph).withStyle(iconStyle));
        }
        this.setCustomName(full);
    }

    public boolean isPregnant() { return this.entityData.get(DATA_PREGNANT); }
    public boolean isChild()    { return this.entityData.get(DATA_IS_CHILD); }

    public String getCitizenName() { return citizenName; }

    public String displayCitizenName() {
        if (citizenName == null) return "";
        Settlement settlement = getSettlement();

        return com.bannerbound.core.language.CustomLanguageLabel.compose(
            settlement, citizenName, earnedSurnameConcept, earnedSurnameJob, getUUID().toString());
    }

    private void bakeNameInLanguage(String base) {
        this.citizenName = com.bannerbound.core.language.CustomLanguageLabel.styleGiven(
            getSettlement(), base, getUUID().toString());
        this.nameBaked = true;
    }

    public long getPregnantSinceTick() { return this.entityData.get(DATA_PREGNANT_SINCE_TICK); }
    public UUID getPregnancyFatherId() { return pregnancyFatherId; }
    public long getBornAtTick() { return bornAtTick; }
    public void setBornAtTick(long t) { this.bornAtTick = t; }

    public UUID getMotherId() { return motherId; }
    public void setMotherId(UUID id) { this.motherId = id; }

    public void setPregnant(boolean pregnant, long startTick, UUID fatherId) {
        this.entityData.set(DATA_PREGNANT, pregnant);
        this.entityData.set(DATA_PREGNANT_SINCE_TICK, pregnant ? startTick : -1L);
        this.pregnancyFatherId = pregnant ? fatherId : null;
        refreshDisplayName();
    }

    public void setIsChild(boolean child) {
        this.entityData.set(DATA_IS_CHILD, child);
        refreshDisplayName();
    }

    public UUID getSettlementId() { return settlementId; }

    public static final int BRAWL_ONGOING_WINDOW_TICKS = 200;

    public static final int BRAWL_RETALIATION_DELAY_TICKS = 5;

    @org.jetbrains.annotations.Nullable
    public UUID getLastBrawlOpponentId() { return lastBrawlOpponentId; }
    public long getLastBrawlTick() { return lastBrawlTick; }

    public void noteBrawlExchange(UUID opponent, long tick) {
        this.lastBrawlOpponentId = opponent;
        this.lastBrawlTick = tick;
    }

    public void schedulePendingRetaliation(UUID target, long tick) {
        this.pendingRetaliationTargetId = target;
        this.pendingRetaliationTick = tick;
    }
    @org.jetbrains.annotations.Nullable
    public UUID getPendingRetaliationTargetId() { return pendingRetaliationTargetId; }
    public long getPendingRetaliationTick() { return pendingRetaliationTick; }
    public void clearPendingRetaliation() {
        this.pendingRetaliationTargetId = null;
        this.pendingRetaliationTick = 0L;
    }

    public static final int GUARD_RETALIATION_WINDOW_TICKS = 400;

    @org.jetbrains.annotations.Nullable private UUID guardRetaliationId;
    private long guardRetaliationTick;

    public void noteGuardRetaliation(UUID attackerId, long tick) {
        this.guardRetaliationId = attackerId;
        this.guardRetaliationTick = tick;
    }

    public boolean isGuardRetaliationTarget(net.minecraft.world.entity.LivingEntity e) {
        if (guardRetaliationId == null || e == null || !e.getUUID().equals(guardRetaliationId)) {
            return false;
        }
        long now = level().getGameTime();
        if (now - guardRetaliationTick > GUARD_RETALIATION_WINDOW_TICKS) {
            guardRetaliationId = null;
            return false;
        }
        return true;
    }

    @org.jetbrains.annotations.Nullable
    public net.minecraft.world.entity.LivingEntity guardRetaliationTarget(
            net.minecraft.server.level.ServerLevel sl) {
        UUID id = guardRetaliationId;
        if (id == null) return null;
        if (sl.getGameTime() - guardRetaliationTick > GUARD_RETALIATION_WINDOW_TICKS) {
            guardRetaliationId = null;
            return null;
        }
        if (sl.getEntity(id) instanceof net.minecraft.world.entity.LivingEntity le
                && le.isAlive()) {
            return le;
        }
        return null;
    }

    public boolean performBrawlSwing(net.minecraft.world.entity.LivingEntity target) {
        if (target == null || !target.isAlive()) return false;

        if (this.distanceToSqr(target) > 49.0) return false;
        this.getLookControl().setLookAt(target, 30.0f, 30.0f);
        this.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        target.hurt(this.damageSources().mobAttack(this), 1.0f);
        if (target instanceof CitizenEntity ct) {
            com.bannerbound.core.social.SocialEvents.applyMutual(this, ct,
                AnarchyWorkGoal.ANARCHY_RELATION_DELTA_PER_HIT);
        }
        if (this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.ANGRY_VILLAGER,
                target.getX(),
                target.getY() + target.getBbHeight() + 0.3,
                target.getZ(),
                3, 0.2, 0.1, 0.2, 0.0);
        }
        return true;
    }

    public int getStamina() { return this.entityData.get(DATA_STAMINA); }
    public int getStaminaMax() { return MAX_STAMINA; }
    private void setStaminaInternal(int value) {
        this.entityData.set(DATA_STAMINA, Math.max(0, Math.min(MAX_STAMINA, value)));
    }

    public void setWorking(boolean working) { this.working = working; }
    public boolean isWorking() { return working; }

    public boolean isStaminaExhausted() { return resting; }

    public void consumeStamina(int amount) {
        int next = Math.max(0, getStamina() - amount);
        setStaminaInternal(next);
        staminaRechargeTimer = 0;
        if (next == 0) {
            resting = true;
        }
    }

    public static final double CAPTURE_RADIUS_SQ = 16.0 * 16.0;

    public static final long PREGNANCY_DURATION_TICKS = 24_000L;

    public static final long ADULTHOOD_TICKS = 72_000L;

    public static final int CHILD_THOUGHT_INTERVAL = 1_200;

    public static final double CHILD_THOUGHT_CHANCE = 0.20;

    public void beginCaptureWindow(BlockPos center, int ticks) {
        this.captureCenter = center;
        this.captureWindowEndTick = this.tickCount + ticks;
    }

    public boolean isCaptureWindowActive() {
        return captureCenter != null && this.tickCount < captureWindowEndTick;
    }

    public BlockPos getCaptureCenter() {
        return captureCenter;
    }

    public String getJobType() { return jobTypeId; }

    public boolean isClientJob(String typeId) {
        return this.entityData.get(DATA_JOB)
            == com.bannerbound.core.social.WorkstationIcons.ordinalOf(typeId);
    }

    public void setClientGlow(boolean on) {
        if (level().isClientSide()) {
            setSharedFlag(6, on);   // bit 6 = vanilla GLOWING shared flag; client-only, never synced to the server
        }
    }

    public boolean isEmployed() { return jobTypeId != null; }

    public boolean isJobPinned() { return jobPinned; }
    public void setJobPinned(boolean pinned) { this.jobPinned = pinned; }

    public java.util.UUID getAssignedWorkshopId() { return assignedWorkshopId; }
    public void setAssignedWorkshopId(java.util.UUID id) { this.assignedWorkshopId = id; }

    public float getJobXp(String key) {
        Float v = jobXp.get(key);
        return v == null ? 0.0F : v;
    }

    public void addJobXp(String key, float amount) {
        grantJobXp(key, amount, key);
    }

    public void grantJobXp(String key, float amount, String surnameConcept) {
        if (key == null || key.isBlank() || amount <= 0.0F) return;

        amount *= happinessPerformanceMultiplier();
        float before = getJobXp(key);
        jobXp.merge(key, amount, Float::sum);
        float after = getJobXp(key);
        String oldTier = com.bannerbound.core.api.quality.QualityMath.skillTierKey((int) before);
        String newTier = com.bannerbound.core.api.quality.QualityMath.skillTierKey((int) after);
        if (!oldTier.equals(newTier)
                && level() instanceof net.minecraft.server.level.ServerLevel sl
                && getThoughts() != null) {
            getThoughts().add(com.bannerbound.core.social.ThoughtKind.PROMOTED, null,
                sl.getGameTime(), sl.getRandom());
            recomputeHappiness();
        }
        if (earnedSurnameConcept == null
                && before < SURNAME_XP_THRESHOLD
                && after >= SURNAME_XP_THRESHOLD) {
            earnedSurnameConcept = (surnameConcept == null || surnameConcept.isBlank()) ? key : surnameConcept;
            earnedSurnameJob = key;
            earnedSurnameTick = level() == null ? -1L : level().getGameTime();
            refreshDisplayName();
        }
    }

    public boolean hasActiveJobRefusal() {
        long now = level().getGameTime();
        for (com.bannerbound.core.social.Thought t : thoughts.entries()) {
            if (t.kind() == com.bannerbound.core.social.ThoughtKind.NO_WORK_AS_JOB && !t.isExpired(now)) {
                return true;
            }
        }
        return false;
    }

    public void setJobType(String typeId) {
        String oldJob = this.jobTypeId;
        BlockPos shoreSnap = this.dropOffPos;   
        this.jobTypeId = typeId;

        if (!CrafterWorkGoal.isWorkshopJob(typeId)) {
            this.assignedWorkshopId = null;
        }

        if (!StockerWorkGoal.JOB_TYPE_ID.equals(typeId)) {
            this.tradingCourier = false;
        }
        this.entityData.set(DATA_JOB,
            typeId == null ? 0 : com.bannerbound.core.social.WorkstationIcons.ordinalOf(typeId));
        if (typeId == null) {
            this.dropOffPos = null;
            this.seedSourcePos = null;
            this.jobPinned = false;   
        } else if (oldJob != null && !oldJob.equals(typeId) && (hasJobTool() || hasJobPickaxe())) {

            returnJobToolsForReskill();
        }

        if (!java.util.Objects.equals(oldJob, typeId)
                && this.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat) {
            this.stopRiding();
            if (shoreSnap != null) {
                this.moveTo(shoreSnap.getX() + 0.5, shoreSnap.getY() + 1.0, shoreSnap.getZ() + 0.5,
                    this.getYRot(), 0.0F);
                tagDeliberateTeleport(this);
                this.getNavigation().stop();
            }
        }

        refreshDisplayName();
    }

    public net.minecraft.world.item.ItemStack getJobTool() { return jobTool; }
    public boolean hasJobTool() { return !jobTool.isEmpty(); }
    public void setJobTool(net.minecraft.world.item.ItemStack stack) {
        this.jobTool = stack == null ? net.minecraft.world.item.ItemStack.EMPTY : stack;
    }

    public net.minecraft.world.item.ItemStack getJobPickaxe() { return jobPickaxe; }
    public boolean hasJobPickaxe() { return !jobPickaxe.isEmpty(); }
    public void setJobPickaxe(net.minecraft.world.item.ItemStack stack) {
        this.jobPickaxe = stack == null ? net.minecraft.world.item.ItemStack.EMPTY : stack;
    }

    public net.minecraft.world.level.block.Block getPreferredLog() { return preferredLog; }
    public void setPreferredLog(net.minecraft.world.level.block.Block block) {
        if (block != null) this.preferredLog = block;
    }

    public int getForageTargetBits() { return forageTargetBits; }
    public void setForageTargetBits(int bits) {
        this.forageTargetBits = bits & com.bannerbound.core.api.forager.ForageCategory.ALL_BITS;
    }

    public boolean foresterKeepsExtras() { return foresterKeepExtras; }
    public void setForesterKeepExtras(boolean keep) { this.foresterKeepExtras = keep; }

    public CitizenWorkStatus getCurrentWorkStatus() { return currentWorkStatus; }
    public void setCurrentWorkStatus(CitizenWorkStatus status) {
        this.currentWorkStatus = status == null ? CitizenWorkStatus.IDLE : status;
    }

    public void setForageTarget(int ordinal, boolean enabled) {
        int bit = 1 << ordinal;
        if ((bit & com.bannerbound.core.api.forager.ForageCategory.ALL_BITS) == 0) return;
        this.forageTargetBits = enabled ? (forageTargetBits | bit) : (forageTargetBits & ~bit);
    }

    public boolean isHunterPreyEnabled(net.minecraft.world.entity.EntityType<?> type) {
        return hunterPreyOff.isEmpty()
            || !hunterPreyOff.contains(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(type).toString());
    }

    public void setHunterPreyEnabled(String entityTypeId, boolean enabled) {
        if (entityTypeId == null || entityTypeId.isEmpty()) return;
        if (enabled) hunterPreyOff.remove(entityTypeId);
        else hunterPreyOff.add(entityTypeId);
    }

    public java.util.List<String> getHunterPreyOffIds() {
        return java.util.List.copyOf(hunterPreyOff);
    }

    public BlockPos getDropOff() { return dropOffPos; }
    public void setDropOff(BlockPos pos) {
        BlockPos old = this.dropOffPos;

        if (old != null && (pos == null || !pos.equals(old)) && hasHaul()
                && isAnarchyTownHallSink(old) && level() instanceof ServerLevel sl) {
            dumpHaulAt(sl, old);
        }
        this.dropOffPos = pos == null ? null : pos.immutable();
    }

    public BlockPos getSeedSource() { return seedSourcePos; }
    public void setSeedSource(BlockPos pos) { this.seedSourcePos = pos == null ? null : pos.immutable(); }

    public net.minecraft.world.SimpleContainer getSeedCache() { return seedCache; }

    public boolean isJobReady(String typeId) {
        return typeId.equals(jobTypeId) && hasJobTool() && hasDropDepot();
    }

    public boolean hasDropDepot() {
        return dropOffPos != null || (!isAnarchy() && settlementHasPooledStorage(true));
    }

    public boolean hasSeedDepot() {
        return seedSourcePos != null || (!isAnarchy() && settlementHasPooledStorage(false));
    }

    private boolean settlementHasPooledStorage(boolean deposit) {
        Settlement s = getSettlement();
        if (s == null || !(level() instanceof ServerLevel sl)) return false;
        return deposit ? SettlementStorage.hasDeposit(sl, s) : SettlementStorage.hasTake(sl, s);
    }

    public boolean isForesterReady() {
        if (isAnarchy()) return ForesterWorkGoal.JOB_TYPE_ID.equals(jobTypeId) && hasDropDepot();
        return isJobReady(ForesterWorkGoal.JOB_TYPE_ID);
    }

    public boolean isFisherReady() {
        if (isAnarchy()) return FisherWorkGoal.JOB_TYPE_ID.equals(jobTypeId) && hasDropDepot();
        return isJobReady(FisherWorkGoal.JOB_TYPE_ID);
    }

    public boolean isForagerReady() {
        return ForagerWorkGoal.JOB_TYPE_ID.equals(jobTypeId) && hasDropDepot();
    }

    public boolean isGatherJobReady(String typeId) {
        if (typeId == null || !typeId.equals(jobTypeId) || !hasDropDepot()) return false;
        if (isAnarchy()) return true;
        com.bannerbound.core.api.job.CitizenJobRegistry.JobDef def =
            com.bannerbound.core.api.job.CitizenJobRegistry.byId(typeId);
        boolean toolRequired = def == null || def.toolRequired();
        return !toolRequired || hasJobTool();
    }

    public boolean validateJobStorage() {
        boolean cleared = false;
        if (dropOffPos != null && level().isLoaded(dropOffPos)
                && !DropOffContainers.isDropOffBlock(level(), dropOffPos)
                && !isAnarchyTownHallSink(dropOffPos)) {
            dropOffPos = null;
            cleared = true;
        }
        if (seedSourcePos != null && level().isLoaded(seedSourcePos)
                && !DropOffContainers.isDropOffBlock(level(), seedSourcePos)) {
            seedSourcePos = null;
            cleared = true;
        }
        return cleared;
    }

    public boolean isFarmerReady() {
        return isJobReady(FarmerWorkGoal.JOB_TYPE_ID) && hasSeedDepot();
    }

    public static final double ANARCHY_TOOLFREE_WORK_FACTOR = 2.0;

    public static final int ANARCHY_HAUL_CAPACITY = 64;

    public boolean isAnarchy() {
        Settlement s = getSettlement();
        return s != null && s.governmentType() == Settlement.Government.NONE;
    }

    public double anarchyWorkSpeedFactor() {
        return (isAnarchy() && !hasJobTool()) ? ANARCHY_TOOLFREE_WORK_FACTOR : 1.0;
    }

    public void autoFindDropOff(Settlement s) {
        if (jobTypeId == null || !AnarchyJobs.isGathererJob(jobTypeId)) return;

        if (isAnarchy() && dropOffPos == null && s.townHallPos() != null) {
            setDropOff(s.townHallPos());
        }
    }

    private boolean isAnarchyTownHallSink(BlockPos pos) {
        if (pos == null || !isAnarchy()) return false;
        Settlement s = getSettlement();
        return s != null && pos.equals(s.townHallPos());
    }

    public boolean isAnarchyHaulDropOff() {
        return isAnarchyTownHallSink(getDropOff());
    }

    public net.minecraft.world.SimpleContainer getAnarchyHaul() { return anarchyHaul; }

    public boolean hasHaul() { return !anarchyHaul.isEmpty(); }

    public int haulItemCount() {
        int n = 0;
        for (int i = 0; i < anarchyHaul.getContainerSize(); i++) n += anarchyHaul.getItem(i).getCount();
        return n;
    }

    public boolean isHaulFull() { return haulItemCount() >= ANARCHY_HAUL_CAPACITY; }

    private java.util.List<net.minecraft.world.item.ItemStack> drainHaulMerged() {
        java.util.List<net.minecraft.world.item.ItemStack> out = new java.util.ArrayList<>();
        for (int i = 0; i < anarchyHaul.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack s = anarchyHaul.getItem(i);
            if (s.isEmpty()) continue;
            for (net.minecraft.world.item.ItemStack o : out) {
                if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(o, s)
                        && o.getCount() < o.getMaxStackSize()) {
                    int move = Math.min(s.getCount(), o.getMaxStackSize() - o.getCount());
                    o.grow(move);
                    s.shrink(move);
                    if (s.isEmpty()) break;
                }
            }
            if (!s.isEmpty()) out.add(s.copy());
            anarchyHaul.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
        }
        return out;
    }

    public void dumpHaulAt(ServerLevel sl, BlockPos pos) {
        for (net.minecraft.world.item.ItemStack s : drainHaulMerged()) {
            net.minecraft.world.entity.item.ItemEntity e = new net.minecraft.world.entity.item.ItemEntity(
                sl, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, s);
            e.setDefaultPickUpDelay();
            sl.addFreshEntity(e);
        }
    }

    private void dumpHaulAtFeet() {
        for (net.minecraft.world.item.ItemStack s : drainHaulMerged()) {
            this.spawnAtLocation(s);
        }
    }

    public void returnJobToolAndClear() {
        if (this.level() instanceof ServerLevel sl) {
            returnOneTool(sl, jobTool);
            returnOneTool(sl, jobPickaxe);
            dumpSeedCache(sl);
            dumpHaulAtFeet();   
        }
        this.jobTool = net.minecraft.world.item.ItemStack.EMPTY;
        this.jobPickaxe = net.minecraft.world.item.ItemStack.EMPTY;
        this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            net.minecraft.world.item.ItemStack.EMPTY);
    }

    private void returnJobToolsForReskill() {
        if (this.level() instanceof ServerLevel sl) {
            returnOneTool(sl, jobTool);
            returnOneTool(sl, jobPickaxe);
        }
        this.jobTool = net.minecraft.world.item.ItemStack.EMPTY;
        this.jobPickaxe = net.minecraft.world.item.ItemStack.EMPTY;
        this.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            net.minecraft.world.item.ItemStack.EMPTY);
    }

    private void dumpSeedCache(ServerLevel sl) {
        net.minecraft.world.Container depot = DropOffContainers.resolveOrPreferred(this, dropOffPos);
        for (int i = 0; i < seedCache.getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack s = seedCache.getItem(i);
            if (s.isEmpty()) continue;
            net.minecraft.world.item.ItemStack leftover = depot != null ? DropOffContainers.insert(depot, s) : s;
            if (!leftover.isEmpty()) this.spawnAtLocation(leftover);
            seedCache.setItem(i, net.minecraft.world.item.ItemStack.EMPTY);
        }
    }

    private void returnOneTool(ServerLevel sl, net.minecraft.world.item.ItemStack tool) {
        if (tool.isEmpty()) return;
        net.minecraft.world.Container c = DropOffContainers.resolveOrPreferred(this, dropOffPos);
        boolean stored = c != null && DropOffContainers.insert(c, tool).isEmpty();
        if (!stored) this.spawnAtLocation(tool);
    }

    @Override
    public void die(net.minecraft.world.damagesource.DamageSource cause) {

        returnJobToolAndClear();
        super.die(cause);
    }

    public Settlement getSettlement() {
        if (settlementId == null) return null;
        if (!(this.level() instanceof ServerLevel serverLevel)) return null;
        SettlementData data = SettlementData.get(serverLevel);
        return data.getById(settlementId);
    }

    public boolean isInOwnedChunk(BlockPos pos) {
        Settlement s = getSettlement();
        if (s == null) return true;
        long packed = new ChunkPos(pos).toLong();
        return s.claimedChunks().contains(packed);
    }

    @Override
    public net.minecraft.world.InteractionResult mobInteract(net.minecraft.world.entity.player.Player player,
                                                              net.minecraft.world.InteractionHand hand) {
        if (player.level().isClientSide) {
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return net.minecraft.world.InteractionResult.PASS;
        }

        net.minecraft.world.item.ItemStack rod =
            serverPlayer.getMainHandItem().is(com.bannerbound.core.BannerboundCore.FOREMANS_ROD.get())
                ? serverPlayer.getMainHandItem()
                : serverPlayer.getOffhandItem().is(com.bannerbound.core.BannerboundCore.FOREMANS_ROD.get())
                    ? serverPlayer.getOffhandItem()
                    : net.minecraft.world.item.ItemStack.EMPTY;

        String rodType = com.bannerbound.core.network.ServerPayloadHandler.rodTypeForJob(getJobType());
        if (serverPlayer.isShiftKeyDown() && !rod.isEmpty() && rodType != null) {
            rod.set(com.bannerbound.core.BannerboundCore.FOREMAN_WORKSTATION_TYPE.get(), rodType);
            rod.set(com.bannerbound.core.BannerboundCore.FOREMAN_TARGET_CITIZEN.get(),
                getUUID().toString());
            Component dname = getCustomName() != null ? getCustomName() : Component.literal("Worker");
            rod.set(com.bannerbound.core.BannerboundCore.FOREMAN_TARGET_NAME.get(), dname.getString());
            Component wname = com.bannerbound.core.social.WorkstationNames.dynamic(getSettlement(), rodType);
            serverPlayer.displayClientMessage(Component.translatable(
                "bannerbound.foremans_rod.one", wname, dname).withStyle(ChatFormatting.AQUA), true);
            return net.minecraft.world.InteractionResult.CONSUME;
        }
        Settlement settlement = getSettlement();
        boolean canModify = settlement != null && settlement.members().contains(serverPlayer.getUUID());

        Component displayName = this.getCustomName() != null
            ? this.getCustomName()
            : Component.literal("Citizen");

        java.util.List<com.bannerbound.core.network.RelationshipEntry> rels = new java.util.ArrayList<>();
        net.minecraft.server.level.ServerLevel sl = (net.minecraft.server.level.ServerLevel) serverPlayer.level();
        for (java.util.Map.Entry<UUID, com.bannerbound.core.social.Relationship> e : relationships.entries().entrySet()) {
            net.minecraft.world.entity.Entity ent = sl.getEntity(e.getKey());
            Component otherName;
            if (ent instanceof CitizenEntity oc && oc.getCustomName() != null) {
                otherName = oc.getCustomName();
            } else {
                otherName = Component.literal("Unknown Citizen");
            }
            rels.add(new com.bannerbound.core.network.RelationshipEntry(
                otherName, e.getValue().score(), e.getValue().isFamily()));
        }

        java.util.List<com.bannerbound.core.network.ThoughtEntry> thoughtRows = new java.util.ArrayList<>();
        for (com.bannerbound.core.social.Thought t : thoughts.entries()) {
            Component partnerName = null;
            if (t.otherUuid() != null) {
                net.minecraft.world.entity.Entity ent = sl.getEntity(t.otherUuid());
                if (ent instanceof CitizenEntity oc && oc.getCustomName() != null) {
                    partnerName = oc.getCustomName();
                } else if (t.savedPartnerName() != null) {

                    partnerName = Component.literal(t.savedPartnerName());
                } else {
                    partnerName = Component.literal("Someone");
                }
            }
            Component label = partnerName != null
                ? Component.translatable(t.kind().labelKey(), partnerName)
                : Component.translatable(t.kind().labelKey());

            thoughtRows.add(new com.bannerbound.core.network.ThoughtEntry(
                label, t.effectiveModifier(sl.getGameTime()), t.expireGameTime(), t.totalDurationTicks(),
                t.kind().category().ordinal()));
        }

        int viewerResentment = getResentment(serverPlayer.getUUID());
        com.bannerbound.core.network.OpenCitizenScreenPayload payload =
            new com.bannerbound.core.network.OpenCitizenScreenPayload(
                this.getId(),
                displayName,
                this.getHealth(),
                this.getMaxHealth(),
                getHappiness(),
                getHappinessMax(),
                canModify,
                getStamina(),
                getStaminaMax(),
                rels,
                thoughtRows,
                compliance,
                viewerResentment
            );
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(serverPlayer, payload);

        com.bannerbound.core.network.ServerPayloadHandler.sendJobState(serverPlayer, this);
        return net.minecraft.world.InteractionResult.CONSUME;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (settlementId != null) {
            tag.putUUID(TAG_SETTLEMENT_ID, settlementId);
        }
        tag.putBoolean("TradingCourier", tradingCourier);
        if (tradeJourneyId != null) tag.putUUID("TradeJourneyId", tradeJourneyId);
        tag.putInt(TAG_STAMINA, getStamina());
        tag.putInt(TAG_STAMINA_TIMER, staminaRechargeTimer);
        tag.putBoolean(TAG_RESTING, resting);
        tag.putInt(TAG_GENDER, this.entityData.get(DATA_GENDER));
        tag.putInt(TAG_TRAITS, this.entityData.get(DATA_TRAITS));
        tag.putInt(TAG_TEXTURE_VARIANT, this.entityData.get(DATA_TEXTURE_VARIANT));
        tag.putInt(TAG_ERA, this.entityData.get(DATA_ERA));

        if (!relationships.isEmpty()) {
            tag.put(TAG_RELATIONS, relationships.save());
        }

        if (!thoughts.isEmpty()) {
            tag.put(TAG_THOUGHTS, thoughts.save());
        }
        tag.putIntArray(TAG_CHUNK_SAMPLES, chunkSamples.clone());
        tag.putLong(TAG_LAST_CHUNK_EVAL_DAY, lastChunkEvalDay);

        if (citizenName != null) tag.putString(TAG_CITIZEN_NAME, citizenName);
        tag.putBoolean(TAG_NAME_BAKED, nameBaked);
        if (isPregnant()) {
            tag.putBoolean(TAG_PREGNANT, true);
            tag.putLong(TAG_PREGNANT_SINCE_TICK, getPregnantSinceTick());
            if (pregnancyFatherId != null) tag.putUUID(TAG_PREGNANCY_FATHER, pregnancyFatherId);
        }
        if (isChild()) tag.putBoolean(TAG_IS_CHILD, true);
        if (bornAtTick > 0L) tag.putLong(TAG_BORN_AT_TICK, bornAtTick);
        if (motherId != null) tag.putUUID(TAG_MOTHER, motherId);
        if (earnedSurnameConcept != null && !earnedSurnameConcept.isBlank()) {
            tag.putString(TAG_EARNED_SURNAME_CONCEPT, earnedSurnameConcept);
            if (earnedSurnameJob != null && !earnedSurnameJob.isBlank()) {
                tag.putString(TAG_EARNED_SURNAME_JOB, earnedSurnameJob);
            }
            if (earnedSurnameTick >= 0L) {
                tag.putLong(TAG_EARNED_SURNAME_TICK, earnedSurnameTick);
            }
        }

        if (compliance != DEFAULT_COMPLIANCE) {
            tag.putInt(TAG_COMPLIANCE, compliance);
        }

        if (!resentmentByPlayer.isEmpty()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            for (java.util.Map.Entry<UUID, Integer> e : resentmentByPlayer.entrySet()) {
                if (e.getValue() == null || e.getValue() <= 0) continue;
                CompoundTag entry = new CompoundTag();
                entry.putUUID(TAG_RESENTMENT_UUID, e.getKey());
                entry.putInt(TAG_RESENTMENT_VALUE, e.getValue());
                list.add(entry);
            }
            if (!list.isEmpty()) tag.put(TAG_RESENTMENT_MAP, list);
        }

        if (jobTypeId != null) tag.putString(TAG_JOB_TYPE, jobTypeId);
        if (jobPinned) tag.putBoolean(TAG_JOB_PINNED, true);
        if (assignedWorkshopId != null) tag.putUUID(TAG_WORKSHOP_ID, assignedWorkshopId);
        if (!jobXp.isEmpty()) {
            net.minecraft.nbt.CompoundTag xpTag = new net.minecraft.nbt.CompoundTag();
            for (java.util.Map.Entry<String, Float> e : jobXp.entrySet()) {
                xpTag.putFloat(e.getKey(), e.getValue());
            }
            tag.put("JobXpMap", xpTag);
        }
        if (!jobTool.isEmpty()) tag.put(TAG_JOB_TOOL, jobTool.save(this.registryAccess()));
        if (!jobPickaxe.isEmpty()) tag.put(TAG_JOB_PICKAXE, jobPickaxe.save(this.registryAccess()));
        if (preferredLog != net.minecraft.world.level.block.Blocks.OAK_LOG) {
            net.minecraft.resources.ResourceLocation logId =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(preferredLog);
            if (logId != null) tag.putString(TAG_PREFERRED_LOG, logId.toString());
        }
        if (dropOffPos != null) tag.putLong(TAG_DROP_OFF, dropOffPos.asLong());
        if (seedSourcePos != null) tag.putLong(TAG_SEED_SOURCE, seedSourcePos.asLong());
        if (forageTargetBits != com.bannerbound.core.api.forager.ForageCategory.ALL_BITS) {
            tag.putInt(TAG_FORAGE_TARGETS, forageTargetBits);
        }
        if (!hunterPreyOff.isEmpty()) {   
            net.minecraft.nbt.ListTag off = new net.minecraft.nbt.ListTag();
            for (String id : hunterPreyOff) off.add(net.minecraft.nbt.StringTag.valueOf(id));
            tag.put(TAG_HUNTER_PREY_OFF, off);
        }
        if (!foresterKeepExtras) {   
            tag.putBoolean(TAG_FORESTER_KEEP_EXTRAS, false);
        }
        if (!seedCache.isEmpty()) {
            tag.put(TAG_SEED_CACHE, seedCache.createTag(this.registryAccess()));
        }
        if (!anarchyHaul.isEmpty()) {
            tag.put(TAG_ANARCHY_HAUL, anarchyHaul.createTag(this.registryAccess()));
        }

        if (outpostSite != null) {
            tag.putLong("OutpostSite", outpostSite.asLong());
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(TAG_SETTLEMENT_ID)) {
            this.settlementId = tag.getUUID(TAG_SETTLEMENT_ID);
        }
        this.tradingCourier = tag.getBoolean("TradingCourier");
        this.tradeJourneyId = tag.hasUUID("TradeJourneyId") ? tag.getUUID("TradeJourneyId") : null;
        this.outpostSite = tag.contains("OutpostSite") ? BlockPos.of(tag.getLong("OutpostSite")) : null;
        int loadedStamina = tag.contains(TAG_STAMINA)
            ? Math.max(0, Math.min(MAX_STAMINA, tag.getInt(TAG_STAMINA)))
            : MAX_STAMINA;
        setStaminaInternal(loadedStamina);
        this.staminaRechargeTimer = tag.contains(TAG_STAMINA_TIMER)
            ? Math.max(0, tag.getInt(TAG_STAMINA_TIMER))
            : 0;

        this.resting = tag.contains(TAG_RESTING)
            ? tag.getBoolean(TAG_RESTING)
            : loadedStamina == 0;

        int uuidHash = this.getUUID().hashCode();
        this.entityData.set(DATA_GENDER, tag.contains(TAG_GENDER)
            ? tag.getInt(TAG_GENDER)
            : (uuidHash & 1));
        this.entityData.set(DATA_TRAITS, tag.contains(TAG_TRAITS) ? tag.getInt(TAG_TRAITS) : 0);
        this.entityData.set(DATA_TEXTURE_VARIANT, tag.contains(TAG_TEXTURE_VARIANT)
            ? tag.getInt(TAG_TEXTURE_VARIANT)
            : (uuidHash >>> 1) & 0xFF);
        this.entityData.set(DATA_ERA, tag.contains(TAG_ERA)
            ? tag.getInt(TAG_ERA)
            : Era.ANCIENT.ordinal());

        relationships.load(tag.contains(TAG_RELATIONS)
            ? tag.getList(TAG_RELATIONS, net.minecraft.nbt.Tag.TAG_COMPOUND)
            : null);

        thoughts.load(tag.contains(TAG_THOUGHTS)
            ? tag.getList(TAG_THOUGHTS, net.minecraft.nbt.Tag.TAG_COMPOUND)
            : null);
        if (tag.contains(TAG_CHUNK_SAMPLES)) {
            int[] saved = tag.getIntArray(TAG_CHUNK_SAMPLES);
            int n = Math.min(saved.length, chunkSamples.length);
            System.arraycopy(saved, 0, chunkSamples, 0, n);
        }
        this.lastChunkEvalDay = tag.contains(TAG_LAST_CHUNK_EVAL_DAY)
            ? tag.getLong(TAG_LAST_CHUNK_EVAL_DAY)
            : -1L;

        this.citizenName = tag.contains(TAG_CITIZEN_NAME) ? tag.getString(TAG_CITIZEN_NAME) : null;

        this.nameBaked = tag.contains(TAG_NAME_BAKED) && tag.getBoolean(TAG_NAME_BAKED);
        boolean loadedPregnant = tag.contains(TAG_PREGNANT) && tag.getBoolean(TAG_PREGNANT);
        this.entityData.set(DATA_PREGNANT, loadedPregnant);
        long loadedSinceTick = loadedPregnant && tag.contains(TAG_PREGNANT_SINCE_TICK)
            ? tag.getLong(TAG_PREGNANT_SINCE_TICK) : -1L;
        this.entityData.set(DATA_PREGNANT_SINCE_TICK, loadedSinceTick);
        this.pregnancyFatherId = loadedPregnant && tag.hasUUID(TAG_PREGNANCY_FATHER)
            ? tag.getUUID(TAG_PREGNANCY_FATHER) : null;
        boolean loadedChild = tag.contains(TAG_IS_CHILD) && tag.getBoolean(TAG_IS_CHILD);
        this.entityData.set(DATA_IS_CHILD, loadedChild);
        this.bornAtTick = tag.contains(TAG_BORN_AT_TICK) ? tag.getLong(TAG_BORN_AT_TICK) : -1L;
        this.motherId = tag.hasUUID(TAG_MOTHER) ? tag.getUUID(TAG_MOTHER) : null;
        this.earnedSurnameConcept = tag.contains(TAG_EARNED_SURNAME_CONCEPT)
            ? tag.getString(TAG_EARNED_SURNAME_CONCEPT) : null;
        this.earnedSurnameJob = tag.contains(TAG_EARNED_SURNAME_JOB)
            ? tag.getString(TAG_EARNED_SURNAME_JOB) : null;
        this.earnedSurnameTick = tag.contains(TAG_EARNED_SURNAME_TICK)
            ? tag.getLong(TAG_EARNED_SURNAME_TICK) : -1L;

        Settlement s = getSettlement();
        this.nameColor = s != null ? s.identityFormatting() : ChatFormatting.WHITE;

        if (this.citizenName == null && s != null) {
            for (com.bannerbound.core.api.settlement.Citizen c : s.citizens()) {
                if (c.entityId().equals(this.getUUID())) {
                    this.citizenName = c.name();
                    break;
                }
            }
        }

        if (this.citizenName != null && s != null) {
            if (!this.nameBaked) {
                bakeNameInLanguage(this.citizenName);
            }
            if (s.renameCitizen(this.getUUID(), this.citizenName)
                    && this.level() instanceof ServerLevel sl) {
                SettlementData.get(sl).setDirty();
            }
        }

        refreshDisplayName();
        recomputeHappiness();

        this.compliance = tag.contains(TAG_COMPLIANCE)
            ? Math.max(COMPLIANCE_MIN, Math.min(COMPLIANCE_MAX, tag.getInt(TAG_COMPLIANCE)))
            : DEFAULT_COMPLIANCE;
        this.resentmentByPlayer.clear();
        if (tag.contains(TAG_RESENTMENT_MAP)) {
            net.minecraft.nbt.ListTag list = tag.getList(TAG_RESENTMENT_MAP,
                net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                if (!entry.hasUUID(TAG_RESENTMENT_UUID)) continue;
                int v = entry.getInt(TAG_RESENTMENT_VALUE);
                if (v <= 0) continue;
                this.resentmentByPlayer.put(entry.getUUID(TAG_RESENTMENT_UUID), v);
            }
        }

        setJobType(tag.contains(TAG_JOB_TYPE) ? tag.getString(TAG_JOB_TYPE) : null);
        // Both reads MUST follow setJobType: it clears jobPinned and nulls assignedWorkshopId for a null/non-crafter job.
        this.jobPinned = tag.getBoolean(TAG_JOB_PINNED);
        this.assignedWorkshopId = tag.hasUUID(TAG_WORKSHOP_ID) ? tag.getUUID(TAG_WORKSHOP_ID) : null;

        if (this.assignedWorkshopId != null && this.jobTypeId != null
                && !CrafterWorkGoal.JOB_TYPE_ID.equals(this.jobTypeId)
                && com.bannerbound.core.api.job.CitizenJobRegistry.byId(this.jobTypeId) == null) {
            java.util.UUID keep = this.assignedWorkshopId;
            setJobType(CrafterWorkGoal.JOB_TYPE_ID);
            this.assignedWorkshopId = keep;
        }
        this.jobXp.clear();
        if (tag.contains("JobXpMap")) {
            net.minecraft.nbt.CompoundTag xpTag = tag.getCompound("JobXpMap");
            for (String key : xpTag.getAllKeys()) {
                this.jobXp.put(key, xpTag.getFloat(key));
            }
        }
        this.jobTool = tag.contains(TAG_JOB_TOOL)
            ? net.minecraft.world.item.ItemStack.parse(this.registryAccess(), tag.getCompound(TAG_JOB_TOOL))
                .orElse(net.minecraft.world.item.ItemStack.EMPTY)
            : net.minecraft.world.item.ItemStack.EMPTY;
        this.jobPickaxe = tag.contains(TAG_JOB_PICKAXE)
            ? net.minecraft.world.item.ItemStack.parse(this.registryAccess(), tag.getCompound(TAG_JOB_PICKAXE))
                .orElse(net.minecraft.world.item.ItemStack.EMPTY)
            : net.minecraft.world.item.ItemStack.EMPTY;
        this.preferredLog = net.minecraft.world.level.block.Blocks.OAK_LOG;
        if (tag.contains(TAG_PREFERRED_LOG)) {
            net.minecraft.resources.ResourceLocation logId =
                net.minecraft.resources.ResourceLocation.tryParse(tag.getString(TAG_PREFERRED_LOG));
            if (logId != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(logId)) {
                this.preferredLog = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(logId);
            }
        }
        this.dropOffPos = tag.contains(TAG_DROP_OFF)
            ? BlockPos.of(tag.getLong(TAG_DROP_OFF)) : null;
        this.seedSourcePos = tag.contains(TAG_SEED_SOURCE)
            ? BlockPos.of(tag.getLong(TAG_SEED_SOURCE)) : null;
        this.forageTargetBits = tag.contains(TAG_FORAGE_TARGETS)
            ? (tag.getInt(TAG_FORAGE_TARGETS) & com.bannerbound.core.api.forager.ForageCategory.ALL_BITS)
            : com.bannerbound.core.api.forager.ForageCategory.ALL_BITS;
        this.hunterPreyOff.clear();
        if (tag.contains(TAG_HUNTER_PREY_OFF)) {
            net.minecraft.nbt.ListTag off = tag.getList(TAG_HUNTER_PREY_OFF,
                net.minecraft.nbt.Tag.TAG_STRING);
            for (int i = 0; i < off.size(); i++) hunterPreyOff.add(off.getString(i));
        }
        this.foresterKeepExtras = !tag.contains(TAG_FORESTER_KEEP_EXTRAS)
            || tag.getBoolean(TAG_FORESTER_KEEP_EXTRAS);
        seedCache.fromTag(tag.contains(TAG_SEED_CACHE)
            ? tag.getList(TAG_SEED_CACHE, net.minecraft.nbt.Tag.TAG_COMPOUND)
            : new net.minecraft.nbt.ListTag(), this.registryAccess());
        anarchyHaul.fromTag(tag.contains(TAG_ANARCHY_HAUL)
            ? tag.getList(TAG_ANARCHY_HAUL, net.minecraft.nbt.Tag.TAG_COMPOUND)
            : new net.minecraft.nbt.ListTag(), this.registryAccess());
    }

    @Override
    public void aiStep() {
        // PathfinderMob.aiStep does NOT call updateSwingTime (only Monster/Player do); drive it or the chop swing never renders.
        this.updateSwingTime();

        if (!this.level().isClientSide && AI_LOD_INTERVAL > 1 && usesAmbientBrain()
                && (this.tickCount + this.getId()) % AI_LOD_INTERVAL != 0) {
            return;
        }

        if (!this.level().isClientSide) {
            refreshAiActivation();
        }
        super.aiStep();
        if (this.level().isClientSide) return;

        if (this.conversationCooldown > 0) {
            this.conversationCooldown--;
        }

        int cur = getStamina();
        if (!working && cur < MAX_STAMINA) {

            int rechargeTicks = isPoisoned() ? RECHARGE_TICKS_PER_POINT * 2 : RECHARGE_TICKS_PER_POINT;
            if (++staminaRechargeTimer >= rechargeTicks) {
                int next = cur + 1;
                setStaminaInternal(next);
                staminaRechargeTimer = 0;
                if (next >= MAX_STAMINA) {
                    resting = false;
                }
            }
        }

        if (resting && this.tickCount % 60 == 0
            && this.level() instanceof ServerLevel restSl) {
            restSl.sendParticles(net.minecraft.core.particles.ParticleTypes.FALLING_WATER,
                this.getX(), this.getY() + this.getBbHeight() + 0.25, this.getZ(),
                6, 0.25, 0.1, 0.25, 0.0);
        }

        if (this.tickCount % 10 == 0) {
            recomputeSpeedModifier();

            Settlement eraSettlement = getSettlement();
            if (eraSettlement != null) {
                int ord = eraSettlement.age().ordinal();
                if (this.entityData.get(DATA_ERA) != ord) {
                    this.entityData.set(DATA_ERA, ord);
                    refreshDisplayName();
                }

                int shovelId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(
                    eraSettlement.getToolForRole("shovel"));
                int hoeId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(
                    eraSettlement.getToolForRole("hoe"));
                if (this.entityData.get(DATA_TOOL_SHOVEL) != shovelId) {
                    this.entityData.set(DATA_TOOL_SHOVEL, shovelId);
                }
                if (this.entityData.get(DATA_TOOL_HOE) != hoeId) {
                    this.entityData.set(DATA_TOOL_HOE, hoeId);
                }

                int jobIconId = com.bannerbound.core.social.JobIcons.iconItemId(eraSettlement, jobTypeId);
                if (jobIconId != lastJobIconItemId) {
                    lastJobIconItemId = jobIconId;
                    refreshDisplayName();
                }
            }
        }

        if ((this.tickCount + Math.abs(this.getId()) % HOURLY_TICK_PERIOD_TICKS)
                % HOURLY_TICK_PERIOD_TICKS == 0) {
            Settlement complianceS = getSettlement();
            if (complianceS != null) {

                recomputeHappiness();
                tickComplianceResentmentHourly(complianceS);
            }
        }

        if (this.level() instanceof ServerLevel thoughtSl) {
            long now = thoughtSl.getGameTime();
            if (thoughts.tick(now)) recomputeHappiness();

            if (this.tickCount % 20 == 0) {
                Settlement jobS = getSettlement();

                boolean unemployed = !isChild() && jobS != null && jobS.isTribe()
                    && !isEmployed()
                    && this.compliance > UNEMPLOYED_COMPLIANCE_FLOOR
                    && !hasWorkRefusalThought();   
                boolean hasUnemployed = thoughts.has(ThoughtKind.UNEMPLOYED, null);
                if (unemployed && !hasUnemployed) {
                    thoughts.add(ThoughtKind.UNEMPLOYED, null, now, thoughtSl.random);
                    recomputeHappiness();
                } else if (!unemployed && hasUnemployed) {
                    thoughts.remove(ThoughtKind.UNEMPLOYED, null);
                    recomputeHappiness();
                }

                if (jobS != null
                        && (jobS.governmentType() == Settlement.Government.NONE || jobS.laborAutoAssign())) {
                    autoFindDropOff(jobS);
                }

                if (jobS != null && !hasJobTool()) {
                    JobTools.tryEquipToolFromStorage(this, jobS);
                }

                long startTick = getPregnantSinceTick();
                if (isPregnant() && startTick > 0L
                    && now - startTick >= PREGNANCY_DURATION_TICKS) {
                    com.bannerbound.core.social.BabyMakingManager.deliver(this, thoughtSl, now);
                }

                if (isChild() && bornAtTick > 0L && now - bornAtTick >= ADULTHOOD_TICKS) {
                    setIsChild(false);
                    com.bannerbound.core.social.BabyMakingManager.broadcastGrewUp(thoughtSl, this);
                }

                Settlement foodS = getSettlement();
                if (foodS != null) {
                    double consumption = foodS.foodConsumptionPerSecond();
                    boolean hasStarving = thoughts.has(ThoughtKind.STARVING, null);
                    boolean hasEatingWell = thoughts.has(ThoughtKind.EATING_WELL, null);
                    boolean hasEatingVeryWell = thoughts.has(ThoughtKind.EATING_VERY_WELL, null);
                    if (consumption <= 0.0) {

                        if (hasStarving) { thoughts.remove(ThoughtKind.STARVING, null); recomputeHappiness(); }
                        if (hasEatingWell) { thoughts.remove(ThoughtKind.EATING_WELL, null); recomputeHappiness(); }
                        if (hasEatingVeryWell) { thoughts.remove(ThoughtKind.EATING_VERY_WELL, null); recomputeHappiness(); }
                    } else {
                        boolean starving = foodS.isStarving();
                        if (starving && !hasStarving) {
                            thoughts.add(ThoughtKind.STARVING, null, now, thoughtSl.random);
                            if (hasEatingWell) thoughts.remove(ThoughtKind.EATING_WELL, null);
                            if (hasEatingVeryWell) thoughts.remove(ThoughtKind.EATING_VERY_WELL, null);
                            recomputeHappiness();
                        } else if (!starving && hasStarving) {
                            thoughts.remove(ThoughtKind.STARVING, null);
                            thoughts.add(ThoughtKind.WAS_STARVING_RECENTLY, null, now, thoughtSl.random);
                            recomputeHappiness();
                        }
                        if (!starving) {

                            double days = foodS.reserveDays();
                            boolean veryWell = days >= 3.0;
                            boolean well = days >= 1.0;
                            if (veryWell) {
                                if (!hasEatingVeryWell) { thoughts.add(ThoughtKind.EATING_VERY_WELL, null, now, thoughtSl.random); recomputeHappiness(); }
                                if (hasEatingWell) { thoughts.remove(ThoughtKind.EATING_WELL, null); recomputeHappiness(); }
                            } else if (well) {
                                if (!hasEatingWell) { thoughts.add(ThoughtKind.EATING_WELL, null, now, thoughtSl.random); recomputeHappiness(); }
                                if (hasEatingVeryWell) { thoughts.remove(ThoughtKind.EATING_VERY_WELL, null); recomputeHappiness(); }
                            } else {
                                if (hasEatingWell) { thoughts.remove(ThoughtKind.EATING_WELL, null); recomputeHappiness(); }
                                if (hasEatingVeryWell) { thoughts.remove(ThoughtKind.EATING_VERY_WELL, null); recomputeHappiness(); }
                            }
                        }
                    }
                }

                boolean homeless = jobS != null && jobS.isTribe()
                    && jobS.getHomeFor(this.getUUID()) == null;
                boolean hasNoHome = thoughts.has(ThoughtKind.NO_HOME, null);
                if (homeless && !hasNoHome) {
                    thoughts.add(ThoughtKind.NO_HOME, null, now, thoughtSl.random);
                    recomputeHappiness();
                } else if (!homeless && hasNoHome) {
                    thoughts.remove(ThoughtKind.NO_HOME, null);
                    recomputeHappiness();
                }

                float maxHp = getMaxHealth();
                if (maxHp > 0f) {
                    float hpRatio = getHealth() / maxHp;
                    boolean badly = hpRatio < 0.5f;
                    boolean inPain = !badly && hpRatio < 0.75f;
                    boolean hasBadly = thoughts.has(ThoughtKind.I_M_BADLY_INJURED, null);
                    boolean hasPain  = thoughts.has(ThoughtKind.I_M_IN_PAIN, null);
                    if (badly) {
                        if (!hasBadly) { thoughts.add(ThoughtKind.I_M_BADLY_INJURED, null, now, thoughtSl.random); recomputeHappiness(); }
                        if (hasPain)   { thoughts.remove(ThoughtKind.I_M_IN_PAIN, null); recomputeHappiness(); }
                    } else if (inPain) {
                        if (!hasPain)  { thoughts.add(ThoughtKind.I_M_IN_PAIN, null, now, thoughtSl.random); recomputeHappiness(); }
                        if (hasBadly)  { thoughts.remove(ThoughtKind.I_M_BADLY_INJURED, null); recomputeHappiness(); }
                    } else {
                        if (hasBadly)  { thoughts.remove(ThoughtKind.I_M_BADLY_INJURED, null); recomputeHappiness(); }
                        if (hasPain)   { thoughts.remove(ThoughtKind.I_M_IN_PAIN, null); recomputeHappiness(); }
                    }
                }

                int poisonStage = getPersistentData().getInt(POISON_STAGE_TAG);
                setPoisonStage(poisonStage);
                boolean poisoned = poisonStage > 0;
                boolean hasPoisoned = thoughts.has(ThoughtKind.POISONED, null);
                if (poisoned && !hasPoisoned) {
                    thoughts.add(ThoughtKind.POISONED, null, now, thoughtSl.random);
                    recomputeHappiness();
                } else if (!poisoned && hasPoisoned) {
                    thoughts.remove(ThoughtKind.POISONED, null);
                    recomputeHappiness();
                }

                Settlement statusS = getSettlement();
                boolean blocked = false;
                if (statusS != null) {
                    boolean anarchy = statusS.governmentType() == Settlement.Government.NONE;
                    CitizenWorkStatus ws = CitizenWorkStatus.derive(this, statusS, anarchy);
                    blocked = ws.category() == CitizenWorkStatus.Category.BLOCKED;
                }
                setWorkBlocked(blocked);
            }

            if (this.tickCount % 100 == 0) {
                tryAutoAssignHome();
            }

            if (this.tickCount % 20 == 0 && HunterWorkGoal.JOB_TYPE_ID.equals(getJobType())) {
                HunterOffscreenTicker.tick(this, thoughtSl);
            }

            if (this.tickCount % 20 == 0 && ForagerWorkGoal.JOB_TYPE_ID.equals(getJobType())) {
                ForagerOffscreenTicker.tick(this, thoughtSl);
            }

            if (this.tickCount % 100 == 0) {
                long packed = new ChunkPos(this.blockPosition()).toLong();
                ChunkBeauty b = ChunkBeautyManager.beautyOf(thoughtSl, packed);
                if (b == null) b = ChunkBeauty.BLAND;
                chunkSamples[b.ordinal()]++;
            }

            if (this.tickCount % 120 == 0 && thoughts.has(ThoughtKind.STARVING, null)) {
                this.hurt(this.damageSources().starve(), 1.0f);
            }

            if (this.tickCount % 140 == 0 && this.getHealth() < this.getMaxHealth()) {
                Settlement regenS = getSettlement();
                if (regenS != null && !regenS.isStarving()) {
                    this.heal(1.0f);
                }
            }

            if (isChild() && this.tickCount % CHILD_THOUGHT_INTERVAL == 0
                && thoughtSl.random.nextDouble() < CHILD_THOUGHT_CHANCE) {
                ThoughtKind[] pool = ThoughtKind.CHILD_FLAVOUR_THOUGHTS;
                ThoughtKind kind = pool[thoughtSl.random.nextInt(pool.length)];
                thoughts.add(kind, null, now, thoughtSl.random);
                recomputeHappiness();
            }

            long today = thoughtSl.getDayTime() / 24_000L;
            if (today != lastChunkEvalDay) {
                if (lastChunkEvalDay != -1L) {
                    Settlement evalS = getSettlement();
                    com.bannerbound.core.api.settlement.Home home = evalS != null
                        ? evalS.getHomeFor(this.getUUID()) : null;
                    if (home != null && home.valid()) {
                        evaluateDailyHomeQuality(thoughtSl, evalS, home, now);
                    } else {
                        evaluateDailyChunkQuality(thoughtSl, now);
                    }

                    if (!isAnarchy() && this.compliance <= 30) {
                        double strikeChance = com.bannerbound.core.api.settlement.ComplianceTables
                            .refuseFullDay(this.compliance);
                        if (strikeChance > 0 && thoughtSl.random.nextDouble() < strikeChance) {
                            thoughts.add(ThoughtKind.NO_WORK_TODAY, null, now, thoughtSl.random);
                            recomputeHappiness();
                        }
                    }

                }
                java.util.Arrays.fill(chunkSamples, 0);
                lastChunkEvalDay = today;
            }
        }

        if (this.tickCount % STUCK_SAMPLE_INTERVAL == 0) {
            double dx = this.getX() - stuckLastX;
            double dy = this.getY() - stuckLastY;
            double dz = this.getZ() - stuckLastZ;
            boolean trying = !this.getNavigation().isDone();
            if (trying && (dx * dx + dy * dy + dz * dz) < STUCK_RADIUS_SQ) {
                stuckTicks += STUCK_SAMPLE_INTERVAL;
                if (stuckTicks >= STUCK_TICK_THRESHOLD) {
                    recallToTownHall();
                    stuckTicks = 0;
                }
            } else {
                stuckTicks = 0;
            }
            stuckLastX = this.getX();
            stuckLastY = this.getY();
            stuckLastZ = this.getZ();
        }
    }

    private void evaluateDailyChunkQuality(ServerLevel sl, long now) {
        int total = 0;
        for (int s : chunkSamples) total += s;
        if (total <= 0) return;
        int hate = chunkSamples[ChunkBeauty.ATROCIOUS.ordinal()]
                 + chunkSamples[ChunkBeauty.REPULSIVE.ordinal()];
        int uncomfortable = chunkSamples[ChunkBeauty.DISGUSTING.ordinal()]
                          + chunkSamples[ChunkBeauty.UNAPPEALING.ordinal()];
        int like = chunkSamples[ChunkBeauty.PLEASANT.ordinal()]
                 + chunkSamples[ChunkBeauty.ATTRACTIVE.ordinal()]
                 + chunkSamples[ChunkBeauty.STUNNING.ordinal()];
        int love = chunkSamples[ChunkBeauty.BREATHTAKING.ordinal()];

        ThoughtKind kind = null;
        if (love * 4 > total * 3) kind = ThoughtKind.LOVE_HERE;
        else if (like * 4 > total * 3) kind = ThoughtKind.LIKE_HERE;
        else if (hate * 4 > total * 3) kind = ThoughtKind.HATE_HERE;
        else if (uncomfortable * 4 > total * 3) kind = ThoughtKind.UNCOMFORTABLE_HERE;
        if (kind == null) return;

        for (ThoughtKind k : new ThoughtKind[]{
            ThoughtKind.LOVE_HERE, ThoughtKind.LIKE_HERE,
            ThoughtKind.UNCOMFORTABLE_HERE, ThoughtKind.HATE_HERE}) {
            if (k != kind) thoughts.remove(k, null);
        }
        thoughts.add(kind, null, now, sl.random);
        recomputeHappiness();
    }

    private void evaluateDailyHomeQuality(ServerLevel sl, Settlement settlement,
                                           com.bannerbound.core.api.settlement.Home home, long now) {

        com.bannerbound.core.api.settlement.Homes.validate(sl, home);
        ThoughtKind kind = com.bannerbound.core.api.settlement.HomeDemand.moodThoughtFor(
            home.cachedHomeHappiness());

        for (ThoughtKind k : new ThoughtKind[]{
            ThoughtKind.LOVE_HERE, ThoughtKind.LIKE_HERE,
            ThoughtKind.UNCOMFORTABLE_HERE, ThoughtKind.HATE_HERE}) {
            thoughts.remove(k, null);
        }

        for (ThoughtKind k : new ThoughtKind[]{
            ThoughtKind.LOVE_HOME, ThoughtKind.LIKE_HOME, ThoughtKind.NICE_HOME,
            ThoughtKind.UNCOMFORTABLE_HOME, ThoughtKind.HATE_HOME}) {
            thoughts.remove(k, null);
        }
        if (kind != null) thoughts.add(kind, null, now, sl.random);
        recomputeHappiness();
    }

    private void tryAutoAssignHome() {
        Settlement s = getSettlement();
        if (s == null) return;

        if (this.outpostSite != null) {
            if (s.workingClaims().contains(new ChunkPos(this.outpostSite).toLong())) {
                com.bannerbound.core.api.settlement.Home home = s.getHomeFor(this.getUUID());
                if (home != null && this.level() instanceof ServerLevel sl && sl.getServer() != null) {
                    home.removeResident(this.getUUID());
                    SettlementData.get(sl.getServer().overworld()).setDirty();
                }
                return;
            }
            this.outpostSite = null;   
        }
        if (s.getHomeFor(this.getUUID()) != null) return; 
        java.util.List<com.bannerbound.core.api.settlement.Home> options = s.homesWithVacancy();
        if (options.isEmpty()) return;
        com.bannerbound.core.api.settlement.Home nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (com.bannerbound.core.api.settlement.Home h : options) {
            double d2 = this.distanceToSqr(
                h.pos().getX() + 0.5, h.pos().getY() + 0.5, h.pos().getZ() + 0.5);
            if (d2 < bestSq) {
                bestSq = d2;
                nearest = h;
            }
        }
        if (nearest != null && nearest.addResident(this.getUUID())) {

            if (this.level() instanceof ServerLevel sl && sl.getServer() != null) {
                SettlementData.get(sl.getServer().overworld()).setDirty();
            }
            if (thoughts.remove(ThoughtKind.NO_HOME, null)) recomputeHappiness();
        }
    }

    public static final String TELEPORT_AT_KEY = "BannerboundTeleportAt";

    public static void tagDeliberateTeleport(net.minecraft.world.entity.Entity e) {
        e.getPersistentData().putLong(TELEPORT_AT_KEY, e.level().getGameTime());
    }

    public void recallToTownHall() {
        Settlement s = getSettlement();
        if (s == null || s.townHallPos() == null) return;
        if (!(this.level() instanceof ServerLevel sl)) return;
        BlockPos th = s.townHallPos();
        BlockPos landing = findRecallLandingPos(sl, th);
        this.getNavigation().stop();
        this.teleportTo(sl, landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5,
            java.util.Set.of(), this.getYRot(), this.getXRot());
        tagDeliberateTeleport(this);

        this.stuckLastX = this.getX();
        this.stuckLastY = this.getY();
        this.stuckLastZ = this.getZ();
        this.stuckTicks = 0;
    }

    private static final int CANNOT_REACH_COOLDOWN_TICKS = 1200;

    private long cannotReachLastTick = Long.MIN_VALUE / 2;

    public void broadcastCannotReach(BlockPos target) {
        if (target == null) return;
        if (!(this.level() instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        if (now - cannotReachLastTick < CANNOT_REACH_COOLDOWN_TICKS) return;
        cannotReachLastTick = now;

        Settlement s = getSettlement();
        if (s == null) return;
        MinecraftServer server = sl.getServer();
        if (server == null) return;

        Component name = this.getDisplayName();
        Component msg = Component.translatable("bannerbound.citizen.cannot_reach",
            name, target.getX(), target.getY(), target.getZ())
            .withStyle(net.minecraft.ChatFormatting.YELLOW);
        for (UUID memberId : s.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(memberId);
            if (p != null) p.sendSystemMessage(msg);
        }
    }

    private BlockPos findRecallLandingPos(ServerLevel sl, BlockPos townHall) {
        int[] dxz = {-1, 0, 1};
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx : dxz) {
                for (int dz : dxz) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos candidate = new BlockPos(townHall.getX() + dx, townHall.getY() + dy, townHall.getZ() + dz);
                    if (sl.getBlockState(candidate).isAir()
                            && sl.getBlockState(candidate.above()).isAir()
                            && !sl.getBlockState(candidate.below()).isAir()) {
                        return candidate;
                    }
                }
            }
        }
        return townHall.above();
    }

    private void recomputeSpeedModifier() {
        net.minecraft.world.entity.ai.attributes.AttributeInstance attr =
            this.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (attr == null) return;

        if (Math.abs(attr.getBaseValue() - BASE_MOVEMENT_SPEED) > 1e-6) {
            attr.setBaseValue(BASE_MOVEMENT_SPEED);
        }

        attr.removeModifier(SPEED_MODIFIER_ID);

        double additive = 0.0; 
        double multiplied = 0.0; 
        Settlement s = getSettlement();
        if (s != null) {
            additive += s.bonusCitizenSpeed();
            additive += s.faithEffects().citizenSpeed(); 

            if (s.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.ROADS)) {
                BlockPos below = this.blockPosition().below();
                if (this.level().getBlockState(below).is(net.minecraft.world.level.block.Blocks.DIRT_PATH)) {
                    multiplied += com.bannerbound.core.api.settlement.PolicyEffects.ROADS_SPEED_BONUS;
                }
            }
        }

        multiplied += happinessSpeedMultiplier() - 1.0f;
        double total = additive + (attr.getBaseValue() * multiplied);
        if (Math.abs(total) > 1e-6) {

            attr.addTransientModifier(new net.minecraft.world.entity.ai.attributes.AttributeModifier(
                SPEED_MODIFIER_ID, total,
                net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE));
        }
    }
}
