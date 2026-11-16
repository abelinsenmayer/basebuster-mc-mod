package dev.abelinsenmayer.basebuster.events;

import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import dev.abelinsenmayer.basebuster.blocks.RaiderScaffoldingBlock;
import dev.abelinsenmayer.basebuster.blocks.VictoryBannerBlock;
import dev.abelinsenmayer.basebuster.pathfinding.PathSearchScheduler;
import dev.abelinsenmayer.basebuster.raids.RaidSpawner;
import dev.abelinsenmayer.basebuster.world.FlagSpawnProtection;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.Comparator;

public class BaseBusterEventBus {

    /** Advances all in-progress long-range path searches by their per-tick slice. */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        PathSearchScheduler.onServerTick();
    }

    /** Drops pending path searches; their state is in-memory only. */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        PathSearchScheduler.onServerStopping();
    }

    @SubscribeEvent
    public void livingDestroyBlock(LivingDestroyBlockEvent event) {
        Entity destroyer = event.getEntity();
        if (destroyer.level() instanceof ServerLevel serverLevel
                && protectedByRaid(serverLevel, event.getPos(), event.getState())) {
            event.setCanceled(true);
            return;
        }
        // The event fires before destruction, so the block entity is still in the world
        handleBlockGriefing(destroyer, event.getPos(), event.getState(), destroyer.level().getBlockEntity(event.getPos()));
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (enforceFlagBreakProtection(event)) {
            return;
        }
        handleBlockGriefing(event.getPlayer(), event.getPos(), event.getState(), event.getLevel().getBlockEntity(event.getPos()));
    }

    @SubscribeEvent
    public void onExplosionDetonate(ExplosionEvent.Detonate event) {
        // A flag under attack and its victory banners shrug off every explosion
        // for the raid's duration, no matter who set it off
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            event.getAffectedBlocks().removeIf(pos ->
                    protectedByRaid(serverLevel, pos, serverLevel.getBlockState(pos)));
        }
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
     * Keeps flag protection areas from overlapping: a new flag must sit far
     * enough from every existing flag that their spheres can never touch,
     * even after either side levels up. Spacing uses the max radius for the
     * existing flag (its current level can grow) plus the new flag's level-1
     * radius. Cancelling also refunds the item to the placer.
     */
    @SubscribeEvent
    public void onFlagPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getPlacedBlock().getBlock() instanceof BaseFlagBlock)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockPos pos = event.getPos();
        int minSeparation = BaseFlagBlock.MAX_PROTECTION_RADIUS * 2;
        BlockPos conflict = FlagsSavedData.getFromLevel(serverLevel).getFlagLocations().stream()
                .filter(flagPos -> flagPos.distSqr(pos) < (double) minSeparation * minSeparation)
                .min(Comparator.comparingDouble(flagPos -> flagPos.distSqr(pos)))
                .orElse(null);
        if (conflict == null) {
            return;
        }

        event.setCanceled(true);
        if (event.getEntity() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal(
                    "Too close to the flag at " + conflict.getX() + " " + conflict.getY() + " " + conflict.getZ()
                            + ": base flags must be at least " + minSeparation + " blocks apart.")
                    .withStyle(ChatFormatting.RED));
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

    /**
     * Lets a sneaking player use the flag's right-click interactions (capture,
     * debug toggle, repairs, raid triggers) even while holding an item such as
     * an offhand shield. Vanilla skips block interaction entirely when sneaking
     * with either hand occupied, which would otherwise make shift-click captures
     * possible only with empty hands. The flag's own handlers still decide what
     * happens, and items keep their normal behavior whenever the flag passes the
     * interaction through.
     */
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity().isSecondaryUseActive()
                && event.getLevel().getBlockState(event.getPos()).getBlock() instanceof BaseFlagBlock) {
            event.setUseBlock(TriState.TRUE);
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
     * Cancels a player break that flag protection forbids: a flag being raided
     * (or one of its victory banners) cannot be broken at all except by
     * Creative-mode players, and non-Creative players can only break flags and
     * attached banners they own.
     *
     * @return whether the event was canceled
     */
    private boolean enforceFlagBreakProtection(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player.isCreative() || !(event.getLevel() instanceof ServerLevel serverLevel)) {
            return false;
        }
        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        if (state.getBlock() instanceof BaseFlagBlock) {
            return guardFlagBreak(event, serverLevel, pos, player, pos);
        }
        if (VictoryBannerBlock.victoryBannerTier(state) > 0) {
            BlockPos flagPos = nearestCoveringFlag(serverLevel, pos);
            if (flagPos != null) {
                return guardFlagBreak(event, serverLevel, flagPos, player, pos);
            }
        }
        return false;
    }

    /**
     * Applies the break rules for {@code brokenPos}, which is either the flag
     * at {@code flagPos} or a victory banner inside its protection radius.
     *
     * @return whether the break is forbidden
     */
    private boolean guardFlagBreak(BlockEvent.BreakEvent event, ServerLevel level, BlockPos flagPos, Player player, BlockPos brokenPos) {
        if (RaidSpawner.isRaidInProgress(level, flagPos)) {
            denyBreak(event, player, brokenPos.equals(flagPos)
                    ? "This flag cannot be broken while a raid is attacking it."
                    : "This banner cannot be broken while a raid is attacking its flag.");
            return true;
        }
        BaseFlagBlockEntity flagEntity = level.getBlockEntity(flagPos) instanceof BaseFlagBlockEntity entity
                ? entity
                : null;
        Player owner = flagEntity != null ? flagEntity.getOwner() : null;
        if (owner == null || !owner.getUUID().equals(player.getUUID())) {
            denyBreak(event, player, brokenPos.equals(flagPos)
                    ? "You cannot break a flag you do not own."
                    : "You cannot break banners attached to a flag you do not own.");
            return true;
        }
        return false;
    }

    private static void denyBreak(BlockEvent.BreakEvent event, Player player, String message) {
        event.setCanceled(true);
        player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.RED), true);
    }

    /**
     * Whether a raid currently protects the block at {@code pos}: it is a flag
     * under attack, or a victory banner attached to a flag under attack.
     */
    private static boolean protectedByRaid(ServerLevel level, BlockPos pos, BlockState state) {
        BlockPos flagPos;
        if (state.getBlock() instanceof BaseFlagBlock) {
            flagPos = pos;
        } else if (VictoryBannerBlock.victoryBannerTier(state) > 0) {
            flagPos = nearestCoveringFlag(level, pos);
        } else {
            return false;
        }
        return flagPos != null && RaidSpawner.isRaidInProgress(level, flagPos);
    }

    /**
     * The nearest registered flag whose protection radius covers {@code pos};
     * a farther higher-level flag can cover a spot a nearer lower-level flag
     * cannot. Null when no flag's sphere reaches the position.
     */
    @Nullable
    private static BlockPos nearestCoveringFlag(ServerLevel level, BlockPos pos) {
        double maxRadiusSqr = Math.pow(BaseFlagBlock.MAX_PROTECTION_RADIUS, 2);
        return FlagsSavedData.getFromLevel(level).getFlagLocations().stream()
                .filter(flagPos -> flagPos.distSqr(pos) <= maxRadiusSqr)
                .filter(flagPos -> flagPos.distSqr(pos)
                        <= Math.pow(BaseFlagBlock.protectionRadius(level, flagPos), 2))
                .min(Comparator.comparingDouble(flagPos -> flagPos.distSqr(pos)))
                .orElse(null);
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

        // Raider scaffolding is temporary clutter, not part of the base:
        // repairing it would just respawn blocks that remove themselves anyway
        if (state.getBlock() instanceof RaiderScaffoldingBlock) {
            return false;
        }

        if (!(culprit.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        // See if there's a flag nearby
        // The flag that handles the grief report is the nearest one whose own
        // radius reaches the broken block
        BlockPos nearestFlag = nearestCoveringFlag(serverLevel, pos);
        if (nearestFlag == null) {
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
