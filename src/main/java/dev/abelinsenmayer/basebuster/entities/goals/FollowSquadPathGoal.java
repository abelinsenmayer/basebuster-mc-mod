package dev.abelinsenmayer.basebuster.entities.goals;

import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.entities.RaiderBossEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderEntity;
import dev.abelinsenmayer.basebuster.pathfinding.SquadPathSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Marches along the rough waypoint path the squad's planner produced (see
 * {@link PlanSquadPathGoal}) instead of re-running a full search toward a
 * distant flag every repath. Waypoints sit ~10 path nodes apart; getting
 * within {@link #WAYPOINT_REACH} of one advances to the next, so each leg is
 * a short local search ordinary navigation completes quickly -- and one long
 * boss search replaces the per-raider chain of partial searches.
 *
 * <p>Extends {@link MoveToFlagGoal} to reuse its retargeting, door-opening,
 * tunneling, and scaffolding machinery unchanged: {@code blockPos} still holds
 * the flag for validity checks, while {@link #getMoveToTarget()} steers at the
 * current waypoint. Inside {@link #HANDOFF_RADIUS} of the flag the goal stays
 * out of the way so {@code MoveToFlagGoal} and {@code CaptureFlagGoal} handle
 * the approach.
 */
public class FollowSquadPathGoal extends MoveToFlagGoal {
    // Getting this close to a waypoint counts as reaching it
    private static final double WAYPOINT_REACH = 5.0;
    // Inside this distance of the flag, ordinary pathing takes over
    private static final double HANDOFF_RADIUS = 32.0;

    private List<BlockPos> waypoints = List.of();
    private int waypointIndex;
    // The flag the adopted waypoints lead to; used to detect retargets
    @Nullable
    private BlockPos pathFlag;

    public FollowSquadPathGoal(PathfinderMob mob, double speedModifier) {
        super(mob, speedModifier);
    }

    @Override
    protected boolean findNearestBlock() {
        if (!Config.RAIDER_SQUAD_PATHING_ENABLED.get()
                || !(this.mob instanceof RaiderEntity raider)) {
            return false;
        }
        BlockPos flag = raider.nearestUncapturedFlag();
        if (flag == null) {
            return false;
        }
        // The boss marches on its own plan; members march on their leader's.
        // The stored list is an immutable snapshot, so adopting it survives
        // the planner's death or later refreshes.
        RaiderBossEntity planner = raider instanceof RaiderBossEntity boss
                ? boss
                : raider.getSquadLeader();
        if (planner == null
                || planner.getSquadPath().isEmpty()
                || !flag.equals(planner.getSquadPathTarget())) {
            return false;
        }
        // Close enough that a normal bounded search reaches the flag directly
        if (this.mob.distanceToSqr(Vec3.atBottomCenterOf(flag)) <= HANDOFF_RADIUS * HANDOFF_RADIUS) {
            return false;
        }
        this.waypoints = planner.getSquadPath();
        this.waypointIndex = SquadPathSearch.closestIndex(this.waypoints, this.mob.blockPosition());
        this.pathFlag = flag;
        this.blockPos = flag;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return super.canContinueToUse()
                && this.pathFlag != null
                // The inherited retarget switched flags mid-march; this path
                // no longer applies, so bail and let goals re-elect
                && this.blockPos.equals(this.pathFlag)
                && this.mob.distanceToSqr(Vec3.atBottomCenterOf(this.pathFlag))
                        > HANDOFF_RADIUS * HANDOFF_RADIUS
                // Partial squad paths can end short of the flag; once the
                // raider reaches the corridor's end, ordinary partial-path
                // chaining is again the best available behavior
                && !this.atLastWaypoint();
    }

    @Override
    public void tick() {
        super.tick();
        this.advanceWaypoints();
    }

    /**
     * Moves the waypoint cursor forward past reached or overshot waypoints
     * and repaths to the new target. The cursor only advances, so an off-
     * corridor raider joins the nearest ahead waypoint rather than doubling
     * back.
     */
    private void advanceWaypoints() {
        int index = this.waypointIndex;
        while (index < this.waypoints.size() - 1) {
            BlockPos current = this.waypoints.get(index);
            BlockPos next = this.waypoints.get(index + 1);
            double distCurrent = this.mob.distanceToSqr(Vec3.atBottomCenterOf(current));
            if (distCurrent > WAYPOINT_REACH * WAYPOINT_REACH
                    && this.mob.distanceToSqr(Vec3.atBottomCenterOf(next)) >= distCurrent) {
                break;
            }
            index++;
        }
        if (index != this.waypointIndex) {
            this.waypointIndex = index;
            this.moveMobToBlock();
        }
    }

    private boolean atLastWaypoint() {
        return this.waypointIndex >= this.waypoints.size() - 1
                && this.mob.distanceToSqr(Vec3.atBottomCenterOf(this.waypoints.get(this.waypointIndex)))
                        <= WAYPOINT_REACH * WAYPOINT_REACH;
    }

    @Override
    protected BlockPos getMoveToTarget() {
        return this.waypoints.get(this.waypointIndex);
    }

    @Override
    public void stop() {
        super.stop();
        this.waypoints = List.of();
        this.waypointIndex = 0;
        this.pathFlag = null;
    }
}
