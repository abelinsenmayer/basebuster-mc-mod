package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.entities.goals.RaiderGoals;
import dev.abelinsenmayer.basebuster.pathfinding.RaiderMoveControl;
import dev.abelinsenmayer.basebuster.pathfinding.RaiderNavigation;
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
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveThroughVillageGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RangedBowAttackGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
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

public class RaiderEntity extends Zombie implements RangedAttackMob, IRaider {
    // -1 = no explicit skin; getSkin() falls back to the generic zombie
    private static final int UNSET_SKIN_ID = -1;
    private static final EntityDataAccessor<Integer> DATA_SKIN_ID =
            SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.INT);
    private static final String SKIN_TAG = "RaiderSkin";
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
    private static final double SWIM_MOVEMENT_EFFICIENCY = 0.5;

    // The composed raider state (kit, tier, squad membership, goal logging);
    // the IRaider contract forwards here so any PathfinderMob subclass can
    // carry the same behavior
    private final RaiderComponent raider = new RaiderComponent(this);

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
        this.setPathfindingMalus(PathType.DANGER_FIRE, 10.0F);
        this.setPathfindingMalus(PathType.DAMAGE_FIRE, 40.0F);
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
        // The shared raider layer (flee/squad/capture/march/wander at
        // priorities 2-6) is identical across raider species; illager hosts
        // install the same goals over their native combat table
        RaiderGoals.install(this);
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
    public void tick() {
        super.tick();
        if (Config.RAIDER_GOAL_SWITCH_LOGGING.getAsBoolean() && !this.level().isClientSide) {
            this.raider.logGoalTransitions(this.goalSelector, this.targetSelector);
        }
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

    // Faction-friendly damage (raiders can't hurt raiders, including stray
    // arrows) lives in BaseBusterEventBus's LivingIncomingDamageEvent handler,
    // so it works across every IRaider species without a per-class override.

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType, @Nullable SpawnGroupData spawnGroupData) {
        this.raider.ensureKit(this.getRandom());
        // super may randomize equipment via populateDefaultEquipmentSlots;
        // run the kit afterward so its gear always wins
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
        this.getKit().equip(this, this.getRaidTier(), this.getRandom());
        AttributeInstance maxHealth = this.getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(this.getConfiguredMaxHealth());
            this.setHealth((float) this.getConfiguredMaxHealth());
        }
        AttributeInstance moveSpeed = this.getAttribute(Attributes.MOVEMENT_SPEED);
        if (moveSpeed != null) {
            moveSpeed.setBaseValue(this.getConfiguredMovementSpeed());
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
        // Walk up full blocks instead of jumping so single-block ledges and
        // slab/stair defenses don't stall the path
        AttributeInstance stepHeight = this.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.setBaseValue(1.0);
        }
        return result;
    }

    // Max health this raider spawns with; subclasses (e.g. bosses) can override.
    protected double getConfiguredMaxHealth() {
        return this.getKit().maxHealth();
    }

    // Movement speed this raider spawns with; subclasses (e.g. bosses) can override.
    protected double getConfiguredMovementSpeed() {
        return this.getKit().movementSpeed();
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

    @Override
    public PathfinderMob mob() {
        return this;
    }

    // Whether this raider type participates in squads; bosses lead, never join
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
        tag.putString(SKIN_TAG, this.getSkin().name());
        this.raider.save(tag);
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
        this.raider.load(tag);
    }

    // Checked every frame; honors live config changes without needing a
    // potion effect that would linger in saved NBT if the config is later disabled
    @Override
    public boolean isCurrentlyGlowing() {
        return this.glowsByConfig() || super.isCurrentlyGlowing();
    }

    // The config flag granting perpetual glow; subclasses (e.g. bosses)
    // override to read their own key so the toggles stay independent
    protected boolean glowsByConfig() {
        return Config.RAIDER_GLOWING.get();
    }

    // Flag queries (isAttemptingCapture, nearestUncapturedFlag, nearestFlag)
    // come from IRaider's defaults -- they only read FlagsSavedData and the
    // host's position, so every raider species shares them.
}
