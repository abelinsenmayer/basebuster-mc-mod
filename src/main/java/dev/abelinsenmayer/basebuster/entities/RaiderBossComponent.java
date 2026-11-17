package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.blocks.VictoryBannerBlock;
import dev.abelinsenmayer.basebuster.raids.Faction;
import dev.abelinsenmayer.basebuster.pathfinding.PathSearchScheduler;
import dev.abelinsenmayer.basebuster.pathfinding.ResumablePathSearch;
import dev.abelinsenmayer.basebuster.pathfinding.SquadPathSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The state and shared logic behind {@link IRaiderBoss}: squad route storage
 * and planning, raid identity/target, interrupted-search persistence, and the
 * last-boss-standing victory banner. Boss entity classes hold one instance
 * and forward their {@link IRaiderBoss} implementations to it.
 *
 * <p>The synced squad path rides a {@link CompoundTag} entity-data accessor.
 * Synched-data accessors are bound to their declaring class
 * ({@code SynchedEntityData.defineId}), so each boss subclass declares its
 * own and hands it to the component at construction.
 *
 * @param <B> the host entity type
 */
public final class RaiderBossComponent<B extends PathfinderMob & IRaider> {
    private static final String RAID_TAG = "RaidId";
    private static final String RAID_TARGET_TAG = "RaidTarget";
    private static final String SQUAD_PATH_TAG = "squad_path";
    private static final String SQUAD_PATH_SAVE_TAG = "SquadPath";
    private static final String SQUAD_PATH_TARGET_TAG = "SquadPathTarget";
    private static final String PENDING_SQUAD_PATH_TAG = "PendingSquadPathTarget";
    // A dying boss scans this far for surviving bosses from the same raid
    // before awarding the victory banner
    private static final double RAID_MATE_SEARCH_RADIUS = 128.0;

    private final B boss;
    private final EntityDataAccessor<CompoundTag> squadPathData;

    // Which raid this boss belongs to; null for egg/summon spawns, which are
    // not part of a raid and never drop a banner
    @Nullable
    private UUID raidId;
    // The flag this boss's raid is attacking. Set at raid spawn and persisted
    // like raidId; break protection scans living bosses for this target
    @Nullable
    private BlockPos raidTarget;
    // Rough route to squadPathTarget assigned at raid spawn, consumed by
    // FollowSquadPathGoal. Immutable snapshots, persisted so a squad keeps
    // its route across world reloads
    private List<BlockPos> squadPath = List.of();
    @Nullable
    private BlockPos squadPathTarget;
    // The flag a scheduled squad-path search was running toward. Persisted
    // because the scheduler is in-memory only: a search interrupted by a
    // world save survives solely as this target, which tick() resubmits on
    // load. Cleared by the search's completion callback
    @Nullable
    private BlockPos pendingSquadPathTarget;
    // Transient companion to pendingSquadPathTarget: whether that search is
    // actually queued with PathSearchScheduler right now. False after a load,
    // which is what triggers the restart
    private boolean squadPathSearchInFlight;

    public RaiderBossComponent(B boss, EntityDataAccessor<CompoundTag> squadPathData) {
        this.boss = boss;
        this.squadPathData = squadPathData;
    }

    /**
     * Resubmits the search a world save interrupted: a squad-path search in
     * flight at save time survives only as pendingSquadPathTarget, so once
     * the world is ticking again it is resubmitted with the same parameters.
     */
    public void tick() {
        if (this.pendingSquadPathTarget != null && !this.squadPathSearchInFlight
                && this.boss.level() instanceof ServerLevel
                && Config.RAIDER_SQUAD_PATHING_ENABLED.get()) {
            this.planSquadPathTo(this.pendingSquadPathTarget);
        }
    }

    public List<BlockPos> getSquadPath() {
        return this.squadPath;
    }

    @Nullable
    public BlockPos getSquadPathTarget() {
        return this.squadPathTarget;
    }

    public boolean isSquadPathSearchPending() {
        return this.pendingSquadPathTarget != null;
    }

    /**
     * Queues a bounded long-range search from this boss to {@code flag},
     * stepped across ticks by {@link PathSearchScheduler}; the decimated
     * result replaces the squad route. Shared by PlanSquadPathGoal and the
     * reload restart in {@link #tick} -- both get the same guardrail
     * rejection and retarget-drop rules as a result.
     */
    public void planSquadPathTo(BlockPos flag) {
        ResumablePathSearch search = SquadPathSearch.beginSearchToFlag(this.boss, flag);
        if (search == null) {
            // Guardrails rejected the search outright
            this.pendingSquadPathTarget = null;
            if (!flag.equals(this.squadPathTarget)) {
                // The retargeted flag got no path: drop waypoints that would
                // march the squad toward a different flag
                this.clearSquadPath();
            }
            return;
        }
        this.pendingSquadPathTarget = flag.immutable();
        this.squadPathSearchInFlight = true;
        PathSearchScheduler.schedule(search, "squad path to flag " + flag, this.boss::isAlive, path -> {
            this.squadPathSearchInFlight = false;
            this.pendingSquadPathTarget = null;
            if (path != null && flag.equals(this.boss.nearestUncapturedFlag())) {
                this.setSquadPath(SquadPathSearch.decimate(path, false), flag);
            } else if (path == null && !flag.equals(this.squadPathTarget)) {
                // Same retarget-drop rule as the rejection path above
                this.clearSquadPath();
            }
        });
    }

