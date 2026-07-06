package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Citizen;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.social.Conversation;
import com.bannerbound.core.social.Conversation.Phase;
import com.bannerbound.core.social.ConversationTopic;
import com.bannerbound.core.social.SocialEvents;
import com.bannerbound.core.social.ThoughtKind;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/**
 * Per-citizen AI goal driving one side of a Bannerbound Conversation: meet at a midpoint near the
 * town hall, face the partner, exchange three speech bubbles, and apply a relationship delta based
 * on how many bubbles matched. See {@code MDK/docs/citizens.md} for the system overview.
 *
 * <p><b>Initiator vs joiner.</b> Two of these goals - one per citizen - share a single
 * {@link Conversation} held on the settlement. The initiator (whose {@link #canUse} created the
 * Conversation) is the sole writer of phase transitions and outcome resolution; the joiner only
 * writes its own facing / navigation / bubble icon. Both compute "time in phase" from
 * {@link Conversation#phaseStartGameTime} so intra-tick ticking order doesn't matter.
 *
 * <p><b>Timing.</b> The post-conversation cooldown is a uniform random roll in
 * [CONV_COOLDOWN_MIN_TICKS (2 min), CONV_COOLDOWN_MAX_TICKS (7 min)] so a settlement never develops
 * a chat heartbeat. A ~30s LOAD_CHAT_GRACE_TICKS after (re)load suppresses the world-open chatter
 * burst: tickCount resets to 0 each load, so pre-save cooldowns don't survive and every citizen
 * would "arrive" at once. The initiator's partner scan is throttled (think-tick +
 * PARTNER_SCAN_INTERVAL) to avoid N-squared cost; the joiner path is left un-gated so a partner
 * attaches promptly.
 *
 * <p><b>Same-tick transition trap.</b> Phase entry actions - the bubble pop sound + match counting
 * in {@link #onBubbleEntry}, outcome application in {@link #onResolvingEntry} - run at the
 * transition site in tickFaceOff/tickGap/tickBubble, NOT inside the target phase's tick handler:
 * transitionTo happens in the same tick, so a handler never observes t == 0 for its own first tick.
 *
 * <p>A 0-match argument can escalate to a real brawl (Step 14) via {@link ConflictGoal} when the
 * settlement is anarchic or either side's compliance is low.
 */
public class ConversationGoal extends Goal {
    public static final int CONV_COOLDOWN_MIN_TICKS = 2_400;
    public static final int CONV_COOLDOWN_MAX_TICKS = 8_400;
    private static final int PARTNER_SCAN_INTERVAL = 20;
    private static final int LOAD_CHAT_GRACE_TICKS = 600;

    private final CitizenEntity citizen;
    private final double speedModifier;

    @Nullable private Conversation active;
    private boolean isInitiator;
    @Nullable private CitizenEntity partnerEntity;
    private int scanCooldown = 0;

