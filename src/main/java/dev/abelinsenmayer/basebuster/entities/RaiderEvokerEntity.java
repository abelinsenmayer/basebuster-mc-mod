package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.entities.goals.RaiderGoals;
import dev.abelinsenmayer.basebuster.pathfinding.RaiderMoveControl;
import dev.abelinsenmayer.basebuster.pathfinding.RaiderNavigation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.loot.LootTable;

import javax.annotation.Nullable;

/**
 * A raider that fights like a vanilla evoker: fangs and summoned vexes, with
 * its native player-avoidance left intact. The shared raider layer sits
 * under the spell goals, so the evoker casts and retreats exactly like a
 * vanilla one while also marching with the raid and capturing flags.
 */
public class RaiderEvokerEntity extends Evoker implements IRaider {
    private final RaiderComponent raider = new RaiderComponent(this);

    public RaiderEvokerEntity(EntityType<? extends Evoker> entityType, Level level) {
        super(entityType, level);
        // Our raiders march for our raid system, not vanilla village raids
        this.setCanJoinRaid(false);
        this.moveControl = new RaiderMoveControl(this);
        this.setPathfindingMalus(PathType.DANGER_FIRE, 24.0F);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new RaiderNavigation(this, level);
    }

    @Override
    protected void registerGoals() {
        // Vanilla registerGoals supplies FloatGoal, the spell goals, player
        // avoidance, stroll, looks, and targets; the raider layer sits among
        // them -- spells and AvoidEntityGoal at priorities 1-6 keep the
        // squishy-caster profile, flag goals fill the gaps
        super.registerGoals();
        RaiderGoals.install(this);
    }

    @Override
    public void tick() {
        super.tick();
        if (Config.RAIDER_GOAL_SWITCH_LOGGING.getAsBoolean() && !this.level().isClientSide) {
            this.raider.logGoalTransitions(this.goalSelector, this.targetSelector);
        }
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                        MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
        IRaider.applyTraversalAttributes(this);
        return result;
    }

    @Override
    public boolean isCurrentlyGlowing() {
        return Config.RAIDER_GLOWING.get() || super.isCurrentlyGlowing();
    }

    // Drops match a vanilla evoker (totem, emeralds on kill)
    @Override
    protected ResourceKey<LootTable> getDefaultLootTable() {
        return EntityType.EVOKER.getDefaultLootTable();
    }

    // ---- IRaider: all state forwards to the component ----

    @Override
    public PathfinderMob mob() {
        return this;
    }

    @Override
    public boolean canJoinSquad() {
        return true;
    }

    @Override
    @Nullable
    public IRaiderBoss getSquadLeader() {
        return this.raider.getSquadLeader();
    }

    @Override
    public void setSquadLeader(@Nullable IRaiderBoss squadLeader) {
        this.raider.setSquadLeader(squadLeader);
    }

    @Override
    public RaiderKit getKit() {
        return this.raider.getKit();
    }

    @Override
    public void setKit(RaiderKit kit) {
        this.raider.setKit(kit);
    }

    @Override
    public int getRaidTier() {
        return this.raider.getRaidTier();
    }

    @Override
    public void setRaidTier(int raidTier) {
        this.raider.setRaidTier(raidTier);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        this.raider.save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.raider.load(tag);
    }
}
