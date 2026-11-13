package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.blocks.VictoryBannerBlock;
import dev.abelinsenmayer.basebuster.entities.goals.PlanSquadPathGoal;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    // A dying boss scans this far for surviving bosses from the same raid
    // before awarding the victory banner
    private static final double RAID_MATE_SEARCH_RADIUS = 128.0;
    // Glow outline color; vanilla defaults to white for teamless entities
    private static final int GLOW_COLOR = ChatFormatting.RED.getColor();
    // Bosses march slower than their kit speed so squad members can reach
    // and hold their chevron slots instead of straggling behind
    private static final double BOSS_SPEED_FACTOR = 0.85;

    // Which raid this boss belongs to; null for egg/summon spawns, which are
    // not part of a raid and never drop a banner
    @Nullable
    private UUID raidId;
    // Rough route to squadPathTarget planned by PlanSquadPathGoal (or assigned
    // at raid spawn), consumed by FollowSquadPathGoal. Immutable snapshots;
    // not persisted -- the goal re-plans after a world load
    private List<BlockPos> squadPath = List.of();
    @Nullable
    private BlockPos squadPathTarget;
    // Chevron slots handed out round-robin to squad members, keyed by member
    // id so a raider keeps its slot across goal restarts
    private final Map<UUID, Integer> formationSlots = new HashMap<>();
    private int nextFormationSlot;

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
        // Squad-route planning claims no goal flags, so it can fire alongside
        // any other activity and completes the tick it starts
        this.goalSelector.addGoal(2, new PlanSquadPathGoal(this));
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

    /**
     * The chevron slot a squad member holds in this boss's formation. First
     * claim assigns the next index round-robin; repeat claims (e.g. after a
     * goal restart) return the same slot. A dead member's slot is recycled
     * only by the modulo wrap, which is fine while squads stay smaller than
     * the slot count.
     */
    public int claimFormationSlot(UUID memberId, int slotCount) {
        return this.formationSlots.computeIfAbsent(
                memberId, id -> this.nextFormationSlot++ % slotCount);
    }

    @Override
    protected double getConfiguredMaxHealth() {
        // Bosses get a full kit like normal raiders, plus a health bonus
        return this.getKit().maxHealth() + Config.RAIDER_BOSS_BONUS_HEALTH.get();
    }

    @Override
    protected double getConfiguredMovementSpeed() {
        return super.getConfiguredMovementSpeed() * BOSS_SPEED_FACTOR;
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
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(RAID_TAG)) {
            this.raidId = tag.getUUID(RAID_TAG);
        }
    }
}