    public ConversationGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(citizen.level() instanceof ServerLevel sl)) return false;
        if (!citizen.isAiActive()) return false;
        if (citizen.tickCount < LOAD_CHAT_GRACE_TICKS) return false;
        if (citizen.isPassenger()) return false;
        if (citizen.getConversationCooldown() > 0) return false;

        Settlement settlement = citizen.getSettlement();
        if (settlement == null) return false;
        if (settlement.activeCrisis() != null) return false;
        if (settlement.hasPolicy(com.bannerbound.core.api.settlement.PolicyRegistry.NIGHTSHIFT)
                && citizen.isEmployed()) {
            return false;
        }
        BlockPos townHall = settlement.townHallPos();
        if (townHall == null) return false;

        Vec3 thVec = Vec3.atCenterOf(townHall);
        double socialSq = Conversation.SOCIAL_RADIUS * Conversation.SOCIAL_RADIUS;
        if (citizen.position().distanceToSqr(thVec) > socialSq) return false;

        Conversation existing = settlement.findActiveConversationFor(citizen.getUUID());
        if (existing != null && existing.phase != Phase.DONE) {
            UUID otherId = existing.otherSide(citizen.getUUID());
            if (otherId != null && sl.getEntity(otherId) instanceof CitizenEntity other) {
                this.active = existing;
                this.isInitiator = existing.a.equals(citizen.getUUID());
                this.partnerEntity = other;
                return true;
            }
            return false;
        }

        if (!citizen.isThinkTick()) return false;
        if (scanCooldown > 0) { scanCooldown--; return false; }
        scanCooldown = PARTNER_SCAN_INTERVAL;
        CitizenEntity partner = findPartner(sl, settlement, thVec, socialSq);
        if (partner == null) return false;

        BlockPos midpoint = BlockPos.containing(
            (citizen.getX() + partner.getX()) * 0.5,
            citizen.getY(),
            (citizen.getZ() + partner.getZ()) * 0.5);

        Conversation conv = Conversation.begin(
            citizen.getUUID(), partner.getUUID(), midpoint, midpoint,
            citizen.getHappiness(), partner.getHappiness(),
            settlement.isTribe(), sl.random);
        settlement.startConversation(conv);
        this.active = conv;
        this.isInitiator = true;
        this.partnerEntity = partner;
        return true;
    }

    private CitizenEntity findPartner(ServerLevel sl, Settlement settlement, Vec3 thVec, double socialSq) {
        List<CitizenEntity> candidates = new ArrayList<>();
        for (Citizen c : settlement.citizens()) {
            UUID id = c.entityId();
            if (id.equals(citizen.getUUID())) continue;
            if (!(sl.getEntity(id) instanceof CitizenEntity other)) continue;
            if (!other.isAlive()) continue;
            if (other.getSettlement() != settlement) continue;
            if (other.tickCount < LOAD_CHAT_GRACE_TICKS) continue;
            if (other.isPassenger()) continue;
            if (other.getConversationCooldown() > 0) continue;
            if (other.getBubbleTopic() != 0) continue;
            if (settlement.findActiveConversationFor(id) != null) continue;
            if (other.position().distanceToSqr(thVec) > socialSq) continue;
            candidates.add(other);
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(sl.random.nextInt(candidates.size()));
    }

    @Override
    public void start() {
        if (active == null || partnerEntity == null) return;
        citizen.getNavigation().moveTo(partnerEntity, speedModifier);
        citizen.setBubbleTopic(0);
        if (isInitiator && citizen.level() instanceof ServerLevel sl) {
            active.transitionTo(Phase.WALK_TO_MEET, sl.getGameTime());
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (active == null) return false;
        if (active.phase == Phase.DONE) return false;
        if (partnerEntity == null || !partnerEntity.isAlive()) return false;
        return true;
    }

    @Override
    public void stop() {
        citizen.setBubbleTopic(0);
        citizen.setConversationCooldown(rollCooldown());
        citizen.getNavigation().stop();
        if (isInitiator && active != null) {
            Settlement s = citizen.getSettlement();
            if (s != null) s.endConversation(active);
        }
        active = null;
        partnerEntity = null;
        isInitiator = false;
    }

    @Override
    public void tick() {
        if (active == null) return;
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        long now = sl.getGameTime();
        int t = active.ticksInPhase(now);
        switch (active.phase) {
            case WALK_TO_MEET -> tickWalk(t, now);
            case FACE_OFF    -> tickFaceOff(t, now);
            case BUBBLE      -> tickBubble(t, now);
            case GAP         -> tickGap(t, now);
            case RESOLVING   -> tickResolving(t, now);
            case DONE        -> {}
        }
    }

    private void tickWalk(int t, long now) {
        if (partnerEntity == null) return;
        double d2 = citizen.distanceToSqr(partnerEntity);
        if (d2 < 4.0) {
            active.markArrived(citizen.getUUID());
            citizen.getNavigation().stop();
        } else if (citizen.getNavigation().isDone()) {
            citizen.getNavigation().moveTo(partnerEntity, speedModifier);
        }
        if (!isInitiator) return;
        if (t > Conversation.WALK_TIMEOUT_TICKS) {
            active.transitionTo(Phase.DONE, now);
            return;
        }
        if (active.bothArrived()) {
            active.transitionTo(Phase.FACE_OFF, now);
        }
    }

    private void tickFaceOff(int t, long now) {
        faceOther();
        if (!isInitiator) return;
        if (t >= Conversation.FACE_OFF_TICKS) {
            active.currentBubble = 0;
            active.transitionTo(Phase.BUBBLE, now);
            onBubbleEntry();
        }
    }

    private void tickBubble(int t, long now) {
        faceOther();
        ConversationTopic mine = active.topicFor(citizen.getUUID(), active.currentBubble);
        int packed = mine.packBubbleId(resolveSubType(mine));
        if (citizen.getBubbleTopic() != packed) {
            citizen.setBubbleTopic(packed);
        }
        if (!isInitiator) return;
        if (t >= Conversation.BUBBLE_TICKS) {
            citizen.setBubbleTopic(0);
            if (partnerEntity != null) partnerEntity.setBubbleTopic(0);
            Phase next = active.currentBubble < 2 ? Phase.GAP : Phase.RESOLVING;
            active.transitionTo(next, now);
            if (next == Phase.RESOLVING) onResolvingEntry();
        }
    }

    private void tickGap(int t, long now) {
        faceOther();
        if (!isInitiator) return;
        if (t >= Conversation.GAP_TICKS) {
            active.currentBubble++;
            active.transitionTo(Phase.BUBBLE, now);
            onBubbleEntry();
        }
    }

    private void tickResolving(int t, long now) {
        faceOther();
        if (!isInitiator) return;
        if (t >= Conversation.RESOLVING_TICKS) {
            active.transitionTo(Phase.DONE, now);
        }
    }

    private void onBubbleEntry() {
        if (active == null || partnerEntity == null) return;
        playPop();
        UUID partnerId = active.otherSide(citizen.getUUID());
        if (partnerId != null) {
            ConversationTopic mine = active.topicFor(citizen.getUUID(), active.currentBubble);
            ConversationTopic theirs = active.topicFor(partnerId, active.currentBubble);
            if (mine == theirs) active.matches++;
        }
    }

    private void onResolvingEntry() {
        if (active == null || active.outcomeApplied || partnerEntity == null) return;
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        int delta = Conversation.outcomeDelta(active.matches);
        SocialEvents.applyMutual(citizen, partnerEntity, delta);
        SocialEvents.spawnOutcomeParticles(citizen, partnerEntity, active.matches);
        ThoughtKind kind = switch (active.matches) {
            case 0 -> ThoughtKind.FIGHT_WITH;
            case 1 -> ThoughtKind.ARGUMENT_WITH;
            case 3 -> ThoughtKind.GREAT_CONVERSATION_WITH;
            default -> null;
        };
        if (kind != null) {
            long now = sl.getGameTime();
            citizen.getThoughts().add(kind, partnerEntity.getUUID(), now, sl.random);
            citizen.recomputeHappiness();
            partnerEntity.getThoughts().add(kind, citizen.getUUID(), now, sl.random);
            partnerEntity.recomputeHappiness();
        }
        if (active.matches == 0 && shouldEscalateConflict(sl)) {
            ConflictGoal.escalate(citizen, partnerEntity, sl);
        }
        active.outcomeApplied = true;
    }

    private boolean shouldEscalateConflict(ServerLevel sl) {
        if (partnerEntity == null) return false;
        if (citizen.isChild() || partnerEntity.isChild()) return false;
        if (!citizen.isAlive() || !partnerEntity.isAlive()) return false;
        com.bannerbound.core.api.settlement.Settlement s = citizen.getSettlement();
        if (s == null) return false;
        boolean anarchy = s.governmentType()
            == com.bannerbound.core.api.settlement.Settlement.Government.NONE;
        boolean lowCompliance = citizen.getCompliance() < CONFLICT_LOW_COMPLIANCE_THRESHOLD
            || partnerEntity.getCompliance() < CONFLICT_LOW_COMPLIANCE_THRESHOLD;
        if (!anarchy && !lowCompliance) return false;
        return sl.random.nextDouble() < CONFLICT_ESCALATION_CHANCE;
    }

    private static final double CONFLICT_ESCALATION_CHANCE = 0.25;
    private static final int CONFLICT_LOW_COMPLIANCE_THRESHOLD = 30;

    private void faceOther() {
        if (partnerEntity == null) return;
        double dx = partnerEntity.getX() - citizen.getX();
        double dz = partnerEntity.getZ() - citizen.getZ();
        if (dx * dx + dz * dz < 1.0e-4) return;
        float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        citizen.setYBodyRot(yaw);
        citizen.setYHeadRot(yaw);
        citizen.getLookControl().setLookAt(
            partnerEntity.getX(), partnerEntity.getEyeY(), partnerEntity.getZ());
    }

    private int rollCooldown() {
        int span = CONV_COOLDOWN_MAX_TICKS - CONV_COOLDOWN_MIN_TICKS;
        if (span <= 0) return CONV_COOLDOWN_MIN_TICKS;
        if (citizen.level() instanceof ServerLevel sl) {
            return CONV_COOLDOWN_MIN_TICKS + sl.random.nextInt(span + 1);
        }
        return CONV_COOLDOWN_MIN_TICKS;
    }

    private int resolveSubType(ConversationTopic topic) {
        return switch (topic) {
            case CULTURE, FOOD, SCIENCE -> 0;
            case HAPPINESS -> happinessBucket(citizen.getHappiness());
            case JOB -> jobSubType(citizen);
        };
    }

    private static int happinessBucket(int happiness) {
        // keep these thresholds in sync with client Icons.happiness or the bucket->glyph goes out of register
        if (happiness >= 70) return 2;
        if (happiness >= 40) return 1;
        return 0;
    }

    private static int jobSubType(CitizenEntity c) {
        String jobType = c.getJobType();
        if (jobType == null) return 0;
        return com.bannerbound.core.social.WorkstationIcons.ordinalOf(jobType);
    }

    private void playPop() {
        if (!(citizen.level() instanceof ServerLevel sl)) return;
        if (active == null) return;
        BlockPos meet = active.standFor(citizen.getUUID());
        sl.playSound(null, meet, BannerboundCore.BUBBLE_POP_SOUND.get(),
            SoundSource.NEUTRAL, 0.35f, 1.0f);
    }
}
