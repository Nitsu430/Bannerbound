package com.bannerbound.core.network;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client snapshot of every datum the town-hall screen renders; sent once per town-hall
 * open and re-broadcast after any vote or state change that affects the buttons. The government
 * block drives the Code-of-Laws workflow: with governmentChoiceWindowOpen true and governmentOrdinal
 * 0 (NONE) the screen shows the "Choose Government" button, and the vote tallies plus the per-player
 * pick render live progress. onlineMembers (NOT totalMembers) is the vote-threshold denominator -
 * offline players forfeit their vote, by design.
 *
 * <p>Field conventions the client depends on: playerGovernmentVote is 0=none / 1=council /
 * 2=chiefdom; chiefCandidates / chiefCandidateNames / chiefCandidateVotes are parallel;
 * playerChiefNomination is the all-zero UUID when the player has not nominated. playerIsChief and
 * playerIsRegent are UI hints only - the server re-checks every gate independently. A Regent is a
 * stand-in while the chief is offline: routine authority (may start Research) but not weighty
 * authority (Disband / Expand stay greyed). chiefStepDownReadyTick is -1 when not applicable and
 * leaveReadyTick is 0 when already free; both are absolute game ticks the screen counts down against
 * the client's synced level.getGameTime(). identityRgbs are 0xRRGGBB, most-present dye first, never
 * empty (founding-rgb fallback resolved server-side), driving the name color and accent gradients.
 */