    public void setSquadPath(List<BlockPos> waypoints, BlockPos flagPos) {
        this.squadPath = List.copyOf(waypoints);
        this.squadPathTarget = flagPos.immutable();
        CompoundTag tag = new CompoundTag();
        tag.putLongArray(SQUAD_PATH_TAG, this.squadPath.stream().mapToLong(BlockPos::asLong).toArray());
        this.boss.getEntityData().set(this.squadPathData, tag);
    }

    public void clearSquadPath() {
        this.squadPath = List.of();
        this.squadPathTarget = null;
        this.boss.getEntityData().set(this.squadPathData, new CompoundTag());
    }

    public List<BlockPos> getSyncedSquadPath() {
        long[] packed = this.boss.getEntityData().get(this.squadPathData).getLongArray(SQUAD_PATH_TAG);
        List<BlockPos> waypoints = new ArrayList<>(packed.length);
        for (long pos : packed) {
            waypoints.add(BlockPos.of(pos));
        }
        return waypoints;
    }

    @Nullable
    public UUID getRaidId() {
        return this.raidId;
    }

    public void setRaidId(UUID raidId) {
        this.raidId = raidId;
    }

    @Nullable
    public BlockPos getRaidTarget() {
        return this.raidTarget;
    }

    public void setRaidTarget(BlockPos raidTarget) {
        this.raidTarget = raidTarget.immutable();
    }

    /**
     * The last boss of a raid drops its victory banner. Call from the host's
     * dropCustomDeathLoot, after the super drop.
     */
    public void dropVictoryBanner(ServerLevel level) {
        if (this.isLastBossOfRaid(level)) {
            int index = Math.clamp(this.boss.getRaidTier(), VictoryBannerBlock.MIN_TIER, VictoryBannerBlock.MAX_TIER) - 1;
            ItemStack banner = new ItemStack(BaseBuster.VICTORY_BANNER_ITEMS.get(index).get());
            // The defeated faction's standard, crossed out in red -- the
            // banner's base color still marks the raid's tier
            Faction faction = this.boss.getFaction();
            banner.set(DataComponents.BANNER_PATTERNS,
                    faction.victoryBannerPatterns(level.registryAccess()));
            this.boss.spawnAtLocation(banner);
        }
    }

    /**
     * Whether no other boss from this boss's raid is still fighting. Bosses
     * without a raid id aren't part of a raid and never qualify. A boss
     * mid-death animation (isDeadOrDying) doesn't count as surviving, which
     * keeps simultaneous kills from suppressing the drop.
     */
    private boolean isLastBossOfRaid(ServerLevel level) {
        if (this.raidId == null) {
            return false;
        }
        return IRaiderBoss.nearby(level, this.boss.getBoundingBox().inflate(RAID_MATE_SEARCH_RADIUS))
                .stream()
                .noneMatch(boss -> boss != this.boss
                        && this.raidId.equals(boss.getRaidId())
                        && !boss.mob().isDeadOrDying());
    }

    public void save(CompoundTag tag) {
        if (this.raidId != null) {
            tag.putUUID(RAID_TAG, this.raidId);
        }
        if (this.raidTarget != null) {
            tag.putLong(RAID_TARGET_TAG, this.raidTarget.asLong());
        }
        if (!this.squadPath.isEmpty()) {
            tag.putLongArray(SQUAD_PATH_SAVE_TAG,
                    this.squadPath.stream().mapToLong(BlockPos::asLong).toArray());
        }
        if (this.squadPathTarget != null) {
            tag.putLong(SQUAD_PATH_TARGET_TAG, this.squadPathTarget.asLong());
        }
        if (this.pendingSquadPathTarget != null) {
            tag.putLong(PENDING_SQUAD_PATH_TAG, this.pendingSquadPathTarget.asLong());
        }
    }

    public void load(CompoundTag tag) {
        if (tag.hasUUID(RAID_TAG)) {
            this.raidId = tag.getUUID(RAID_TAG);
        }
        if (tag.contains(RAID_TARGET_TAG, Tag.TAG_LONG)) {
            this.raidTarget = BlockPos.of(tag.getLong(RAID_TARGET_TAG));
        }
        // Path and target are written together; a stray partial pair isn't
        // worth resurrecting
        long[] packedPath = tag.getLongArray(SQUAD_PATH_SAVE_TAG);
        if (packedPath.length > 0 && tag.contains(SQUAD_PATH_TARGET_TAG, Tag.TAG_LONG)) {
            List<BlockPos> waypoints = new ArrayList<>(packedPath.length);
            for (long pos : packedPath) {
                waypoints.add(BlockPos.of(pos));
            }
            // setSquadPath also mirrors the route into synced entity data, so
            // the debug overlay survives the reload too
            this.setSquadPath(waypoints, BlockPos.of(tag.getLong(SQUAD_PATH_TARGET_TAG)));
        }
        if (tag.contains(PENDING_SQUAD_PATH_TAG, Tag.TAG_LONG)) {
            this.pendingSquadPathTarget = BlockPos.of(tag.getLong(PENDING_SQUAD_PATH_TAG));
        }
    }
}
