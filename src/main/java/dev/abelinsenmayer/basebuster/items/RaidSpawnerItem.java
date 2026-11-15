package dev.abelinsenmayer.basebuster.items;

import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import dev.abelinsenmayer.basebuster.raids.RaidSpawner;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Testing tool disguised as rotten flesh: right-clicking a block starts a
 * raid on the nearest player-owned flag, with the whole party spawning on
 * the clicked face instead of a dynamically-chosen point on the spawn ring.
 */
public class RaidSpawnerItem extends Item {
    public RaidSpawnerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        // Spawn on the face that was clicked, like placing a block
        BlockPos spawnPos = context.getClickedPos().relative(context.getClickedFace());
        BlockPos flagPos = nearestRaidableFlag(serverLevel, spawnPos);
        if (flagPos == null) {
            if (player != null) {
                player.displayClientMessage(
                        Component.literal("No player-owned flag found"), true);
            }
            return InteractionResult.CONSUME;
        }

        int raidTier = RaidSpawner.raidTier(serverLevel, flagPos);
        // The party materializes once its path searches finish, some ticks
        // later; announce the raid then, not at trigger time
        if (RaidSpawner.startRaidAt(serverLevel, flagPos, spawnPos, () -> {
            if (player != null) {
                player.displayClientMessage(
                        Component.literal("A level " + raidTier + " raid has begun!"), false);
            }
        })) {
            if (player != null && !player.hasInfiniteMaterials()) {
                context.getItemInHand().shrink(1);
            }
        } else if (player != null) {
            player.displayClientMessage(
                    Component.literal("Could not find a safe raid spawn point"), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * The nearest flag that could take a raid: a loaded flag block entity
     * owned by a player (unowned and raider-captured flags are skipped, as
     * in {@link dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock}'s spawn
     * egg trigger). Flags in unloaded chunks are ignored rather than
     * force-loaded.
     */
    private static BlockPos nearestRaidableFlag(ServerLevel level, BlockPos from) {
        BlockPos nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos flag : FlagsSavedData.getFromLevel(level).getFlagLocations()) {
            if (!level.isLoaded(flag)) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(flag);
            if (!(blockEntity instanceof BaseFlagBlockEntity flagEntity) || flagEntity.getOwner() == null) {
                continue;
            }
            double dist = flag.distSqr(from);
            if (dist < bestDist) {
                bestDist = dist;
                nearest = flag;
            }
        }
        return nearest;
    }
}
