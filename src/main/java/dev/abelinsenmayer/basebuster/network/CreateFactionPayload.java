package dev.abelinsenmayer.basebuster.network;

import dev.abelinsenmayer.basebuster.BaseBuster;
import java.util.Arrays;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent from the faction pledge screen when the player clicks Create Faction.
 * The server validates the name, creates the scoreboard team, applies the
 * options that map to vanilla team properties, and replies with a
 * {@link CreateFactionResultPayload}.
 */
public record CreateFactionPayload(String name, int colorIndex, boolean allowFriendlyFire,
        boolean hideNametags, boolean showNamePrefix)
        implements CustomPacketPayload {

    // Vanilla imposes no team-name length limit in this version; the GUI caps at 50
    public static final int MAX_NAME_LENGTH = 50;

    public static final Type<CreateFactionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BaseBuster.MODID, "create_faction"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CreateFactionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), CreateFactionPayload::name,
                    ByteBufCodecs.VAR_INT, CreateFactionPayload::colorIndex,
                    ByteBufCodecs.BOOL, CreateFactionPayload::allowFriendlyFire,
                    ByteBufCodecs.BOOL, CreateFactionPayload::hideNametags,
                    ByteBufCodecs.BOOL, CreateFactionPayload::showNamePrefix,
                    CreateFactionPayload::new);

    // Team colors are ChatFormatting; its 16 color formattings are exactly
    // the assignable set. The GUI swatches index this same list and show each
    // entry's RGB, so the picked color is what appears in chat and nametags
    public static final List<ChatFormatting> SELECTABLE_COLORS =
            Arrays.stream(ChatFormatting.values()).filter(ChatFormatting::isColor).toList();

    @Override
    public Type<CreateFactionPayload> type() {
        return TYPE;
    }

    /** Server-side handler; NeoForge runs payload handlers on the main thread. */
    public static void handle(CreateFactionPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        String name = payload.name().trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH
                || payload.colorIndex() < 0 || payload.colorIndex() >= SELECTABLE_COLORS.size()) {
            context.reply(new CreateFactionResultPayload(false,
                    Component.translatable("gui.basebuster.faction_pledge.name_invalid")));
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        if (scoreboard.getPlayerTeam(name) != null) {
            context.reply(new CreateFactionResultPayload(false,
                    Component.translatable("gui.basebuster.faction_pledge.name_taken")));
            return;
        }

        PlayerTeam team = scoreboard.addPlayerTeam(name);
        team.setDisplayName(Component.literal(name));
        team.setColor(SELECTABLE_COLORS.get(payload.colorIndex()));
        team.setAllowFriendlyFire(payload.allowFriendlyFire());
        team.setNameTagVisibility(payload.hideNametags()
                ? Team.Visibility.HIDE_FOR_OTHER_TEAMS
                : Team.Visibility.ALWAYS);
        if (payload.showNamePrefix()) {
            team.setPlayerPrefix(Component.literal("[" + name + "] "));
        }
        // The creator is not joined automatically -- the pledge in hand
        // becomes bound to the new faction so right-clicking it joins later
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (held.is(BaseBuster.FACTION_PLEDGE.get())) {
                held.set(BaseBuster.FACTION.get(), name);
            }
        }
        player.sendSystemMessage(
                Component.translatable("gui.basebuster.faction_pledge.created", name));
        context.reply(new CreateFactionResultPayload(true,
                Component.translatable("gui.basebuster.faction_pledge.created", name)));
    }
}
