package com.bannerbound.core.creative;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * Adds Create-Aeronautics-style labelled "sections" to any creative-mode tab without touching that
 * tab's item list: the tab's displayItems generator stays the single source of truth for WHICH items
 * appear, and this class only re-orders the already-built list into labelled bands separated by blank
 * "banner" rows. Items not assigned to any section are kept and rendered first, ungrouped, so a
 * forgotten item can never silently vanish from a tab.
 *
 * How the pieces fit: mods register sections + item membership via forTab (common-side, at mod init).
 * CreativeModeTabMixin calls layout() at the tail of buildContents and swaps in the re-ordered list
 * (with ItemStack.EMPTY spacer rows) -- the tab's field must be replaced wholesale because vanilla's
 * backing set dedupes and rejects empties. CreativeModeInventoryScreenMixin draws the banners over
 * the blank rows at render-tail using TabSections.bannerRow and currentRow; CreativeItemPickerMenuMixin
 * keeps currentRow in sync with the scrollbar. currentRow is client-only state written by the
 * scrollbar and read by the renderer.
 */
public final class CreativeSections {

    private CreativeSections() {}

    public static final int COLUMNS = 9;
    public static final int VISIBLE_ROWS = 5;

    public static int currentRow = 0;

    private static final Map<Supplier<CreativeModeTab>, TabSections> REGISTERED = new LinkedHashMap<>();
    private static final Map<CreativeModeTab, TabSections> RESOLVED = new IdentityHashMap<>();
    private static final TabSections ABSENT = new TabSections();

    public static final class TabSections {
        final List<CreativeSection> order = new ArrayList<>();
        final Map<Item, CreativeSection> itemToSection = new IdentityHashMap<>();
        final Map<CreativeSection, Integer> bannerRows = new IdentityHashMap<>();

        public List<CreativeSection> order() {
            return order;
        }

        public int bannerRow(CreativeSection section) {
            return bannerRows.getOrDefault(section, -1);
        }
    }

    public static Builder forTab(Supplier<CreativeModeTab> tab) {
        return new Builder(REGISTERED.computeIfAbsent(tab, t -> new TabSections()));
    }

    public static final class Builder {
        private final TabSections ts;
        private CreativeSection current;

        private Builder(TabSections ts) {
            this.ts = ts;
        }

        public Builder section(CreativeSection section) {
            ts.order.add(section);
            this.current = section;
            return this;
        }

        @SafeVarargs
        public final Builder add(Supplier<? extends ItemLike>... items) {
            for (Supplier<? extends ItemLike> sup : items) {
                ItemLike like = sup.get();
                if (like != null) {
                    ts.itemToSection.put(like.asItem(), current);
                }
            }
            return this;
        }

        public Builder add(Collection<? extends Supplier<? extends ItemLike>> items) {
            for (Supplier<? extends ItemLike> sup : items) {
                ItemLike like = sup.get();
                if (like != null) {
                    ts.itemToSection.put(like.asItem(), current);
                }
            }
            return this;
        }

        public Builder addItems(ItemLike... items) {
            for (ItemLike like : items) {
                ts.itemToSection.put(like.asItem(), current);
            }
            return this;
        }
    }

    public static TabSections forResolvedTab(CreativeModeTab tab) {
        TabSections cached = RESOLVED.get(tab);
        if (cached != null) {
            return cached == ABSENT ? null : cached;
        }
        TabSections found = ABSENT;
        for (Map.Entry<Supplier<CreativeModeTab>, TabSections> e : REGISTERED.entrySet()) {
            CreativeModeTab resolved;
            try {
                resolved = e.getKey().get();
            } catch (RuntimeException ex) {
                continue;
            }
            if (resolved == tab) {
                found = e.getValue();
                break;
            }
        }
        RESOLVED.put(tab, found);
        return found == ABSENT ? null : found;
    }

    public static final class Built {
        public final List<ItemStack> display;
        public final Set<ItemStack> search;

        Built(List<ItemStack> display, Set<ItemStack> search) {
            this.display = display;
            this.search = search;
        }
    }

    public static Built layout(TabSections ts, Collection<ItemStack> original) {
        Map<CreativeSection, List<ItemStack>> grouped = new IdentityHashMap<>();
        List<ItemStack> unassigned = new ArrayList<>();
        for (ItemStack stack : original) {
            CreativeSection s = ts.itemToSection.get(stack.getItem());
            (s == null ? unassigned : grouped.computeIfAbsent(s, k -> new ArrayList<>())).add(stack);
        }

        List<ItemStack> display = new LinkedList<>();
        Set<ItemStack> search = new LinkedHashSet<>();
        ts.bannerRows.clear();

        if (!unassigned.isEmpty()) {
            for (ItemStack st : unassigned) {
                display.add(st);
                search.add(st);
            }
            padToRow(display);
        }

        for (CreativeSection s : ts.order) {
            List<ItemStack> items = grouped.get(s);
            if (items == null || items.isEmpty()) {
                continue;
            }
            // Relies on the list being row-aligned here (padToRow after every band); otherwise banners land on the wrong row.
            int bannerRow = display.size() / COLUMNS;
            for (int i = 0; i < COLUMNS; i++) {
                display.add(ItemStack.EMPTY);
            }
            ts.bannerRows.put(s, bannerRow);
            for (ItemStack st : items) {
                display.add(st);
                search.add(st);
            }
            padToRow(display);
        }
        return new Built(display, search);
    }

    private static void padToRow(List<ItemStack> display) {
        int rem = display.size() % COLUMNS;
        if (rem != 0) {
            for (int i = rem; i < COLUMNS; i++) {
                display.add(ItemStack.EMPTY);
            }
        }
    }
}
