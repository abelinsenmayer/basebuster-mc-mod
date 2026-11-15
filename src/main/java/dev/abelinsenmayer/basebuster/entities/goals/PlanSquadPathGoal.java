package dev.abelinsenmayer.basebuster.entities.goals;

import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.entities.RaiderBossEntity;
import dev.abelinsenmayer.basebuster.pathfinding.PathSearchScheduler;
import dev.abelinsenmayer.basebuster.pathfinding.ResumablePathSearch;
import dev.abelinsenmayer.basebuster.pathfinding.SquadPathSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;

/**
 * Squad route planning: periodically runs one long-range path search from the
 * boss to its target flag and stores a decimated waypoint list on the boss,
 * which {@link FollowSquadPathGoal} hands to the boss and every squad member.
 *
 * <p>The search is the expensive kind ordinary navigation can't afford per
 * raider, so {@link SquadPathSearch} bounds it by distance band, loaded-chunk
 * coverage, and a wall-clock time limit, and this goal fires it only when the stored path
 * is missing/stale or the refresh cooldown has elapsed. The search itself is
 * stepped across later ticks by {@link PathSearchScheduler}, so the goal only
 * submits work; it declares no {@link Goal.Flag}s and returns to idle while
 * the search runs -- the same pattern as {@link JoinSquadGoal}.
 *
 * <p><b>Currently dormant</b>: not registered in
 * {@link RaiderBossEntity#registerGoals} -- squad routes are assigned at raid
 * spawn only and bosses never re-plan on their own. Kept for the planned
 * world-load re-plan case.
 */
public class PlanSquadPathGoal extends Goal {
    // Random spread on the refresh interval so multi-boss raids don't run
    // their expensive searches on the same tick
    private static final int REFRESH_JITTER_TICKS = 100;

    private final RaiderBossEntity boss;
    private int cooldown;
    @Nullable
    private BlockPos plannedFor;
    // Guards canUse while a scheduled search is still running; cleared by its
    // completion callback (never on cancellation, but a cancelled search means
    // the boss is gone anyway)
    private boolean searchPending;

    public PlanSquadPathGoal(RaiderBossEntity boss) {
        this.boss = boss;
    }

    @Override
    public boolean canUse() {
        if (!Config.RAIDER_SQUAD_PATHING_ENABLED.get()
                || !(this.boss.level() instanceof ServerLevel)
                || this.searchPending) {
            return false;
        }
        BlockPos flag = this.boss.nearestUncapturedFlag();
        if (flag == null || !SquadPathSearch.withinSearchRange(this.boss, flag)) {
            return false;
        }
        boolean pathValid = flag.equals(this.boss.getSquadPathTarget())
                && !this.boss.getSquadPath().isEmpty();
        if (pathValid && --this.cooldown > 0) {
            return false;
        }
        this.cooldown = Config.RAIDER_SQUAD_PATHING_REFRESH_TICKS.get()
                + this.boss.getRandom().nextInt(REFRESH_JITTER_TICKS);
        this.plannedFor = flag;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        // start() submitted the search; the scheduler finishes it over later ticks
        return false;
    }

    @Override
    public void start() {
        BlockPos flag = this.plannedFor;
        this.plannedFor = null;
        if (flag == null) {
            return;
        }
        ResumablePathSearch search = SquadPathSearch.beginSearchToFlag(this.boss, flag);
        if (search == null) {
            // Guardrails rejected the search outright
            if (!flag.equals(this.boss.getSquadPathTarget())) {
                // The retargeted flag got no path: drop waypoints that would
                // march the squad toward a different flag
                this.boss.clearSquadPath();
            }
            return;
        }
        this.searchPending = true;
        PathSearchScheduler.schedule(search, "squad path to flag " + flag, this.boss::isAlive, path -> {
            this.searchPending = false;
            if (path != null && flag.equals(this.boss.nearestUncapturedFlag())) {
                this.boss.setSquadPath(SquadPathSearch.decimate(path, false), flag);
            } else if (path == null && !flag.equals(this.boss.getSquadPathTarget())) {
                // Same retarget-drop rule as the synchronous path
                this.boss.clearSquadPath();
            }
        });
    }
}
