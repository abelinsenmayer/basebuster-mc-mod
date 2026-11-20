package dev.abelinsenmayer.basebuster.network;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent from the flag's base tab when a member of the owning team clicks a
 * citizen row's Banish button. The server re-checks ownership like
 * {@link ManageRepairsPayload} does, then ejects the citizen immediately;
 * open GUIs learn of the changed roster from the flag's block-entity update
 * packet, so no reply is sent.
 */
public record BanishCitizenPayload(BlockPos flagPos, UUID citizenId)
        implements CustomPacketPayload {

    public static final Type<BanishCitizenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BaseBuster.MODID, "banish_citizen"));

    public static final StreamCodec<ByteBuf, BanishCitizenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, BanishCitizenPayload::flagPos,
                    UUIDUtil.STREAM_CODEC, BanishCitizenPayload::citizenId,
                    BanishCitizenPayload::new);

    @Override
    public Type<BanishCitizenPayload> type() {
        return TYPE;
    }

    /** Server-side handler; NeoForge runs payload handlers on the main thread. */
    public static void handle(BanishCitizenPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        // Only a member of the team that owns this flag may banish its citizens
        if (!(level.getBlockEntity(payload.flagPos()) instanceof BaseFlagBlockEntity flagEntity)
                || !flagEntity.isOwnedBy(player)) {
            return;
        }

        flagEntity.banishCitizen(payload.citizenId(), player);
        level.sendBlockUpdated(payload.flagPos(), flagEntity.getBlockState(), flagEntity.getBlockState(), 3);
    }
}
