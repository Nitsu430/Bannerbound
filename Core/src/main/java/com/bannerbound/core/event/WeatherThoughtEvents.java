package com.bannerbound.core.event;

import java.util.HashMap;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.social.ThoughtKind;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Watches each loaded server level's weather via LevelTickEvent.Post (server thread, once per level
 * per tick) and, when rain or a thunderstorm ends, stamps the matching ThoughtKind on every
 * CitizenEntity in that level.
 *
 * <p>The trigger is the TRANSITION (raining -> clear / thundering -> clear), not the ongoing
 * condition: continuous rain does not keep re-applying RECENTLY_RAINED. The thought lands once when
 * the weather lifts and decays naturally over the next 2-3 min; re-applying during the decay window
 * just refreshes the entry (the Thoughts container de-dupes by kind on solo thoughts), which is the
 * right behaviour for back-to-back showers.
 *
 * <p>Rain-ended and thunder-ended are tracked independently and can both fire on one tick. Because
 * isThundering implies isRaining in vanilla, a thunder->clear transition that also ends rain stamps
 * BOTH thoughts, which is intended (it stormed AND it rained).
 *
 * <p>Per-dimension state lives in a static map keyed by the level's ResourceKey so Overworld and
 * Nether do not share weather (they do not in vanilla either).
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class WeatherThoughtEvents {
    private record WeatherSnapshot(boolean raining, boolean thundering) {}

    private static final Map<ResourceKey<Level>, WeatherSnapshot> LAST = new HashMap<>();

    private WeatherThoughtEvents() {}

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel sl)) return;

        boolean rainingNow = sl.isRaining();
        boolean thunderingNow = sl.isThundering();
        WeatherSnapshot prev = LAST.get(sl.dimension());

        if (prev == null) {
            LAST.put(sl.dimension(), new WeatherSnapshot(rainingNow, thunderingNow));
            return;
        }

        boolean rainEnded = prev.raining() && !rainingNow;
        boolean thunderEnded = prev.thundering() && !thunderingNow;

        if (rainEnded || thunderEnded) {
            long now = sl.getGameTime();
            for (Entity e : sl.getAllEntities()) {
                if (!(e instanceof CitizenEntity c)) continue;
                if (rainEnded) c.getThoughts().add(ThoughtKind.RECENTLY_RAINED, null, now, sl.random);
                if (thunderEnded) c.getThoughts().add(ThoughtKind.RECENTLY_STORMED, null, now, sl.random);
                c.recomputeHappiness();
            }
        }

        LAST.put(sl.dimension(), new WeatherSnapshot(rainingNow, thunderingNow));
    }
}