@ApiStatus.Internal
public record OpenTownHallScreenPayload(
    String settlementName,
    int colorOrdinal,
    int eraOrdinal,
    int tabletsIssued,
    int tabletCapacity,
    int disbandVoteCount,
    int disbandTotalMembers,
    boolean playerHasVotedToDisband,
    boolean disbandVoteActive,
    int governmentOrdinal,
    boolean codeOfLawsPromptShown,
    boolean governmentChoiceWindowOpen,
    boolean governmentVoteActive,
    int councilVoteCount,
    int chiefdomVoteCount,
    int onlineMembers,
    int playerGovernmentVote,
    boolean chiefdomElectionActive,
    List<UUID> chiefCandidates,
    List<String> chiefCandidateNames,
    List<Integer> chiefCandidateVotes,
    UUID playerChiefNomination,
    boolean playerIsChief,
    boolean playerIsRegent,
    long chiefStepDownReadyTick,
    long leaveReadyTick,
    List<Integer> identityRgbs
) implements CustomPacketPayload {

    private static final StreamCodec<ByteBuf, List<Integer>> INT_LIST =
        ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list());
    public static final CustomPacketPayload.Type<OpenTownHallScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_townhall_screen"));

    public static final StreamCodec<ByteBuf, OpenTownHallScreenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, p.settlementName());
            ByteBufCodecs.VAR_INT.encode(buf, p.colorOrdinal());
            ByteBufCodecs.VAR_INT.encode(buf, p.eraOrdinal());
            ByteBufCodecs.VAR_INT.encode(buf, p.tabletsIssued());
            ByteBufCodecs.VAR_INT.encode(buf, p.tabletCapacity());
            ByteBufCodecs.VAR_INT.encode(buf, p.disbandVoteCount());
            ByteBufCodecs.VAR_INT.encode(buf, p.disbandTotalMembers());
            buf.writeBoolean(p.playerHasVotedToDisband());
            buf.writeBoolean(p.disbandVoteActive());
            ByteBufCodecs.VAR_INT.encode(buf, p.governmentOrdinal());
            buf.writeBoolean(p.codeOfLawsPromptShown());
            buf.writeBoolean(p.governmentChoiceWindowOpen());
            buf.writeBoolean(p.governmentVoteActive());
            ByteBufCodecs.VAR_INT.encode(buf, p.councilVoteCount());
            ByteBufCodecs.VAR_INT.encode(buf, p.chiefdomVoteCount());
            ByteBufCodecs.VAR_INT.encode(buf, p.onlineMembers());
            ByteBufCodecs.VAR_INT.encode(buf, p.playerGovernmentVote());
            buf.writeBoolean(p.chiefdomElectionActive());
            ByteBufCodecs.VAR_INT.encode(buf, p.chiefCandidates().size());
            for (int i = 0; i < p.chiefCandidates().size(); i++) {
                UUIDUtil.STREAM_CODEC.encode(buf, p.chiefCandidates().get(i));
                ByteBufCodecs.STRING_UTF8.encode(buf, p.chiefCandidateNames().get(i));
                ByteBufCodecs.VAR_INT.encode(buf, p.chiefCandidateVotes().get(i));
            }
            UUIDUtil.STREAM_CODEC.encode(buf, p.playerChiefNomination());
            buf.writeBoolean(p.playerIsChief());
            buf.writeBoolean(p.playerIsRegent());
            buf.writeLong(p.chiefStepDownReadyTick());
            buf.writeLong(p.leaveReadyTick());
            INT_LIST.encode(buf, p.identityRgbs());
        },
        buf -> {
            String settlementName = ByteBufCodecs.STRING_UTF8.decode(buf);
            int colorOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
            int eraOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
            int tabletsIssued = ByteBufCodecs.VAR_INT.decode(buf);
            int tabletCapacity = ByteBufCodecs.VAR_INT.decode(buf);
            int disbandVoteCount = ByteBufCodecs.VAR_INT.decode(buf);
            int disbandTotalMembers = ByteBufCodecs.VAR_INT.decode(buf);
            boolean playerHasVotedToDisband = buf.readBoolean();
            boolean disbandVoteActive = buf.readBoolean();
            int governmentOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
            boolean codeOfLawsPromptShown = buf.readBoolean();
            boolean governmentChoiceWindowOpen = buf.readBoolean();
            boolean governmentVoteActive = buf.readBoolean();
            int councilVoteCount = ByteBufCodecs.VAR_INT.decode(buf);
            int chiefdomVoteCount = ByteBufCodecs.VAR_INT.decode(buf);
            int onlineMembers = ByteBufCodecs.VAR_INT.decode(buf);
            int playerGovernmentVote = ByteBufCodecs.VAR_INT.decode(buf);
            boolean chiefdomElectionActive = buf.readBoolean();
            int candidateCount = ByteBufCodecs.VAR_INT.decode(buf);
            java.util.ArrayList<UUID> chiefCandidates = new java.util.ArrayList<>(candidateCount);
            java.util.ArrayList<String> chiefCandidateNames = new java.util.ArrayList<>(candidateCount);
            java.util.ArrayList<Integer> chiefCandidateVotes = new java.util.ArrayList<>(candidateCount);
            for (int i = 0; i < candidateCount; i++) {
                chiefCandidates.add(UUIDUtil.STREAM_CODEC.decode(buf));
                chiefCandidateNames.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                chiefCandidateVotes.add(ByteBufCodecs.VAR_INT.decode(buf));
            }
            UUID playerChiefNomination = UUIDUtil.STREAM_CODEC.decode(buf);
            boolean playerIsChief = buf.readBoolean();
            boolean playerIsRegent = buf.readBoolean();
            long chiefStepDownReadyTick = buf.readLong();
            long leaveReadyTick = buf.readLong();
            List<Integer> identityRgbs = INT_LIST.decode(buf);
            return new OpenTownHallScreenPayload(
                settlementName, colorOrdinal, eraOrdinal, tabletsIssued, tabletCapacity,
                disbandVoteCount, disbandTotalMembers, playerHasVotedToDisband, disbandVoteActive,
                governmentOrdinal, codeOfLawsPromptShown, governmentChoiceWindowOpen,
                governmentVoteActive, councilVoteCount, chiefdomVoteCount, onlineMembers,
                playerGovernmentVote, chiefdomElectionActive,
                chiefCandidates, chiefCandidateNames, chiefCandidateVotes, playerChiefNomination,
                playerIsChief, playerIsRegent, chiefStepDownReadyTick, leaveReadyTick,
                identityRgbs);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
