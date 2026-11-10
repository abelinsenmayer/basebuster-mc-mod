package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.entities.goals.CaptureFlagGoal;
import dev.abelinsenmayer.basebuster.entities.goals.MoveToFlagGoal;
import dev.abelinsenmayer.basebuster.entities.goals.WanderNearFlagGoal;
import dev.abelinsenmayer.basebuster.pathfinding.RaiderNavigation;
import net.minecraft.world.entity.EntityType;
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

public class RaiderEntity extends Zombie {
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
        this.goalSelector.addGoal(2, new ZombieAttackGoal(this, 1.0, false));
        // Capturing is the raider's highest priority after combat
        this.goalSelector.addGoal(3, new CaptureFlagGoal(this));
        this.goalSelector.addGoal(4, new MoveToFlagGoal(this, 1.0));
        this.goalSelector.addGoal(5, new WanderNearFlagGoal(this, 1.0));
        this.goalSelector.addGoal(6, new MoveThroughVillageGoal(this, 1.0, true, 4, this::canBreakDoors));
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 1.0));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

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
}
