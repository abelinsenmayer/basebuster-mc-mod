package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.entities.goals.RaiderGoals;
import dev.abelinsenmayer.basebuster.raids.Faction;
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
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.loot.LootTable;

import javax.annotation.Nullable;

/**
 * A raider that fights like a vanilla pillager: native crossbow combat plus
 * the shared raider layer (squads, flag assault, flee). Pillagers are the
 * illager theme's ranged member, the counterpart to the zombie bowman kit.
 */
public class RaiderPillagerEntity extends Pillager implements IRaider {
    private final RaiderComponent raider = new RaiderComponent(this);

    public RaiderPillagerEntity(EntityType<? extends Pillager> entityType, Level level) {
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
        // Vanilla registerGoals supplies FloatGoal, crossbow combat, stroll,
        // looks, and the full target table; the raider layer sits among them
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
        // super hands the pillager its crossbow; traversal stats come after
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
        IRaider.applyTraversalAttributes(this);
        return result;
    }

    @Override
    public boolean isCurrentlyGlowing() {
        return Config.RAIDER_GLOWING.get() || super.isCurrentlyGlowing();
    }

    // Drops match a vanilla pillager (crossbow chance, emeralds on kill)
    @Override
    protected ResourceKey<LootTable> getDefaultLootTable() {
        return EntityType.PILLAGER.getDefaultLootTable();
    }

    // ---- IRaider: all state forwards to the component ----

    @Override
    public PathfinderMob mob() {
        return this;
    }

    @Override
    public Faction getFaction() {
        return Faction.ILLAGER;
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
