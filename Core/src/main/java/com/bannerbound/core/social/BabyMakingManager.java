package com.bannerbound.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Citizen;
import com.bannerbound.core.api.settlement.CitizenGender;
import com.bannerbound.core.api.settlement.Home;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.data.CitizenNameLoader;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;

/**
 * Server-side singleton running Antiquity's procreation loop, wired in
 * {@code ResearchEvents.onServerTick} alongside the other per-tick managers (cheap on a
 * no-pregnancy server: it only re-scans during night and the session list is usually empty). Two
 * responsibilities:
 * <ol>
 *   <li>Periodically during the night, scan every settlement's valid homes for opposite-gender
 *       resident pairs that are both asleep, adult, and above STRANGERS. Roll the per-tier chance
 *       ({@link #chanceFor}), nudged by the home's happiness; each success opens a
 *       {@link LovemakingSession} that pulses heart particles 5 times over ~12 s and ends with the
 *       woman flagged pregnant.</li>
 *   <li>Every tick, advance active sessions, cancel any whose participants stopped sleeping / died /
 *       lost the home, and deliver babies whose pregnancy timer elapsed ({@link #deliver}, invoked
 *       from {@code CitizenEntity.aiStep}'s 20-tick poll).</li>
 * </ol>
 * Scanning is periodic (every {@link #SCAN_INTERVAL_TICKS}) rather than one-shot-at-night-start: the
 * one-shot version fired before citizens reached their beds, found everyone awake, then never
 * re-scanned. {@link #ROLLED_TONIGHT} (cleared each new night) enforces the pair-selection
 * invariants: a woman conceives at most once per night and a man fathers at most once, whether the
 * roll succeeds or fails. Iteration order is randomised so the same (M, F) does not always win; a
 * 2M+2F home can still produce up to 2 pregnancies per night. Both the mate-scan and delivery honour
 * a soft population cap - at the cap no new sessions fire and finished pregnancies are held open
 * (never cancelled) to retry once the cap rises or population drops. Sessions and ROLLED_TONIGHT are
 * server-only, never persisted; a save mid-session just drops them.
 */
@ApiStatus.Internal
public final class BabyMakingManager {
    private static final long NIGHT_START = 12_500L; // matches SleepGoal's night window (monsters spawn / beds usable)
    private static final long NIGHT_END = 23_460L;
    private static final int SCAN_INTERVAL_TICKS = 100;
    private static final int LOVEMAKING_BURST_INTERVAL = 60;
    private static final int LOVEMAKING_BURSTS = 5;

    private static long currentNightDay = -1L;
    private static final java.util.Set<UUID> ROLLED_TONIGHT = new java.util.HashSet<>();
    private static final List<LovemakingSession> SESSIONS = new ArrayList<>();

    private BabyMakingManager() {}

    public static void tickAll(MinecraftServer server) {
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        long now = overworld.getGameTime();
        long t = overworld.getDayTime() % 24_000L;
        long day = overworld.getDayTime() / 24_000L;
        boolean isNight = t >= NIGHT_START && t < NIGHT_END;

        if (!isNight) {
            if (currentNightDay != -1L) {
                currentNightDay = -1L;
                ROLLED_TONIGHT.clear();
            }
        } else {
            if (currentNightDay != day) {
                currentNightDay = day;
                ROLLED_TONIGHT.clear();
            }
            if (now % SCAN_INTERVAL_TICKS == 0) {
                startSessions(overworld, now);
            }
        }

        if (!SESSIONS.isEmpty()) {
            tickSessions(overworld, now);
        }
    }

