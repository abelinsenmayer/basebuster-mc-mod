package dev.abelinsenmayer.basebuster.entities.goals;

import dev.abelinsenmayer.basebuster.entities.IRaider;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.GoalSelector;

/**
 * The shared raider goal layer: squad membership and flag assault AI, layered
 * onto whatever combat/survival goals the host species already registered in
 * its own {@code registerGoals}. Zombie raiders install it after their kit-
 * driven attack goals; illager raiders install it after the full native table
 * (melee/crossbow/spells), which keeps each species' combat style while all
 * of them march, follow squad routes, capture, and scatter identically.
 *
 * <p>Priorities sit below typical combat slots (0-4) so a native attack goal
 * always wins when a target exists, and above vanilla strolls (8+) so flag
 * duty wins when it doesn't. Bosses run the same layer: their
 * {@link IRaider#canJoinSquad} gate keeps FleeGoal/JoinSquadGoal idle.
 */
public final class RaiderGoals {
    private static final int FLEE_PRIORITY = 2;
    private static final int JOIN_SQUAD_PRIORITY = 3;
    private static final int CAPTURE_FLAG_PRIORITY = 4;
    private static final int FOLLOW_PATH_PRIORITY = 4;
    private static final int MOVE_TO_FLAG_PRIORITY = 5;
    private static final int WANDER_PRIORITY = 6;

    private RaiderGoals() {
    }

    /** Adds the raider squad+flag goals to {@code raider}'s goal selector. */
    public static <T extends PathfinderMob & IRaider> void install(T raider) {
        GoalSelector goals = raider.goalSelector;
        goals.addGoal(FLEE_PRIORITY, new FleeGoal<>(raider));
        goals.addGoal(JOIN_SQUAD_PRIORITY, new JoinSquadGoal<>(raider));
        goals.addGoal(CAPTURE_FLAG_PRIORITY, new CaptureFlagGoal(raider));
        goals.addGoal(FOLLOW_PATH_PRIORITY, new FollowSquadPathGoal(raider, 1.0));
        goals.addGoal(MOVE_TO_FLAG_PRIORITY, new MoveToFlagGoal(raider, 1.0));
        goals.addGoal(WANDER_PRIORITY, new WanderNearFlagGoal(raider, 1.0));
    }
}
