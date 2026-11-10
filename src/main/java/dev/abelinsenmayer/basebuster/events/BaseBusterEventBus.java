package dev.abelinsenmayer.basebuster.events;

import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import dev.abelinsenmayer.basebuster.world.FlagSpawnProtection;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public class BaseBusterEventBus {

    @SubscribeEvent
    public void livingDestroyBlock(LivingDestroyBlockEvent event) {
        Entity destroyer = event.getEntity();
        // The event fires before destruction, so the block entity is still in the world
        handleBlockGriefing(destroyer, event.getPos(), event.getState(), destroyer.level().getBlockEntity(event.getPos()));
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        handleBlockGriefing(event.getPlayer(), event.getPos(), event.getState(), event.getLevel().getBlockEntity(event.getPos()));
    }

    @SubscribeEvent
    public void onExplosionDetonate(ExplosionEvent.Detonate event) {
        Explosion explosion = event.getExplosion();
        // Prefer the entity that indirectly caused the explosion (e.g. the player who ignited the TNT)
        Entity responsible = explosion.getIndirectSourceEntity();
        if (responsible == null) {
            responsible = explosion.getDirectSourceEntity();
        }
        if (responsible == null) {
            return;
        }
        for (BlockPos pos : event.getAffectedBlocks()) {
            if (handleBlockGriefing(responsible, pos, event.getLevel().getBlockState(pos), event.getLevel().getBlockEntity(pos))) {
                event.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /**
     * Bars players from setting a respawn point (bed click, respawn anchor,
     * /spawnpoint) inside a faction-owned flag's protected area. Cancelling
     * the event also suppresses the vanilla "Respawn point set" message.
     */
    @SubscribeEvent
    public void onPlayerSetSpawn(PlayerSetSpawnEvent event) {
        BlockPos newSpawn = event.getNewSpawn();
        if (newSpawn == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel spawnLevel = player.server.getLevel(event.getSpawnLevel());
        if (spawnLevel == null) {
            return;
        }
        if (FlagSpawnProtection.controllingFlag(spawnLevel, newSpawn) != null) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal(
                    "You can't set your respawn point here: the area is controlled by a hostile faction.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Drops a stored respawn point that ended up inside a faction-controlled
     * area while the player was offline (e.g. their flag was captured).
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FlagSpawnProtection.clearSpawnIfControlled(player);
        }
    }

    @SubscribeEvent
    public void onBlockDrop(BlockDropsEvent event) {
        Entity breaker = event.getBreaker();
        if (breaker == null) {
            return;
        }
        if (handleBlockGriefing(breaker, event.getPos(), event.getState(), event.getBlockEntity())) {
            // Cancel the event so we don't drop any items or experience
            event.setCanceled(true);
        }
    }

    /**
     * Determines whether a block destroyed by a known entity falls under a nearby flag's protection.
     * If the culprit doesn't own the protecting flag, the block is registered as a repair target
     * so the flag can restore it later.
     *
     * @param culprit     the entity responsible for destroying the block
     * @param pos         the position of the destroyed block
     * @param state       the block state prior to destruction
     * @param blockEntity the block entity that was at {@code pos}, if any
     * @return whether the block was registered as a repair target on a flag
     */
    public static boolean handleBlockGriefing(Entity culprit, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) {
        if (state.getBlock() == Blocks.AIR) {
            return false;
        }

        // A broken flag can't repair itself
        if (state.getBlock() instanceof BaseFlagBlock) {
            return false;
        }

        if (!(culprit.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        // See if there's a flag nearby
        FlagsSavedData flagsSavedData = FlagsSavedData.getFromLevel(serverLevel);
        Set<BlockPos> flagLocations = flagsSavedData.getFlagLocations();

        BlockPos nearestFlag = flagLocations.stream()
                .min(Comparator.comparingDouble(flagLocation -> flagLocation.distSqr(pos)))
                .orElse(null);
        if (nearestFlag == null || nearestFlag.distSqr(pos) > Math.pow(BaseFlagBlock.FLAG_PROTECTION_RADIUS, 2)) {
            return false;
        }

        BaseFlagBlockEntity flagEntity = (BaseFlagBlockEntity) serverLevel.getBlockEntity(nearestFlag);
        if (flagEntity == null) {
            return false;
        }

        // If the flag has no owner, or the culprit owns it, do nothing
        if (flagEntity.getOwner() == null || flagEntity.getOwner().getName().equals(culprit.getName())) {
            return false;
        }

        // If the culprit is not an owner, store the block as a repair target on the flag
        flagEntity.addRepairTarget(pos, state, blockEntity);
        // Remove the block entity before the block is destroyed so Containers.dropContentsOnDestroy
        // finds nothing at this position. Otherwise container contents (e.g. chests) would be
        // scattered as item entities and cleared from the saved block entity.
        serverLevel.removeBlockEntity(pos);
        serverLevel.sendBlockUpdated(flagEntity.getBlockPos(), flagEntity.getBlockState(), flagEntity.getBlockState(), 3);
        return true;
    }
}