    private static void startSessions(ServerLevel overworld, long now) {
        SettlementData sd = SettlementData.get(overworld);
        RandomSource rng = overworld.random;
        for (Settlement s : sd.all()) {
            if (s.population() >= s.populationMaximum()) continue;
            for (Home home : s.homes().values()) {
                if (!home.valid()) continue;
                List<CitizenEntity> men = new ArrayList<>();
                List<CitizenEntity> women = new ArrayList<>();
                for (UUID rid : home.residents()) {
                    Entity raw = overworld.getEntity(rid);
                    if (!(raw instanceof CitizenEntity ce)) continue;
                    if (!ce.isAlive() || ce.isChild() || !ce.isSleeping()) continue;
                    if (ce.getGender() == CitizenGender.MALE) {
                        if (!ROLLED_TONIGHT.contains(ce.getUUID())) men.add(ce);
                    } else if (!ce.isPregnant() && !ROLLED_TONIGHT.contains(ce.getUUID())) {
                        women.add(ce);
                    }
                }
                if (men.isEmpty() || women.isEmpty()) continue;
                Random javaRng = new Random(rng.nextLong());
                Collections.shuffle(men, javaRng);
                Collections.shuffle(women, javaRng);
                Set<UUID> takenWomen = new HashSet<>();
                Set<UUID> takenMen = new HashSet<>();
                for (CitizenEntity m : men) {
                    if (takenMen.contains(m.getUUID())) continue;
                    for (CitizenEntity w : women) {
                        if (takenWomen.contains(w.getUUID())) continue;
                        Relationship rel = m.getRelationships().get(w.getUUID());
                        if (rel.isFamily()) continue;
                        double chance = (chanceFor(rel.tier())
                            + com.bannerbound.core.api.settlement.HomeDemand.reproductionBonus(
                                home.cachedHomeHappiness()))
                            * com.bannerbound.core.Config.BIRTH_RATE_MULTIPLIER.get();
                        if (chance <= 0.0) continue;
                        boolean success = rng.nextDouble() < chance;
                        ROLLED_TONIGHT.add(w.getUUID());
                        ROLLED_TONIGHT.add(m.getUUID());
                        takenWomen.add(w.getUUID());
                        takenMen.add(m.getUUID());
                        if (success) {
                            SESSIONS.add(new LovemakingSession(
                                w.getUUID(), m.getUUID(), home.pos(), now));
                        }
                        break;
                    }
                }
            }
        }
    }

    private static void tickSessions(ServerLevel overworld, long now) {
        Iterator<LovemakingSession> it = SESSIONS.iterator();
        while (it.hasNext()) {
            LovemakingSession sess = it.next();
            Entity mEnt = overworld.getEntity(sess.motherId());
            Entity fEnt = overworld.getEntity(sess.fatherId());
            if (!(mEnt instanceof CitizenEntity mother) || !(fEnt instanceof CitizenEntity father)) {
                it.remove(); continue;
            }
            if (!mother.isAlive() || !father.isAlive()
                || !mother.isSleeping() || !father.isSleeping()) {
                it.remove(); continue;
            }
            long elapsed = now - sess.startTick();
            if (elapsed < 0 || elapsed % LOVEMAKING_BURST_INTERVAL != 0) continue;
            int burstIdx = (int) (elapsed / LOVEMAKING_BURST_INTERVAL);
            if (burstIdx >= LOVEMAKING_BURSTS) {
                it.remove(); continue;
            }
            SocialEvents.spawnHearts(overworld, mother);
            SocialEvents.spawnHearts(overworld, father);
            if (burstIdx == LOVEMAKING_BURSTS - 1) {
                mother.setPregnant(true, now, father.getUUID());
                it.remove();
            }
        }
    }

    private static double chanceFor(RelationshipTier tier) {
        return switch (tier) {
            case ACQUAINTANCES    -> 0.15;
            case FRIENDS          -> 0.30;
            case CLOSE_FRIENDS    -> 0.65;
            case FRIENDS_FOR_LIFE -> 1.0;
            default               -> 0.0;
        };
    }

