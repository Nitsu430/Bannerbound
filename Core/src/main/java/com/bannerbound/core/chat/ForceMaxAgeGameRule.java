package com.bannerbound.core.chat;

import java.util.function.BiConsumer;

import com.mojang.brigadier.context.CommandContext;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.command.EraGameRuleArgument;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameRules;

/**
 * Custom {@link GameRules.Value} backing {@code /gamerule forceMaxAge} with a Bannerbound
 * {@link Era} instead of a raw ordinal. The command argument ({@link EraGameRuleArgument}) parses
 * era names ({@code ancient}, {@code classical}, ...) plus the alias {@code none} (= the last era =
 * "no cap"), so the rule tab-completes friendly presets and serialises to {@code level.dat} as the
 * era key. The {@code forceMaxAge} key is registered in {@link BannerboundGameRules}; the cap is
 * read back through {@link com.bannerbound.core.api.research.ResearchManager#forceMaxAge}.
 *
 * <p>Constructing a bespoke {@link GameRules.Type} reaches the package-private {@code Type}
 * constructor and the {@code VisitorCaller} interface, both opened by Core's access transformer
 * ({@code META-INF/accesstransformer.cfg}); NeoForge's bundled AT only exposes the boolean/integer
 * factories.
 *
 * <p>Deserialise is tolerant: an era key, the {@code none} alias, or a legacy numeric ordinal (the
 * rule was once a plain {@code IntegerValue}) all parse, and anything unparseable falls back to the
 * last era = "no cap" rather than throwing on a corrupt {@code level.dat}.
 */
public class ForceMaxAgeGameRule extends GameRules.Value<ForceMaxAgeGameRule> {
    private Era era;

    public ForceMaxAgeGameRule(GameRules.Type<ForceMaxAgeGameRule> type, Era era) {
        super(type);
        this.era = era;
    }

    public static GameRules.Type<ForceMaxAgeGameRule> type(
            Era defaultEra, BiConsumer<MinecraftServer, ForceMaxAgeGameRule> callback) {
        return new GameRules.Type<ForceMaxAgeGameRule>(
            EraGameRuleArgument::era,
            type -> new ForceMaxAgeGameRule(type, defaultEra),
            callback,
            (visitor, key, type) -> visitor.visit(key, type));
    }

    public Era era() {
        return era;
    }

    public void set(Era era, MinecraftServer server) {
        this.era = era;
        onChanged(server);
    }

    @Override
    protected void updateFromArgument(CommandContext<CommandSourceStack> context, String name) {
        this.era = context.getArgument(name, Era.class);
    }

    @Override
    protected void deserialize(String value) {
        this.era = parse(value);
    }

    @Override
    public String serialize() {
        return era.key();
    }

    @Override
    public int getCommandResult() {
        return era.ordinal();
    }

    @Override
    protected ForceMaxAgeGameRule getSelf() {
        return this;
    }

    @Override
    protected ForceMaxAgeGameRule copy() {
        return new ForceMaxAgeGameRule(this.type, this.era);
    }

    @Override
    public void setFrom(ForceMaxAgeGameRule other, MinecraftServer server) {
        this.era = other.era;
        onChanged(server);
    }

    private static Era parse(String value) {
        Era named = EraGameRuleArgument.resolve(value);
        if (named != null) {
            return named;
        }
        Era[] vals = Era.values();
        try {
            return Era.fromOrdinalOrDefault(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return vals[vals.length - 1];
        }
    }
}
