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
 * <p>Only the boss steers at the waypoints directly. When
 * {@link Config#RAIDER_SQUAD_FORMATION_ENABLED} is on, each squad member
 * instead paths to a slot in a chevron centered on its leader (see
 * {@link #formationSlot()}), so the squad moves as a clump rather than a
 * single-file line. A slot is just a leg's destination -- the member's local
 * search still picks the one legal route across bridges and through doorways,
 * so the chevron collapses through chokepoints and re-fans afterward. A member
 * whose leader dies exits this goal and re-elects normally. With the config
 * off, members march the waypoints exactly like the boss.
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
    // Slots in the formation chevron around the leader, dealt round-robin
    private static final int FORMATION_SLOTS = 16;
    // Side-to-side spacing of each chevron row
    private static final double SLOT_LATERAL_SPACING = 1;
    // March-axis spacing between chevron rows; rows straddle the leader
    private static final double SLOT_ROW_SPACING = 1;
    // How far a member's slot may drift from the live path's aim before repathing
    private static final double SLOT_REPATH_DRIFT = 4.0;

    private List<BlockPos> waypoints = List.of();
    private int waypointIndex;
    // The flag the adopted waypoints lead to; used to detect retargets
    @Nullable
    private BlockPos pathFlag;
    // Squad members follow their leader's formation slot instead of the
    // corridor while formation mode is on; null for the boss (always a
    // waypoint-marcher) and for members when the config toggle is off
    @Nullable
    private RaiderBossEntity leader;
    // The slot position the live path was aimed at; drift past
    // SLOT_REPATH_DRIFT triggers a repath so members track the moving boss
    @Nullable
    private BlockPos lastSlotTarget;
    // This member's chevron slot, claimed from the leader when the path is adopted
    private int formationSlotIndex;

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
        // The boss is its own planner; members anchor on their leader only
        // in formation mode -- otherwise they march the corridor themselves
        this.leader = Config.RAIDER_SQUAD_FORMATION_ENABLED.get() && planner != raider
                ? planner
                : null;
        if (this.leader != null) {
            this.formationSlotIndex = this.leader.claimFormationSlot(
                    this.mob.getUUID(), FORMATION_SLOTS);
        }
        this.waypoints = planner.getSquadPath();
        this.waypointIndex = SquadPathSearch.closestIndex(this.waypoints, this.mob.blockPosition());
        this.pathFlag = flag;
        this.blockPos = flag;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (!super.canContinueToUse()
                || this.pathFlag == null
                // The inherited retarget switched flags mid-march; this path
                // no longer applies, so bail and let goals re-elect
                || !this.blockPos.equals(this.pathFlag)
                || this.mob.distanceToSqr(Vec3.atBottomCenterOf(this.pathFlag))
                        <= HANDOFF_RADIUS * HANDOFF_RADIUS) {
            return false;
        }
        if (this.leader != null) {
            // Members march on their leader rather than the corridor: stay
            // while the leader lives and still plans a path to the same flag.
            // On its death (or a live config toggle to waypoint marching),
            // goals re-elect and this goal restarts in the new mode.
            return Config.RAIDER_SQUAD_FORMATION_ENABLED.get()
                    && this.mob instanceof RaiderEntity raider
                    && raider.getSquadLeader() == this.leader
                    && this.pathFlag.equals(this.leader.getSquadPathTarget());
        }
        // Partial squad paths can end short of the flag; once the boss
        // reaches the corridor's end, ordinary partial-path chaining is
        // again the best available behavior
        return !this.atLastWaypoint();
    }

    @Override
    public void tick() {
        super.tick();
        this.advanceWaypoints();
        // The formation anchor moves: repath once the member's slot has
        // drifted far enough from the position the live path was aimed at
        if (this.leader != null
                && !this.leader.isRemoved()
                && this.lastSlotTarget != null
                && this.formationSlot().distSqr(this.lastSlotTarget)
                        > SLOT_REPATH_DRIFT * SLOT_REPATH_DRIFT) {
            this.moveMobToBlock();
        }
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
        if (this.leader != null) {
            return this.formationSlot();
        }
        return this.waypoints.get(this.waypointIndex);
    }

    @Override
    protected void moveMobToBlock() {
        if (this.leader != null && !this.leader.isRemoved()) {
            this.lastSlotTarget = this.formationSlot();
        }
        super.moveMobToBlock();
    }

    /**
     * A member's march target: a slot in a chevron centered on its leader
     * rather than a corridor waypoint. Rows of two fan out ahead of, beside,
     * and behind the boss's march direction, indexed by a slot the leader
     * deals out round-robin so every member gets an even, stable position;
     * rear rows spread wider, keeping the V readable with the boss inside it.
     * The slot is only a leg's destination -- the member's local search still finds the
     * one legal route across a bridge or through a doorway, so the chevron
     * collapses at chokepoints and re-fans afterward.
     */
    private BlockPos formationSlot() {
        Vec3 bossPos = this.leader.position();
        Vec3 forward = this.marchDirection(bossPos);
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        int slot = this.formationSlotIndex;
        // Rows straddle the boss along the march axis -- front rows lead him,
        // back rows trail -- so he marches inside the chevron, not at its point
        int row = slot / 2;
        double side = (slot & 1) == 0 ? -1.0 : 1.0;
        double midRow = (FORMATION_SLOTS / 2 - 1) / 2.0;
        Vec3 offset = right.scale(side * (row + 1) * SLOT_LATERAL_SPACING)
                .add(forward.scale((midRow - row) * SLOT_ROW_SPACING));
        return BlockPos.containing(bossPos.add(offset));
    }

    /**
     * The direction the formation chevron faces: the leader's current heading
     * while it moves, or toward the flag when it stands still (digging,
     * fighting), so members keep the flag side covered.
     */
    private Vec3 marchDirection(Vec3 bossPos) {
        Vec3 velocity = this.leader.getDeltaMovement();
        Vec3 heading = new Vec3(velocity.x, 0.0, velocity.z);
        if (heading.lengthSqr() > 1.0E-4) {
            return heading.normalize();
        }
        Vec3 toFlag = new Vec3(
                this.pathFlag.getX() + 0.5 - bossPos.x,
                0.0,
                this.pathFlag.getZ() + 0.5 - bossPos.z);
        return toFlag.lengthSqr() > 1.0E-4 ? toFlag.normalize() : new Vec3(0.0, 0.0, 1.0);
    }

    @Override
    public void stop() {
        super.stop();
        this.waypoints = List.of();
        this.waypointIndex = 0;
        this.pathFlag = null;
        this.leader = null;
        this.lastSlotTarget = null;
        this.formationSlotIndex = 0;
    }
}