    public static void deliver(CitizenEntity mother, ServerLevel sl, long now) {
        UUID fatherId = mother.getPregnancyFatherId();
        Settlement s = mother.getSettlement();
        if (s == null) {
            mother.setPregnant(false, -1L, null);
            return;
        }
        // At the pop cap, hold the pregnancy open (never setPregnant(false)) so the next poll retries; do NOT cancel.
        if (s.population() >= s.populationMaximum()) return;
        CitizenEntity child = BannerboundCore.CITIZEN.get().create(sl);
        if (child == null) {
            mother.setPregnant(false, -1L, null);
            return;
        }
        CitizenGender gender = sl.random.nextBoolean() ? CitizenGender.MALE : CitizenGender.FEMALE;
        String name = CitizenNameLoader.randomName(sl.random, s.age(), gender);
        child.initializeCitizen(s.id(), name, gender, s.age(), s.identityFormatting());
        name = child.getCitizenName();
        child.setIsChild(true);
        child.setBornAtTick(now);
        child.setMotherId(mother.getUUID());
        child.moveTo(mother.getX(), mother.getY(), mother.getZ(), 0f, 0f);
        child.finalizeSpawn(sl, sl.getCurrentDifficultyAt(mother.blockPosition()),
            MobSpawnType.MOB_SUMMONED, null);
        if (!sl.addFreshEntity(child)) {
            mother.setPregnant(false, -1L, null);
            return;
        }
        s.addCitizen(new Citizen(child.getUUID(), name));

        SocialEvents.linkMutualFamily(child, mother);
        CitizenEntity father = null;
        if (fatherId != null && sl.getEntity(fatherId) instanceof CitizenEntity fe && fe.isAlive()) {
            father = fe;
            SocialEvents.linkMutualFamily(child, father);
        }

        for (Citizen c : s.citizens()) {
            if (c.entityId().equals(child.getUUID())) continue;
            if (!(sl.getEntity(c.entityId()) instanceof CitizenEntity sibling)) continue;
            if (!sibling.isAlive() || !mother.getUUID().equals(sibling.getMotherId())) continue;
            SocialEvents.linkMutualFamily(child, sibling);
        }

        mother.getThoughts().add(ThoughtKind.MY_CHILD_BORN, child.getUUID(), now, sl.random);
        mother.recomputeHappiness();
        if (father != null) {
            father.getThoughts().add(ThoughtKind.MY_CHILD_BORN, child.getUUID(), now, sl.random);
            father.recomputeHappiness();
        }

        for (Citizen c : s.citizens()) {
            if (c.entityId().equals(mother.getUUID()) || c.entityId().equals(child.getUUID())) continue;
            if (father != null && c.entityId().equals(father.getUUID())) continue;
            Entity raw = sl.getEntity(c.entityId());
            if (!(raw instanceof CitizenEntity ce)) continue;
            ce.getThoughts().add(ThoughtKind.NEW_CHILD_IN_SETTLEMENT, null, now, sl.random);
            ce.recomputeHappiness();
        }

        MinecraftServer server = sl.getServer();
        if (server != null) {
            Component msg = Component.translatable("bannerbound.birth.broadcast",
                    mother.getDisplayName(), child.getDisplayName())
                .withStyle(ChatFormatting.LIGHT_PURPLE);
            for (UUID memberId : s.members()) {
                ServerPlayer p = server.getPlayerList().getPlayer(memberId);
                if (p != null) p.sendSystemMessage(msg);
            }
        }

        SocialEvents.spawnHearts(sl, mother);

        // Clear pregnancy LAST so the chat broadcast above captures the still-pregnant display name.
        mother.setPregnant(false, -1L, null);

        SettlementData.get(sl).setDirty();
    }

    public static void broadcastGrewUp(ServerLevel sl, CitizenEntity child) {
        MinecraftServer server = sl.getServer();
        if (server == null) return;
        Settlement s = child.getSettlement();
        if (s == null) return;
        Component msg = Component.translatable("bannerbound.grewup.broadcast",
                child.getDisplayName())
            .withStyle(ChatFormatting.GRAY);
        for (UUID memberId : s.members()) {
            ServerPlayer p = server.getPlayerList().getPlayer(memberId);
            if (p != null) p.sendSystemMessage(msg);
        }
    }
}
