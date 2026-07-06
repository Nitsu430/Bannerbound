package com.bannerbound.antiquity.workshop;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.ClayTankBlockEntity;
import com.bannerbound.antiquity.block.entity.TanningRackBlockEntity;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkExecutor;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.bannerbound.antiquity.craft.Tannery;

/**
 * NPC driver for the Tannery workshop (staffed by the generic Crafter). Whenever leather is wanted
 * (an order or a min-stock deficit) it walks the leather chain FORWARD as many small crafts, each
 * with its own walk + delay so the tanner visibly works the stations instead of teleporting a tank
 * to "done": FILL (scoop water into the fired clay bucket at open water), POUR (empty it into the
 * clay tank), LIME (work quicklime in -> curing liquid), CURE (dunk a scraped hide -> cured hide),
 * SCRAPE (raw hide -> scraped hides at the rack), DRY-PLACE (lay a cured hide on the rack) and
 * DRY-TAKE (lift the finished leather off). Steps are discriminated purely by craft shape
 * (result/input items) - LIME and DRY-PLACE are both resultless and are told apart by their input -
 * so any new step must keep the shapes disjoint. DRY-TAKE is deliberately UNGATED by demand
 * (rule 1 of the waiting-stage contract, see Workshops.wantsAnother): the leather already exists
 * and leaving it jams the rack slot. Every other step gates on NET demand (wantsAnother minus
 * TanneryWorkshopRules.leatherInProgress, i.e. hides already drying), else a single order would
 * lay a second hide while the first is mid-dry. FILL/LIME only start once quicklime is stocked so
 * the tanner never fills a tank it cannot finish charging (the hide would sit in plain water
 * forever). Drying itself runs on the rack block entity's own ~60s timer (the SAME one player
 * drying uses); the tanner lays the hide, walks off to other work, and returns for DRY-TAKE. The
 * NPC bulk path scrapes at a fixed yield (NPC_SCRAPE_YIELD) - the player minigame is what realizes
 * hide quality as scraped quantity; the NPC tracks no per-stack quality. The fired clay bucket is
 * a conserved tool cycling empty <-> water-filled, never consumed; both variants are retained in
 * the workshop and the stocker is only asked for one (per tank base, potters' kiln output) when
 * neither variant is on hand. The clay tank cooperation mirrors PotterExecutor's rack <-> kiln
 * pattern. Per-step tick constants are base durations before the goal's skill-speed scaling.
 */
public class TanneryExecutor implements WorkExecutor {
    private static final int BEATS = 3;
    private static final int NPC_SCRAPE_YIELD = 2;

    private static final int FILL_TICKS = 40;
    private static final int POUR_TICKS = 40;
    private static final int LIME_TICKS = 60;
    private static final int CURE_TICKS = 60;
    private static final int SCRAPE_TICKS = 60;
    private static final int RACK_HANDLE_TICKS = 30;

