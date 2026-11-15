package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.blocks.VictoryBannerBlock;
import dev.abelinsenmayer.basebuster.pathfinding.PathSearchScheduler;
import dev.abelinsenmayer.basebuster.pathfinding.ResumablePathSearch;
import dev.abelinsenmayer.basebuster.pathfinding.SquadPathSearch;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// A beefier raider: scaled up to roughly 2.5 blocks tall. Skins come from the
// same RaiderSkin pool as normal raiders; goals and behavior are inherited.
public class RaiderBossEntity extends RaiderEntity {
    // Base zombie height is 1.95 blocks; 1.95 * SCALE ~= 2.5
    private static final double BOSS_SCALE = 2.5 / 1.95;
    private static final String RAID_TAG = "RaidId";
    // The squad path mirrored to clients (as packed BlockPos longs) for the
    // debug overlay; entity data has no list type, so it rides a CompoundTag
    private static final EntityDataAccessor<CompoundTag> DATA_SQUAD_PATH =
            SynchedEntityData.defineId(RaiderBossEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final String SQUAD_PATH_TAG = "squad_path";
    private static final String SQUAD_PATH_SAVE_TAG = "SquadPath";
    private static final String SQUAD_PATH_TARGET_TAG = "SquadPathTarget";
    private static final String PENDING_SQUAD_PATH_TAG = "PendingSquadPathTarget";
    // A dying boss scans this far for surviving bosses from the same raid
    // before awarding the victory banner
    private static final double RAID_MATE_SEARCH_RADIUS = 128.0;
    // Glow outline color; vanilla defaults to white for teamless entities
    private static final int GLOW_COLOR = ChatFormatting.RED.getColor();

    // Which raid this boss belongs to; null for egg/summon spawns, which are
    // not part of a raid and never drop a banner
    @Nullable
    private UUID raidId;
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

    public RaiderBossEntity(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
        // Set in the constructor (not finalizeSpawn) so the size is correct
        // regardless of spawn path, including /summon.
        AttributeInstance scale = this.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.setBaseValue(BOSS_SCALE);
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SQUAD_PATH, new CompoundTag());
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        // No PlanSquadPathGoal: bosses never re-plan their squad route on
        // their own. Routes are assigned at raid spawn only; persistence and
        // the reload-restart of an interrupted search live in the save-data
        // methods and tick() instead.
    }

    @Override
    public void tick() {
        super.tick();
        // A squad-path search in flight when the world saved survives only as
        // pendingSquadPathTarget; resubmit it with the same parameters once
        // the world is ticking again
        if (this.pendingSquadPathTarget != null && !this.squadPathSearchInFlight
                && this.level() instanceof ServerLevel
                && Config.RAIDER_SQUAD_PATHING_ENABLED.get()) {
            this.planSquadPathTo(this.pendingSquadPathTarget);
        }
    }

    /**
     * The shared rough route this boss's squad marches along. Followers take
     * the immutable list itself, so updating it never mutates a path another
     * raider is following.
     */
    public List<BlockPos> getSquadPath() {
        return this.squadPath;
    }

    /** The flag {@link #getSquadPath} leads to; null when no path is stored. */
    @Nullable
    public BlockPos getSquadPathTarget() {
        return this.squadPathTarget;
    }

    /**
     * Whether a squad-path search is in flight or was at last save. Pending
     * state lives here (not on PlanSquadPathGoal) so it can ride the boss's
     * NBT across world reloads.
     */
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
        ResumablePathSearch search = SquadPathSearch.beginSearchToFlag(this, flag);
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
        PathSearchScheduler.schedule(search, "squad path to flag " + flag, this::isAlive, path -> {
            this.squadPathSearchInFlight = false;
            this.pendingSquadPathTarget = null;
            if (path != null && flag.equals(this.nearestUncapturedFlag())) {
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
        this.entityData.set(DATA_SQUAD_PATH, tag);
    }

    public void clearSquadPath() {
        this.squadPath = List.of();
        this.squadPathTarget = null;
        this.entityData.set(DATA_SQUAD_PATH, new CompoundTag());
    }

    /**
     * The squad path as synced to clients. Unlike {@link #getSquadPath}, which
     * is populated only on the server, this reads entity data so it resolves
     * on both sides -- the debug renderer's source of truth.
     */
    public List<BlockPos> getSyncedSquadPath() {
        long[] packed = this.entityData.get(DATA_SQUAD_PATH).getLongArray(SQUAD_PATH_TAG);
        List<BlockPos> waypoints = new ArrayList<>(packed.length);
        for (long pos : packed) {
            waypoints.add(BlockPos.of(pos));
        }
        return waypoints;
    }

    @Override
    protected double getConfiguredMaxHealth() {
        // Bosses get a full kit like normal raiders, plus a health bonus
        return this.getKit().maxHealth() + Config.RAIDER_BOSS_BONUS_HEALTH.get();
    }

    // Bosses read their own glow key rather than the regular-raider one, so
    // the two can be toggled independently
    @Override
    protected boolean glowsByConfig() {
        return Config.RAIDER_BOSS_GLOWING.get();
    }

    // Vanilla colors glow outlines by scoreboard team, defaulting to white
    // when the entity has none. Red marks bosses without putting them on a
    // team; an explicit team assignment still wins over the default
    @Override
    public int getTeamColor() {
        return this.getTeam() == null ? GLOW_COLOR : super.getTeamColor();
    }

    @Override
    public boolean canJoinSquad() {
        // Bosses are leaders; they don't join squads and never flee
        return false;
    }

    // Bosses are worth 5x a raider's XP (25)
    @Override
    protected int getBaseExperienceReward() {
        return super.getBaseExperienceReward() * 5;
    }

    @Nullable
    public UUID getRaidId() {
        return this.raidId;
    }

    /** Tags this boss as a member of a raid; RaidSpawner assigns one id per raid. */
    public void setRaidId(UUID raidId) {
        this.raidId = raidId;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        if (this.isLastBossOfRaid(level)) {
            int index = Math.clamp(this.getRaidTier(), VictoryBannerBlock.MIN_TIER, VictoryBannerBlock.MAX_TIER) - 1;
            this.spawnAtLocation(new ItemStack(BaseBuster.VICTORY_BANNER_ITEMS.get(index).get()));
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
        List<RaiderBossEntity> survivingRaidMates = level.getEntities(
                EntityTypeTest.forClass(RaiderBossEntity.class),
                this.getBoundingBox().inflate(RAID_MATE_SEARCH_RADIUS),
                boss -> boss != this && this.raidId.equals(boss.raidId) && !boss.isDeadOrDying());
        return survivingRaidMates.isEmpty();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.raidId != null) {
            tag.putUUID(RAID_TAG, this.raidId);
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

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(RAID_TAG)) {
            this.raidId = tag.getUUID(RAID_TAG);
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
