package dev.abelinsenmayer.basebuster.network;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
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
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent from the flag's base management tab when a member of the owning team
 * clicks Save. The server re-checks that the flag at {@link #flagPos} is owned
 * by the sender's team -- ownership can change while the screen sits open --
 * then applies the base name (rejected when another base on the same team
 * already has it) and the protection-border visibility. The screen gets a
 * {@link CreateFactionResultPayload} either way: success closes it, failure
 * shows the message in red.
 */
public record EditBasePayload(BlockPos flagPos, String baseName, boolean showBorder)
        implements CustomPacketPayload {

    // Bases get the same 50-char cap as faction names
    public static final int MAX_NAME_LENGTH = CreateFactionPayload.MAX_NAME_LENGTH;

    public static final Type<EditBasePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BaseBuster.MODID, "edit_base"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EditBasePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, EditBasePayload::flagPos,
                    ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), EditBasePayload::baseName,
                    ByteBufCodecs.BOOL, EditBasePayload::showBorder,
                    EditBasePayload::new);

    @Override
    public Type<EditBasePayload> type() {
        return TYPE;
    }

    private static void reject(IPayloadContext context, String translationKey, Object... args) {
        context.reply(new CreateFactionResultPayload(false, Component.translatable(translationKey, args)));
    }

    /** Server-side handler; NeoForge runs payload handlers on the main thread. */
    public static void handle(EditBasePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        String name = payload.baseName().trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            reject(context, "gui.basebuster.base_management.name_invalid");
            return;
        }

        ServerLevel level = player.serverLevel();
        MinecraftServer server = player.getServer();
        // Only a member of the team that owns this flag may edit the base
        if (server == null
                || !(level.getBlockEntity(payload.flagPos()) instanceof BaseFlagBlockEntity flagEntity)
                || !flagEntity.isOwnedBy(player)
                || flagEntity.getOwnerTeamName() == null) {
            reject(context, "gui.basebuster.base_management.not_member");
            return;
        }

        // Base names are unique within the owning team; the flag being renamed
        // is exempted from the scan so it can't collide with itself
        if (FlagsSavedData.isBaseNameTaken(server, flagEntity.getOwnerTeamName(), name,
                level, payload.flagPos())) {
            reject(context, "gui.basebuster.base_management.name_taken", name);
            return;
        }

        flagEntity.setFlagName(Component.literal(name));
        flagEntity.setDebugBoundary(payload.showBorder());
        level.sendBlockUpdated(payload.flagPos(), flagEntity.getBlockState(), flagEntity.getBlockState(), 3);
        context.reply(new CreateFactionResultPayload(true,
                Component.translatable("gui.basebuster.base_management.saved")));
    }
}
