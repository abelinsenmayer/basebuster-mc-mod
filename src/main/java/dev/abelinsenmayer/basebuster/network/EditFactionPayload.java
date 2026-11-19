package dev.abelinsenmayer.basebuster.network;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent from the faction settings screen (the flag's right-click GUI) when a
 * member of the owning team clicks Save. The server re-checks that the flag
 * at {@link #flagPos} is owned by the sender's team -- ownership can change
 * while the screen sits open -- then applies the options that map to vanilla
 * team properties. Team names can't change here (scoreboard teams can't be
 * renamed), so the payload carries no name: the flag's owning team is the
 * authority. The screen gets a {@link CreateFactionResultPayload} either
 * way, and every online member of the team is told who saved.
 *
 * <p>{@link Options#allowUninvited} has no vanilla team backing yet; it rides
 * along to keep the packet format stable, as in {@link CreateFactionPayload}.
 */
public record EditFactionPayload(BlockPos flagPos, Options options)
        implements CustomPacketPayload {

    // StreamCodec.composite tops out at six fields, so the five team settings
    // ride inside a nested record
    public record Options(int colorIndex, boolean allowFriendlyFire, boolean hideNametags,
            boolean showNamePrefix, boolean allowUninvited) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Options> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Options::colorIndex,
                        ByteBufCodecs.BOOL, Options::allowFriendlyFire,
                        ByteBufCodecs.BOOL, Options::hideNametags,
                        ByteBufCodecs.BOOL, Options::showNamePrefix,
                        ByteBufCodecs.BOOL, Options::allowUninvited,
                        Options::new);
    }

    public static final Type<EditFactionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BaseBuster.MODID, "edit_faction"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EditFactionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, EditFactionPayload::flagPos,
                    Options.STREAM_CODEC, EditFactionPayload::options,
                    EditFactionPayload::new);

    /** Convenience constructor taking the settings flat, matching the screen's field order. */
    public EditFactionPayload(BlockPos flagPos, int colorIndex,
            boolean allowFriendlyFire, boolean hideNametags, boolean showNamePrefix,
            boolean allowUninvited) {
        this(flagPos, new Options(colorIndex, allowFriendlyFire,
                hideNametags, showNamePrefix, allowUninvited));
    }

    @Override
    public Type<EditFactionPayload> type() {
        return TYPE;
    }

    private static void reject(IPayloadContext context, String translationKey) {
        context.reply(new CreateFactionResultPayload(false, Component.translatable(translationKey)));
    }

    /** Server-side handler; NeoForge runs payload handlers on the main thread. */
    public static void handle(EditFactionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        Options options = payload.options();
        if (options.colorIndex() < 0
                || options.colorIndex() >= CreateFactionPayload.SELECTABLE_COLORS.size()) {
            reject(context, "gui.basebuster.faction_pledge.name_invalid");
            return;
        }

        ServerLevel level = player.serverLevel();
        // Only a member of the team that owns this flag may edit the faction
        if (!(level.getBlockEntity(payload.flagPos()) instanceof BaseFlagBlockEntity flagEntity)
                || !flagEntity.isOwnedBy(player)
                || flagEntity.getOwnerTeam() == null) {
            reject(context, "gui.basebuster.faction_pledge.not_member");
            return;
        }
        PlayerTeam team = flagEntity.getOwnerTeam();
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        team.setColor(CreateFactionPayload.SELECTABLE_COLORS.get(options.colorIndex()));
        team.setAllowFriendlyFire(options.allowFriendlyFire());
        team.setNameTagVisibility(options.hideNametags()
                ? Team.Visibility.HIDE_FOR_OTHER_TEAMS
                : Team.Visibility.ALWAYS);
        team.setPlayerPrefix(options.showNamePrefix()
                ? Component.literal("[" + team.getName() + "] ")
                : Component.empty());

        Component announcement = Component.translatable(
                "gui.basebuster.faction_pledge.updated_broadcast", player.getDisplayName());
        for (String memberName : team.getPlayers()) {
            ServerPlayer member = server.getPlayerList().getPlayerByName(memberName);
            if (member != null) {
                member.sendSystemMessage(announcement);
            }
        }
        context.reply(new CreateFactionResultPayload(true,
                Component.translatable("gui.basebuster.faction_pledge.updated", team.getName())));
    }
}
