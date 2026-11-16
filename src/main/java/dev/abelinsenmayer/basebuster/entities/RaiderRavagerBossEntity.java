package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.Config;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The illager theme's squad leader: a ravager scaled up like the zombie boss,
 * carrying the same {@link RaiderBossComponent} state (squad route, raid
 * identity, banner drop). Squad members follow its planned path exactly as
 * they would a zombie boss's.
 */
public class RaiderRavagerBossEntity extends RaiderRavagerEntity implements IRaiderBoss {
    // Vanilla ravager height is 2.2 blocks; scale by the same factor the
    // zombie boss uses (1.95 -> ~2.5) for a consistent "this one leads" read
    private static final double BOSS_SCALE = 2.5 / 1.95;
    private static final EntityDataAccessor<CompoundTag> DATA_SQUAD_PATH =
            SynchedEntityData.defineId(RaiderRavagerBossEntity.class, EntityDataSerializers.COMPOUND_TAG);
    private static final int GLOW_COLOR = ChatFormatting.RED.getColor();

    private final RaiderBossComponent<RaiderRavagerBossEntity> boss = new RaiderBossComponent<>(this, DATA_SQUAD_PATH);

    public RaiderRavagerBossEntity(EntityType<? extends Ravager> entityType, Level level) {
        super(entityType, level);
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
    public void tick() {
        super.tick();
        this.boss.tick();
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
        // Ravagers already run 100hp; the boss bonus stacks on top
        AttributeInstance maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(maxHealth.getBaseValue() + Config.RAIDER_BOSS_BONUS_HEALTH.get());
            this.setHealth(this.getMaxHealth());
        }
        return result;
    }

    @Override
    public boolean canJoinSquad() {
        // Bosses are leaders; they don't join squads and never flee
        return false;
    }

    // Bosses read their own glow key rather than the regular-raider one
    @Override
    public boolean isCurrentlyGlowing() {
        return Config.RAIDER_BOSS_GLOWING.get() || super.isCurrentlyGlowing();
    }

    @Override
    public int getTeamColor() {
        return GLOW_COLOR;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        this.boss.dropVictoryBanner(level);
    }

    // ---- IRaiderBoss: all state forwards to the component ----

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
