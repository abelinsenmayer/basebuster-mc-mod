package dev.abelinsenmayer.basebuster.entities.goals;

import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.entities.RaiderBossEntity;
import dev.abelinsenmayer.basebuster.pathfinding.SquadPathSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;

/**
 * Squad route planning: periodically runs one long-range path search from the
 * boss to its target flag and stores a decimated waypoint list on the boss,
 * which {@link FollowSquadPathGoal} hands to the boss and every squad member.
 *
 * <p>The search is the expensive kind ordinary navigation can't afford per
 * raider, so {@link SquadPathSearch} bounds it by distance band, loaded-chunk
 * coverage, and node budget, and this goal fires it only when the stored path
 * is missing/stale or the refresh cooldown has elapsed. It declares no
 * {@link Goal.Flag}s, so it can run alongside whatever the boss is doing and
 * completes the same tick it starts -- the same pattern as
 * {@link JoinSquadGoal}.
 */
public class PlanSquadPathGoal extends Goal {
    // Random spread on the refresh interval so multi-boss raids don't run
    // their expensive searches on the same tick
    private static final int REFRESH_JITTER_TICKS = 100;

    private final RaiderBossEntity boss;
    private int cooldown;
    @Nullable
    private BlockPos plannedFor;

    public PlanSquadPathGoal(RaiderBossEntity boss) {
        this.boss = boss;
    }

    @Override
    public boolean canUse() {
        if (!Config.RAIDER_SQUAD_PATHING_ENABLED.get()
                || !(this.boss.level() instanceof ServerLevel)) {
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
        // start() ran the whole search; nothing to sustain
        return false;
    }

    @Override
    public void start() {
        BlockPos flag = this.plannedFor;
        this.plannedFor = null;
        if (flag == null) {
            return;
        }
        Path path = SquadPathSearch.searchToFlag(this.boss, flag);
        if (path != null) {
            this.boss.setSquadPath(SquadPathSearch.decimate(path, false), flag);
        } else if (!flag.equals(this.boss.getSquadPathTarget())) {
            // The retargeted flag got no path: drop waypoints that would march
            // the squad toward a different flag
            this.boss.clearSquadPath();
        }
    }
}