    @Nullable
    @Override
    public Craft chooseCraft(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        TanningRackBlockEntity rack = sl.getBlockEntity(workBlock) instanceof TanningRackBlockEntity r ? r : null;
        TanningRackBlockEntity.Phase rackPhase = rack == null ? null : rack.getPhase();
        // DRY-TAKE stays demand-UNGATED: the leather already exists; gating it jams the rack slot.
        if (rack != null && rack.isDry()) {
            return new Craft(List.of(), new ItemStack(Items.LEATHER), RACK_HANDLE_TICKS, 1);
        }
        ItemStack leather = new ItemStack(Items.LEATHER);
        int inProgress = TanneryWorkshopRules.leatherInProgress(sl, workshop);
        if (!Workshops.wantsAnother(sl, settlement, workshop, leather, inProgress)) return null;
        if (rackPhase == TanningRackBlockEntity.Phase.EMPTY
                && WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CURED_HIDE.get()) > 0) {
            return new Craft(List.of(new ItemStack(BannerboundAntiquity.CURED_HIDE.get())),
                ItemStack.EMPTY, RACK_HANDLE_TICKS, BEATS);
        }
        boolean hasScraped = WorkshopStorage.count(sl, workshop, BannerboundAntiquity.SCRAPED_HIDE.get()) > 0;
        if (hasScraped) {
            ClayTankBlockEntity tank = TanneryWorkshopRules.findTank(sl, workshop);
            ClayTankBlockEntity.LiquidType liquid = tank == null ? null : tank.getLiquid();
            boolean hasLime = WorkshopStorage.count(sl, workshop, BannerboundAntiquity.QUICKLIME.get()) > 0;
            if (TanneryWorkshopRules.hasCuring(sl, workshop)) {
                return new Craft(List.of(new ItemStack(BannerboundAntiquity.SCRAPED_HIDE.get())),
                    new ItemStack(BannerboundAntiquity.CURED_HIDE.get()), CURE_TICKS, BEATS);
            }
            if (liquid == ClayTankBlockEntity.LiquidType.WATER && hasLime) {
                return new Craft(List.of(new ItemStack(BannerboundAntiquity.QUICKLIME.get())),
                    ItemStack.EMPTY, LIME_TICKS, BEATS);
            }
            if (liquid == ClayTankBlockEntity.LiquidType.EMPTY) {
                boolean hasFilled = WorkshopStorage.count(sl, workshop,
                    BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get()) > 0;
                if (hasFilled) {
                    return new Craft(List.of(new ItemStack(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get())),
                        new ItemStack(BannerboundAntiquity.CLAY_FIRED_BUCKET.get()), POUR_TICKS, BEATS);
                }
                if (hasLime
                    && WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CLAY_FIRED_BUCKET.get()) > 0
                    && tank != null && TanneryWorkshopRules.findWaterSource(sl, tank) != null) {
                    return new Craft(List.of(new ItemStack(BannerboundAntiquity.CLAY_FIRED_BUCKET.get())),
                        new ItemStack(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get()), FILL_TICKS, BEATS);
                }
            }
        }
        if (rackPhase == TanningRackBlockEntity.Phase.EMPTY) {
            Item rawHide = firstRawHide(sl, workshop);
            if (rawHide != null) {
                return new Craft(List.of(new ItemStack(rawHide)),
                    new ItemStack(BannerboundAntiquity.SCRAPED_HIDE.get(), NPC_SCRAPE_YIELD), SCRAPE_TICKS, BEATS);
            }
        }
        return null;
    }

    private static boolean hasInput(Craft craft, Item item) {
        for (ItemStack in : craft.inputs()) {
            if (in.is(item)) return true;
        }
        return false;
    }

    private static boolean isFill(Craft craft) {
        return craft.result().is(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get());
    }

    private static boolean isPour(Craft craft) {
        return craft.result().is(BannerboundAntiquity.CLAY_FIRED_BUCKET.get());
    }

    private static boolean isLime(Craft craft) {
        return craft.result().isEmpty() && hasInput(craft, BannerboundAntiquity.QUICKLIME.get());
    }

    private static boolean isDryPlace(Craft craft) {
        return craft.result().isEmpty() && hasInput(craft, BannerboundAntiquity.CURED_HIDE.get());
    }

    private static boolean isDryTake(Craft craft) {
        return craft.result().is(Items.LEATHER) && craft.inputs().isEmpty();
    }

    private static boolean isScrape(Craft craft) {
        return craft.result().is(BannerboundAntiquity.SCRAPED_HIDE.get());
    }

    private static boolean isCure(Craft craft) {
        return craft.result().is(BannerboundAntiquity.CURED_HIDE.get());
    }

    @Override
    public BlockPos workTarget(ServerLevel sl, Settlement settlement, Workshop workshop,
                               BlockPos workBlock, Craft craft) {
        if (isPour(craft) || isLime(craft) || isCure(craft) || isFill(craft)) {
            ClayTankBlockEntity tank = TanneryWorkshopRules.findTank(sl, workshop);
            if (tank == null) return workBlock;
            if (isFill(craft)) {
                BlockPos water = TanneryWorkshopRules.findWaterSource(sl, tank);
                return water != null ? water : tank.getBlockPos();
            }
            return tank.getBlockPos();
        }
        return workBlock;
    }

    @Override
    public void onStart(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isScrape(craft) && sl.getBlockEntity(workBlock) instanceof TanningRackBlockEntity rack
                && rack.getPhase() == TanningRackBlockEntity.Phase.EMPTY && !craft.inputs().isEmpty()) {
            rack.placeRaw(craft.inputs().get(0));
        }
    }

    @Override
    public void onAbort(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        // The goal returns the withdrawn raw hide to storage; clear the rack visual to match.
        if (isScrape(craft) && sl.getBlockEntity(workBlock) instanceof TanningRackBlockEntity rack
                && rack.isRaw()) {
            rack.clear();
        }
    }

    @Override
    public ItemStack finish(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isPour(craft) || isLime(craft) || isCure(craft)) {
            Settlement s = citizen.getSettlement();
            Workshop w = s == null ? null : s.getWorkshop(citizen.getAssignedWorkshopId());
            ClayTankBlockEntity tank = w == null ? null : TanneryWorkshopRules.findTank(sl, w);
            if (tank != null) {
                if (isPour(craft)) {
                    tank.fillWater();
                } else if (isLime(craft)) {
                    tank.convertToCuring();
                } else {
                    tank.drawCuring();
                }
            }
        } else if (sl.getBlockEntity(workBlock) instanceof TanningRackBlockEntity rack) {
            if (isScrape(craft)) {
                rack.clear();
            } else if (isDryPlace(craft)) {
                rack.placeCured();
            } else if (isDryTake(craft)) {
                rack.clear();
            }
        }
        return craft.result().copy();
    }

    @Override
    public void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft, int beatIndex) {
        BlockPos at = citizen.blockPosition();
        if (isFill(craft)) {
            sl.playSound(null, at, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.6F, 1.0F);
            splash(sl, citizen, 6);
        } else if (isPour(craft)) {
            sl.playSound(null, at, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.7F, 0.9F);
            splash(sl, citizen, 8);
        } else if (isLime(craft)) {
            sl.playSound(null, at, SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.6F, 1.1F);
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.WHITE_SMOKE,
                citizen.getX(), citizen.getY() + 1.0, citizen.getZ(), 6, 0.2, 0.1, 0.2, 0.0);
        } else if (isCure(craft)) {
            sl.playSound(null, at, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.6F, 1.1F);
            splash(sl, citizen, 8);
        } else if (isScrape(craft)) {
            sl.playSound(null, workBlock, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.5F, 1.0F);
        } else {
            sl.playSound(null, workBlock, SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 0.5F, 1.0F);
        }
    }

    private static void splash(ServerLevel sl, CitizenEntity citizen, int count) {
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH,
            citizen.getX(), citizen.getY() + 0.8, citizen.getZ(), count, 0.25, 0.1, 0.25, 0.0);
    }

    @Override
    public List<ItemStack> possibleOutputs(ServerLevel sl, BlockPos workBlock) {
        return List.of(new ItemStack(Items.LEATHER),
            new ItemStack(BannerboundAntiquity.SCRAPED_HIDE.get()),
            new ItemStack(BannerboundAntiquity.CURED_HIDE.get()));
    }

    @Override
    public Set<Item> retainedItems(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        // Both bucket variants must stay retained: a filled bucket mid-fetch is not surplus.
        return Set.of(
            BannerboundAntiquity.COW_HIDE.get(), BannerboundAntiquity.SHEEP_HIDE.get(),
            BannerboundAntiquity.PIG_HIDE.get(), BannerboundAntiquity.GOAT_HIDE.get(),
            BannerboundAntiquity.HORSE_HIDE.get(), BannerboundAntiquity.SCRAPED_HIDE.get(),
            BannerboundAntiquity.CURED_HIDE.get(), BannerboundAntiquity.QUICKLIME.get(),
            BannerboundAntiquity.CLAY_FIRED_BUCKET.get(),
            BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get());
    }

    @Override
    public List<ItemStack> missingInputs(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        List<ItemStack> out = new java.util.ArrayList<>();
        if (!leatherWanted(sl, settlement, workshop)) return out;
        addDeficit(out, sl, workshop, BannerboundAntiquity.QUICKLIME.get(), 4);
        for (Item hide : RAW_HIDES) {
            addDeficit(out, sl, workshop, hide, 4);
        }
        // The bucket is conserved: count BOTH variants or the stocker delivers a new one every fill trip.
        int tankBases = TanneryWorkshopRules.countTankBases(sl, workshop);
        if (tankBases > 0) {
            int buckets = WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CLAY_FIRED_BUCKET.get())
                + WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get());
            if (buckets < tankBases) {
                out.add(new ItemStack(BannerboundAntiquity.CLAY_FIRED_BUCKET.get(), tankBases - buckets));
            }
        }
        return out;
    }

    private static void addDeficit(List<ItemStack> out, ServerLevel sl, Workshop workshop, Item item, int buffer) {
        int have = WorkshopStorage.count(sl, workshop, item);
        if (have < buffer) out.add(new ItemStack(item, buffer - have));
    }

    private static final Item[] RAW_HIDES = {
        BannerboundAntiquity.COW_HIDE.get(), BannerboundAntiquity.SHEEP_HIDE.get(),
        BannerboundAntiquity.PIG_HIDE.get(), BannerboundAntiquity.GOAT_HIDE.get(),
        BannerboundAntiquity.HORSE_HIDE.get()
    };

    private static boolean leatherWanted(ServerLevel sl, Settlement settlement, Workshop workshop) {
        if (Workshops.orderedCraftCount(workshop, Items.LEATHER) > 0) return true;
        return Workshops.wantedByMinStock(sl, settlement, workshop, new ItemStack(Items.LEATHER));
    }

    @Nullable
    private static Item firstRawHide(ServerLevel sl, Workshop workshop) {
        Item[] hides = {
            BannerboundAntiquity.COW_HIDE.get(), BannerboundAntiquity.SHEEP_HIDE.get(),
            BannerboundAntiquity.PIG_HIDE.get(), BannerboundAntiquity.GOAT_HIDE.get(),
            BannerboundAntiquity.HORSE_HIDE.get()
        };
        for (Item hide : hides) {
            if (WorkshopStorage.count(sl, workshop, hide) > 0) return hide;
        }
        return null;
    }
}
