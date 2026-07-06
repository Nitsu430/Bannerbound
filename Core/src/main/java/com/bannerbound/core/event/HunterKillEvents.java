package com.bannerbound.core.event;

import java.util.Iterator;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.entity.BreedingEvents;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.entity.DropOffContainers;
import com.bannerbound.core.entity.HerderWorkGoal;
import com.bannerbound.core.entity.HunterWorkGoal;

import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;

/**
 * Routes hunter/herder animal kills straight into job storage: when prey dies to a CitizenEntity
 * holding the hunter job (melee blow, or an arrow/spear it owns) or the herder job, the death
 * drops are inserted into the citizen's drop-off container instead of scattering at the kill site,
 * so the hunter never hauls meat home by hand. Anything that doesn't fit stays on the ground where
 * the animal fell. Hunter meat is also tallied into the settlement's "hunting" food-production
 * stat (food the citizens later eat from storage); herder kills additionally roll a per-species
 * hide via HerderHooks, subject to never_drop datapack overrides.
 *
 * Runs at EventPriority.LOWEST -- after DropGatingEvents (LOW) has stripped items the settlement
 * doesn't know against its known-set, so only recognized drops reach the chest.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class HunterKillEvents {
    private HunterKillEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.getEntity().level().isClientSide() || event.isCanceled()) {
            return;
        }
        Entity killer = event.getSource() == null ? null : event.getSource().getEntity();
        if (!(killer instanceof CitizenEntity citizen)) {
            return;
        }
        boolean hunter = HunterWorkGoal.JOB_TYPE_ID.equals(citizen.getJobType());
        boolean herder = HerderWorkGoal.JOB_TYPE_ID.equals(citizen.getJobType())
            && event.getEntity() instanceof Animal;
        if (!hunter && !herder) {
            return;
        }
        Container depot = DropOffContainers.resolveJobDepot(citizen);
        if (depot == null) {
            return;
        }
        if (herder && event.getEntity() instanceof Animal victim) {
            ItemStack hide = com.bannerbound.core.api.herder.HerderHooks.get().herdHide(victim,
                BreedingEvents.breedChance(victim.level(), victim.blockPosition()),
                (int) citizen.getJobXp(HerderWorkGoal.JOB_TYPE_ID));
            String sourceId = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(victim.getType()).toString();
            if (!hide.isEmpty() && !isSuppressedDrop(hide, sourceId)) {
                event.getDrops().add(new ItemEntity(victim.level(), victim.getX(), victim.getY(), victim.getZ(), hide));
            }
        }
        int foodStored = 0;
        Iterator<ItemEntity> it = event.getDrops().iterator();
        while (it.hasNext()) {
            ItemEntity drop = it.next();
            ItemStack original = drop.getItem().copy();
            ItemStack remainder = DropOffContainers.insert(depot, drop.getItem());
            if (hunter && citizen.getSettlement() != null
                    && com.bannerbound.core.api.settlement.data.FoodValueLoader.base(original.getItem()) > 0f) {
                foodStored += original.getCount() - remainder.getCount();
            }
            if (remainder.isEmpty()) {
                it.remove();
            } else {
                drop.setItem(remainder);
            }
        }
        if (foodStored > 0) citizen.getSettlement().addFoodProduced("hunting", foodStored);
    }

    private static boolean isSuppressedDrop(ItemStack stack, String sourceId) {
        if (stack.isEmpty()) return false;
        net.minecraft.resources.ResourceLocation id =
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && com.bannerbound.core.api.research.data.DropOverrideLoader
            .decide(id.toString(), sourceId)
            == com.bannerbound.core.api.research.data.DropOverrideLoader.Decision.NEVER_DROP;
    }
}
