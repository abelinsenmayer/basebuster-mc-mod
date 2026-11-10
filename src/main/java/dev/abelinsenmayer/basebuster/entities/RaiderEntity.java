package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.entities.goals.CaptureFlagGoal;
import dev.abelinsenmayer.basebuster.entities.goals.FleeGoal;
import dev.abelinsenmayer.basebuster.entities.goals.JoinSquadGoal;
import dev.abelinsenmayer.basebuster.entities.goals.MoveToFlagGoal;
import dev.abelinsenmayer.basebuster.entities.goals.WanderNearFlagGoal;
import dev.abelinsenmayer.basebuster.pathfinding.RaiderNavigation;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveThroughVillageGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.ZombieAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;

import javax.annotation.Nullable;
import java.util.Comparator;

public class RaiderEntity extends Zombie {
    @Nullable
    private RaiderBossEntity squadLeader;

    public RaiderEntity(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new RaiderNavigation(this, level);
    }

    // Full goal hierarchy, specified explicitly instead of inheriting Zombie's.
    // Priorities mirror Zombie's so tuning stays comparable; lower number = higher priority.
    @Override
    protected void registerGoals() {
        // Execution goals
        // Leaderless raiders on an assault get cold feet before anything else
        this.goalSelector.addGoal(2, new FleeGoal(this));
        this.goalSelector.addGoal(3, new ZombieAttackGoal(this, 1.0, false));
        // Squad election uses no goal flags, so it can fire alongside anything
        // (including mid-flee) and end the retreat as soon as a boss shows up
        this.goalSelector.addGoal(3, new JoinSquadGoal(this));
        // Capturing is the raider's highest priority after combat
        this.goalSelector.addGoal(4, new CaptureFlagGoal(this));
        this.goalSelector.addGoal(5, new MoveToFlagGoal(this, 1.0));
        this.goalSelector.addGoal(6, new WanderNearFlagGoal(this, 1.0));
        this.goalSelector.addGoal(7, new MoveThroughVillageGoal(this, 1.0, true, 4, this::canBreakDoors));
        this.goalSelector.addGoal(8, new WaterAvoidingRandomStrollGoal(this, 1.0));
        this.goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));

        // Target selection goals
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this).setAlertOthers(ZombifiedPiglin.class));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, false));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
        AttributeInstance maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(this.getConfiguredMaxHealth());
            this.setHealth((float) this.getConfiguredMaxHealth());
        }
        return result;
    }

    // Max health this raider spawns with; subclasses (e.g. bosses) can override.
    protected double getConfiguredMaxHealth() {
        return Config.RAIDER_MAX_HEALTH.get();
    }

    // Whether this raider type participates in squads; bosses lead, never join
    public boolean canJoinSquad() {
        return true;
    }

    @Nullable
    public RaiderBossEntity getSquadLeader() {
        if (this.squadLeader != null && this.squadLeader.isRemoved()) {
            this.squadLeader = null;
        }
        return this.squadLeader;
    }

    public void setSquadLeader(@Nullable RaiderBossEntity squadLeader) {
        this.squadLeader = squadLeader;
    }

    /**
     * Whether the raider is on an assault: a flag not owned by the raider
     * faction exists for it to march on. Raiders guarding an already-captured
     * flag are not assaulting, so they never join squads or flee.
     */
    public boolean isAttemptingCapture() {
        return this.nearestUncapturedFlag() != null;
    }

    /** The closest registered flag not owned by the raider faction. */
    @Nullable
    public BlockPos nearestUncapturedFlag() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        FlagsSavedData flagsSavedData = FlagsSavedData.getFromLevel(serverLevel);
        BlockPos mobPos = this.blockPosition();
        return flagsSavedData.getFlagLocations().stream()
                .filter(flagPos -> !flagsSavedData.getCapturedFlagLocations().contains(flagPos))
                .filter(this::isWithinRestriction)
                .min(Comparator.comparingDouble(flagPos -> flagPos.distSqr(mobPos)))
                .orElse(null);
    }

    /** The closest registered flag, regardless of ownership. */
    @Nullable
    public BlockPos nearestFlag() {
        if (!(this.level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        FlagsSavedData flagsSavedData = FlagsSavedData.getFromLevel(serverLevel);
        BlockPos mobPos = this.blockPosition();
        return flagsSavedData.getFlagLocations().stream()
                .filter(this::isWithinRestriction)
                .min(Comparator.comparingDouble(flagPos -> flagPos.distSqr(mobPos)))
                .orElse(null);
    }
}
