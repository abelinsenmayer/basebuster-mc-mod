package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

// A beefier raider: scaled up to roughly 2.5 blocks tall. Skins come from the
// same RaiderSkin pool as normal raiders; goals and behavior are inherited.
public class RaiderBossEntity extends RaiderEntity implements IRaiderBoss {
    // Base zombie height is 1.95 blocks; 1.95 * SCALE ~= 2.5
    private static final double BOSS_SCALE = 2.5 / 1.95;
    // The squad path mirrored to clients (as packed BlockPos longs) for the
    // debug overlay; entity data has no list type, so it rides a CompoundTag
    private static final EntityDataAccessor<CompoundTag> DATA_SQUAD_PATH =
            SynchedEntityData.defineId(RaiderBossEntity.class, EntityDataSerializers.COMPOUND_TAG);
    // Glow outline color; vanilla defaults to white for teamless entities
    private static final int GLOW_COLOR = ChatFormatting.RED.getColor();

    // The composed boss state (squad route, raid identity, pending search);
    // the IRaiderBoss contract forwards here so any PathfinderMob subclass
    // can lead squads the same way
    private final RaiderBossComponent<RaiderBossEntity> boss = new RaiderBossComponent<>(this, DATA_SQUAD_PATH);

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
        // Resubmits the squad-path search a world save interrupted, if any
        this.boss.tick();
    }

    @Override
    public List<BlockPos> getSquadPath() {
        return this.boss.getSquadPath();
    }

    @Override
    @Nullable
    public BlockPos getSquadPathTarget() {
        return this.boss.getSquadPathTarget();
    }

    @Override
    public boolean isSquadPathSearchPending() {
        return this.boss.isSquadPathSearchPending();
    }

    @Override
    public void planSquadPathTo(BlockPos flag) {
        this.boss.planSquadPathTo(flag);
    }

    @Override
    public void setSquadPath(List<BlockPos> waypoints, BlockPos flagPos) {
        this.boss.setSquadPath(waypoints, flagPos);
    }

    @Override
    public void clearSquadPath() {
        this.boss.clearSquadPath();
    }

    @Override
    public List<BlockPos> getSyncedSquadPath() {
        return this.boss.getSyncedSquadPath();
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

    @Override
    @Nullable
    public UUID getRaidId() {
        return this.boss.getRaidId();
    }

    @Override
    public void setRaidId(UUID raidId) {
        this.boss.setRaidId(raidId);
    }

    @Override
    @Nullable
    public BlockPos getRaidTarget() {
        return this.boss.getRaidTarget();
    }

    @Override
    public void setRaidTarget(BlockPos raidTarget) {
        this.boss.setRaidTarget(raidTarget);
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        this.boss.dropVictoryBanner(level);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        this.boss.save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.boss.load(tag);
    }
}
