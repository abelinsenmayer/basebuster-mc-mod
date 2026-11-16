package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

import javax.annotation.Nullable;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The state and shared logic behind {@link IRaider}: squad membership, the
 * combat kit, the raid tier, and goal-transition logging. Concrete raider
 * entity classes hold one instance and forward their {@link IRaider}
 * implementations to it, which is what lets the same behavior attach to any
 * {@link PathfinderMob} subclass rather than only {@code Zombie} children.
 *
 * <p>Construct it as an entity field initializer; nothing it does may run
 * during the entity constructor ({@code registerGoals}/{@code
 * defineSynchedData} fire first), so lazy access is safe.
 */
public final class RaiderComponent {
    private static final String KIT_TAG = "RaiderKit";
    private static final String RAID_TIER_TAG = "RaidTier";

    private final PathfinderMob mob;
    @Nullable
    private IRaiderBoss squadLeader;
    // Null until finalizeSpawn assigns one; getKit() falls back to a
    // UUID-derived kit so goal checks never see null
    @Nullable
    private RaiderKit kit;
    // The tier of the raid this raider spawned in, which scales its kit's
    // gear quality. Tier 1 (the weakest loadout) for egg/summon spawns,
    // which aren't part of a raid
    private int raidTier = 1;
    // Last tick's running goals; diffed each server AI tick to log transitions
    private List<Goal> runningGoals = List.of();
    private List<Goal> runningTargetGoals = List.of();

    public RaiderComponent(PathfinderMob mob) {
        this.mob = mob;
    }

    @Nullable
    public IRaiderBoss getSquadLeader() {
        if (this.squadLeader != null && this.squadLeader.mob().isRemoved()) {
            this.squadLeader = null;
        }
        return this.squadLeader;
    }

    public void setSquadLeader(@Nullable IRaiderBoss squadLeader) {
        this.squadLeader = squadLeader;
    }

    /**
     * This raider's combat kit: equipment, stats, and attack style. Falls back
     * to a deterministic UUID-derived kit for entities that never went through
     * finalizeSpawn, so goal checks always have an answer.
     */
    public RaiderKit getKit() {
        RaiderKit kit = this.kit;
        return kit != null ? kit : RaiderKit.forUuid(this.mob.getUUID());
    }

    public void setKit(RaiderKit kit) {
        this.kit = kit;
    }

    /** Assigns a random kit if none was pinned; called at the top of finalizeSpawn. */
    public void ensureKit(RandomSource random) {
        if (this.kit == null) {
            this.kit = RaiderKit.random(random);
        }
    }

    public int getRaidTier() {
        return this.raidTier;
    }

    public void setRaidTier(int raidTier) {
        this.raidTier = raidTier;
    }

    // GoalSelector exposes no per-goal callbacks, so sample the running set
    // after each AI tick and log the diff -- that diff is what "switching
    // goals" means for a raider. Runs server-side only, where the AI lives.
    public void logGoalTransitions(GoalSelector goalSelector, GoalSelector targetSelector) {
        List<Goal> goals = runningGoalsOf(goalSelector);
        List<Goal> targetGoals = runningGoalsOf(targetSelector);
        // Goal has no value equality, so List.equals compares by identity --
        // which is exactly what a start/stop transition means here
        if (!goals.equals(this.runningGoals)) {
            BaseBuster.LOGGER.info("Raider {} goals: {} -> {}",
                    this.mob.getId(), describeGoals(this.runningGoals), describeGoals(goals));
            this.runningGoals = goals;
        }
        if (!targetGoals.equals(this.runningTargetGoals)) {
            BaseBuster.LOGGER.info("Raider {} target goals: {} -> {}",
                    this.mob.getId(), describeGoals(this.runningTargetGoals), describeGoals(targetGoals));
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
        return goals.stream().map(RaiderComponent::goalName).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String goalName(Goal goal) {
        Class<?> cls = goal.getClass();
        // Anonymous overrides registered in registerGoals (e.g. the kit-gated
        // FloatGoal) have no simple name; report the vanilla superclass instead
        return cls.isAnonymousClass() ? cls.getSuperclass().getSimpleName() : cls.getSimpleName();
    }

    /** Writes kit and raid tier; the host adds its own tags (e.g. skin). */
    public void save(CompoundTag tag) {
        tag.putString(KIT_TAG, this.getKit().name());
        tag.putInt(RAID_TIER_TAG, this.raidTier);
    }

    public void load(CompoundTag tag) {
        if (tag.contains(KIT_TAG, Tag.TAG_STRING)) {
            try {
                this.kit = RaiderKit.valueOf(tag.getString(KIT_TAG));
            } catch (IllegalArgumentException ignored) {
                // Unknown kit name (e.g. from an older save); fall back to the default
            }
        }
        if (tag.contains(RAID_TIER_TAG)) {
            this.raidTier = tag.getInt(RAID_TIER_TAG);
        }
    }
}
