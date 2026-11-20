package dev.abelinsenmayer.basebuster.network;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent from the flag's repairs tab when a member of the owning team clicks a
 * row's Repair or Abandon button ({@link #targetPos} identifies the saved
 * repair target) or Repair all ({@link #targetPos} is unused). The server
 * re-checks ownership like {@link EditBasePayload} does, then performs the
 * action immediately; open GUIs learn of the changed repair list from the
 * flag's block-entity update packet, so no reply is sent.
 */
public record ManageRepairsPayload(BlockPos flagPos, Action action, BlockPos targetPos)
        implements CustomPacketPayload {

    public enum Action {
        /** Restore the saved block at {@link #targetPos}. */
        REPAIR,
        /** Discard the saved repair at {@link #targetPos}, dropping its loot. */
        ABANDON,
        /** Repair every saved target at once. */
        REPAIR_ALL,
        /** Discard every saved repair at once, dropping their loot. */
        ABANDON_ALL;

        public static final StreamCodec<ByteBuf, Action> STREAM_CODEC =
                ByteBufCodecs.idMapper(i -> values()[i], Action::ordinal);
    }

    public static final Type<ManageRepairsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BaseBuster.MODID, "manage_repairs"));

    public static final StreamCodec<ByteBuf, ManageRepairsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ManageRepairsPayload::flagPos,
                    Action.STREAM_CODEC, ManageRepairsPayload::action,
                    BlockPos.STREAM_CODEC, ManageRepairsPayload::targetPos,
                    ManageRepairsPayload::new);

    @Override
    public Type<ManageRepairsPayload> type() {
        return TYPE;
    }

    /** Server-side handler; NeoForge runs payload handlers on the main thread. */
    public static void handle(ManageRepairsPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        // Only a member of the team that owns this flag may manage its repairs
        if (!(level.getBlockEntity(payload.flagPos()) instanceof BaseFlagBlockEntity flagEntity)
                || !flagEntity.isOwnedBy(player)) {
            return;
        }

        ItemStack breakingTool = new ItemStack(Items.DIAMOND_PICKAXE);
        switch (payload.action()) {
            case REPAIR -> flagEntity.repairTarget(payload.targetPos(), breakingTool, player);
            case ABANDON -> flagEntity.abandonRepair(payload.targetPos(), player);
            case REPAIR_ALL -> flagEntity.repairRandomTargets(Integer.MAX_VALUE, breakingTool, player);
            case ABANDON_ALL -> flagEntity.abandonRepairs(player);
        }
        level.sendBlockUpdated(payload.flagPos(), flagEntity.getBlockState(), flagEntity.getBlockState(), 3);
    }
}
