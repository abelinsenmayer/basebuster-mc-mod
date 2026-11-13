package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.entities.goals.CaptureFlagGoal;
import dev.abelinsenmayer.basebuster.entities.goals.FleeGoal;
import dev.abelinsenmayer.basebuster.entities.goals.FollowSquadPathGoal;
import dev.abelinsenmayer.basebuster.entities.goals.JoinSquadGoal;
import dev.abelinsenmayer.basebuster.entities.goals.MoveToFlagGoal;
import dev.abelinsenmayer.basebuster.entities.goals.WanderNearFlagGoal;
import dev.abelinsenmayer.basebuster.pathfinding.RaiderMoveControl;
import dev.abelinsenmayer.basebuster.pathfinding.RaiderNavigation;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveThroughVillageGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.goal.ZombieAttackGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.neoforge.fluids.FluidType;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class RaiderEntity extends Zombie implements RangedAttackMob {
    // -1 = no explicit skin; getSkin() falls back to the generic zombie
    private static final int UNSET_SKIN_ID = -1;
    private static final EntityDataAccessor<Integer> DATA_SKIN_ID =
            SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.INT);
    private static final String SKIN_TAG = "RaiderSkin";
    private static final String KIT_TAG = "RaiderKit";
    private static final int BOW_ATTACK_INTERVAL_TICKS = 20;
    private static final float BOW_ATTACK_RADIUS = 40.0F;
    // Vanilla skeletons fire at 1.6 blocks/tick over a 15-block range. Launch
    // velocity is computed per shot (see performRangedAttack); BOW_VELOCITY is
    // the ceiling, scaled with the configured attack radius.
    private static final float BOW_BASE_RANGE = 15.0F;
    private static final float BOW_BASE_VELOCITY = 1.6F;
    private static final float BOW_VELOCITY = BOW_BASE_VELOCITY * BOW_ATTACK_RADIUS / BOW_BASE_RANGE;
    // Approximate speed an arrow loses per block traveled (arrows keep ~99% of
    // their speed per tick)
    private static final float BOW_DRAG_PER_BLOCK = 0.01F;
    // In-water speed retention for raiders that swim (0-1 like Depth Strider's
    // attribute scale): near-full land speed so moats, rivers, and waterfall
    // bases don't stall an assault. Sinking raiders keep vanilla underwater
    // footing since they walk the bottom rather than swim.
    private static final double SWIM_MOVEMENT_EFFICIENCY = 0.7;

    @Nullable
    private RaiderBossEntity squadLeader;
    // Null until finalizeSpawn assigns one; getKit() falls back to a
    // UUID-derived kit so goal checks never see null
    @Nullable
    private RaiderKit kit;
    // Last tick's running goals; diffed each server AI tick to log transitions
    private List<Goal> runningGoals = List.of();
    private List<Goal> runningTargetGoals = List.of();

    public RaiderEntity(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
        // Lets goals freeze MoveControl's horizontal steering and auto-jump
        // while they drive the mob vertically (scaffold pillar climbs)
        this.moveControl = new RaiderMoveControl(this);
        // Vanilla prices DANGER_FIRE (cells adjacent to lava or burning blocks)
        // at 8 -- identical to WATER -- so mobs hug lava lakeshores as casually
        // as they wade. Tripling it keeps the cells legal but makes a detour
        // almost always cheaper than skirting lava. DAMAGE_FIRE (standing on
        // magma/in fire) rises to match; synthetic tunnel/bridge nodes pay the
        // same floor via RaiderNodeEvaluator's lava-proximity check.
        this.setPathfindingMalus(PathType.DANGER_FIRE, 50.0F);
        this.setPathfindingMalus(PathType.DAMAGE_FIRE, 100.0F);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SKIN_ID, UNSET_SKIN_ID);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        return new RaiderNavigation(this, level);
    }

    // Full goal hierarchy, specified explicitly instead of inheriting Zombie's.
    // Priorities mirror Zombie's so tuning stays comparable; lower number = higher priority.
    @Override
    protected void registerGoals() {
        // Surface swimmers keep their head above water like most vanilla
        // mobs; sinkers skip this goal and walk the bottom instead
        this.goalSelector.addGoal(1, new FloatGoal(this) {
            @Override
            public boolean canUse() {
                return !RaiderEntity.this.sinksInWater() && super.canUse();
            }
        });
        // Execution goals
        // Leaderless raiders on an assault get cold feet before anything else
//        this.goalSelector.addGoal(2, new FleeGoal(this));  ABE TODO reenable
        // Melee and bow goals share a priority; the kit decides which may run.
        // A bowman without its bow falls back to the melee goal, mirroring
        // vanilla's reassessWeaponGoal behavior.
        this.goalSelector.addGoal(3, new ZombieAttackGoal(this, 1.0, false) {
            @Override
            public boolean canUse() {
                return !RaiderEntity.this.usesRangedAttack() && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !RaiderEntity.this.usesRangedAttack() && super.canContinueToUse();
            }
        });
        this.goalSelector.addGoal(3, new RangedBowAttackGoal<RaiderEntity>(this, 1.0, BOW_ATTACK_INTERVAL_TICKS, BOW_ATTACK_RADIUS) {
            @Override
            public boolean canUse() {
                return RaiderEntity.this.getKit().isRanged() && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return RaiderEntity.this.getKit().isRanged() && super.canContinueToUse();
            }
        });
        // Squad election uses no goal flags, so it can fire alongside anything
        // (including mid-flee) and end the retreat as soon as a boss shows up
        this.goalSelector.addGoal(3, new JoinSquadGoal(this));
        // Capturing is the raider's highest priority after combat
        this.goalSelector.addGoal(4, new CaptureFlagGoal(this));
        // Shares priority 4 so it can preempt MoveToFlagGoal immediately on
        // spawn; CaptureFlagGoal is registered first and wins ties near the
        // flag, where FollowSquadPathGoal's handoff gate keeps it idle anyway
        this.goalSelector.addGoal(4, new FollowSquadPathGoal(this, 1.0));
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

    // GoalSelector exposes no per-goal callbacks, so sample the running set
    // after each AI tick and log the diff -- that diff is what "switching
    // goals" means for a raider. Runs server-side only, where the AI lives.
    @Override
    public void tick() {
        super.tick();
        if (Config.RAIDER_GOAL_SWITCH_LOGGING.getAsBoolean() && !this.level().isClientSide) {
            this.logGoalTransitions();
        }
    }

    private void logGoalTransitions() {
        List<Goal> goals = runningGoalsOf(this.goalSelector);
        List<Goal> targetGoals = runningGoalsOf(this.targetSelector);
        // Goal has no value equality, so List.equals compares by identity --
        // which is exactly what a start/stop transition means here
        if (!goals.equals(this.runningGoals)) {
            BaseBuster.LOGGER.info("Raider {} goals: {} -> {}",
                    this.getId(), describeGoals(this.runningGoals), describeGoals(goals));
            this.runningGoals = goals;
        }
        if (!targetGoals.equals(this.runningTargetGoals)) {
            BaseBuster.LOGGER.info("Raider {} target goals: {} -> {}",
                    this.getId(), describeGoals(this.runningTargetGoals), describeGoals(targetGoals));
            this.runningTargetGoals = targetGoals;
        }
    }

    private static List<Goal> runningGoalsOf(GoalSelector selector) {
        return selector.getAvailableGoals().stream()
                .filter(WrappedGoal::isRunning)
                .map(WrappedGoal::getGoal)
                .toList();
    }

    private static String describeGoals(List<Goal> goals) {
        return goals.stream().map(RaiderEntity::goalName).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String goalName(Goal goal) {
        Class<?> cls = goal.getClass();
        // Anonymous overrides registered in registerGoals (e.g. the kit-gated
        // FloatGoal) have no simple name; report the vanilla superclass instead
        return cls.isAnonymousClass() ? cls.getSuperclass().getSimpleName() : cls.getSimpleName();
    }

    @Override
    protected boolean isSunSensitive() {
        return false;
    }

    // Death loot resolves to a per-skin table
    // (data/basebuster/loot_table/entities/raider/<skin>.json); each one nests
    // the shared kit table, which gates arrow/iron drops on the RaiderKit tag
    @Override
    protected ResourceKey<LootTable> getDefaultLootTable() {
        return this.getSkin().lootTable();
    }

    // Mob.dropCustomDeathLoot rolls equipment-drop chances on every slot, so
    // kit gear and picked-up items could drop. Overridden empty: raiders only
    // drop their loot-table contents and XP. RaiderBossEntity extends this to
    // add its victory banner, so guaranteed drops still have a hook.
    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
    }

    // Mob inflates XP by 1-3 per equipped piece; raiders always drop flat
    // zombie XP (5) regardless of kit. Zombie's baby bonus doesn't apply
    // either -- raiders are never babies.
    @Override
    protected int getBaseExperienceReward() {
        return this.xpReward;
    }

    /**
     * Whether this raider sinks in water and walks the bottom like a vanilla
     * zombie, instead of swimming at the surface. Undead-themed raiders (and
     * the zombified piglin, which is undead despite its piglin theme) sink;
     * everyone else stays afloat.
     */
    public boolean sinksInWater() {
//        RaiderSkin skin = this.getSkin();
//        return skin.theme() == RaiderSkinTheme.UNDEAD || skin == RaiderSkin.ZOMBIFIED_PIGLIN;
        return false;
    }

    // Sinking raiders don't need air, like vanilla undead; swimming raiders
    // drown like vanilla mobs. NeoForge queries this in onLivingBreathe.
    @Override
    public boolean canDrownInFluidType(FluidType fluidType) {
        return !this.sinksInWater() && super.canDrownInFluidType(fluidType);
    }

    // Raiders never convert into vanilla Drowned: conversion would replace
    // this entity, discarding its skin, kit, and squad membership
    @Override
    protected boolean convertsInWater() {
        return false;
    }

    // Raiders are on the same faction: any attack sourced from a raider —
    // melee or a stray arrow (getEntity() resolves to the shooter) — deals no
    // damage, which also keeps HurtByTargetGoal from triggering infighting.
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof RaiderEntity) {
            return false;
        }
        return super.hurt(source, amount);
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData) {
        if (this.kit == null) {
            this.kit = RaiderKit.random(this.getRandom());
        }
        // super may randomize equipment via populateDefaultEquipmentSlots;
        // run the kit afterward so its gear always wins
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
        this.kit.equip(this, this.getRandom());
        AttributeInstance maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(this.getConfiguredMaxHealth());
            this.setHealth((float) this.getConfiguredMaxHealth());
        }
        AttributeInstance moveSpeed = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (moveSpeed != null) {
            moveSpeed.setBaseValue(this.getKit().movementSpeed());
        }
        // Knockback resistance is kit-level too (e.g. tanks shrug off hits)
        AttributeInstance knockbackResist = this.getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (knockbackResist != null) {
            knockbackResist.setBaseValue(this.getKit().knockbackResistance());
        }
        // Swimmers keep most of their land speed in water; sinkers walk the
        // bottom at vanilla underwater speed
        AttributeInstance waterEfficiency = this.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY);
        if (waterEfficiency != null && !this.sinksInWater()) {
            waterEfficiency.setBaseValue(SWIM_MOVEMENT_EFFICIENCY);
        }
        return result;
    }

    // Max health this raider spawns with; subclasses (e.g. bosses) can override.
    protected double getConfiguredMaxHealth() {
        return this.getKit().maxHealth();
    }

    /** Whether the raider currently fights at range: ranged kit + weapon in hand. */
    private boolean usesRangedAttack() {
        return this.getKit().isRanged()
                && this.getMainHandItem().getItem() instanceof ProjectileWeaponItem;
    }

    /**
     * Fires an arrow at the target, mirroring {@code AbstractSkeleton}.
     * Invoked by {@link RangedBowAttackGoal} once the bow draw completes.
     */
    @Override
    public void performRangedAttack(LivingEntity target, float distanceFactor) {
        ItemStack weapon = this.getItemInHand(ProjectileUtil.getWeaponHoldingHand(this, item -> item instanceof BowItem));
        ItemStack projectile = this.getProjectile(weapon);
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, projectile, distanceFactor, weapon);
        if (weapon.getItem() instanceof ProjectileWeaponItem weaponItem) {
            arrow = weaponItem.customArrow(arrow, projectile, weapon);
        }
        double dx = target.getX() - this.getX();
        double dy = target.getY(0.3333333333333333) - arrow.getY();
        double dz = target.getZ() - this.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // Arrows bleed ~1% of their speed per block in flight. Launching at
        // (vanilla arrival speed + drag lost en route) means the arrow arrives
        // with roughly vanilla punch at any range, so close-range hits don't
        // get amplified when BOW_ATTACK_RADIUS is large. Capped by the
        // radius-scaled ceiling so extreme distances don't launch absurdly fast.
        float velocity = (float) Math.clamp(BOW_BASE_VELOCITY + BOW_DRAG_PER_BLOCK * distance, BOW_BASE_VELOCITY, BOW_VELOCITY);
        arrow.shoot(dx, dy + horizontalDistance * 0.2, dz, velocity,
                (float) (14 - this.level().getDifficulty().getId() * 4));
        this.playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level().addFreshEntity(arrow);
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
     * The skin this raider renders with. When none was assigned (spawn egg,
     * /summon), falls back to the generic zombie skin while all raiders are
     * pinned to it; restore the UUID-derived fallback when varied skins
     * come back.
     */
    public RaiderSkin getSkin() {
        RaiderSkin skin = RaiderSkin.byId(this.entityData.get(DATA_SKIN_ID));
        return skin != null ? skin : RaiderSkin.ZOMBIE;
    }

    /** Pins this raider to a specific skin; raids set one per member from their theme. */
    public void setSkin(RaiderSkin skin) {
        this.entityData.set(DATA_SKIN_ID, skin.ordinal());
    }

    /**
     * This raider's combat kit: equipment, stats, and attack style. Falls back
     * to a deterministic UUID-derived kit for entities that never went through
     * finalizeSpawn, so goal checks always have an answer.
     */
    public RaiderKit getKit() {
        RaiderKit kit = this.kit;
        return kit != null ? kit : RaiderKit.forUuid(this.getUUID());
    }

    /** Pins this raider to a specific kit. Must be called before finalizeSpawn
     *  if the kit's equipment should also be applied. */
    public void setKit(RaiderKit kit) {
        this.kit = kit;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString(SKIN_TAG, this.getSkin().name());
        tag.putString(KIT_TAG, this.getKit().name());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(SKIN_TAG, Tag.TAG_STRING)) {
            try {
                this.setSkin(RaiderSkin.valueOf(tag.getString(SKIN_TAG)));
            } catch (IllegalArgumentException ignored) {
                // Unknown skin name (e.g. from an older save); fall back to the default
            }
        }
        if (tag.contains(KIT_TAG, Tag.TAG_STRING)) {
            try {
                this.kit = RaiderKit.valueOf(tag.getString(KIT_TAG));
            } catch (IllegalArgumentException ignored) {
                // Unknown kit name (e.g. from an older save); fall back to the default
            }
        }
    }

    // ABE TODO remove this when done debugging
    @Override
    public boolean isCurrentlyGlowing() {
        return Config.RAIDER_BOSS_GLOWING.get() || super.isCurrentlyGlowing();
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
