package dev.abelinsenmayer.basebuster.entities.goals;

import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.entities.IRaider;
import dev.abelinsenmayer.basebuster.entities.IRaiderBoss;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;

import javax.annotation.Nullable;
import java.util.Comparator;

/**
 * Squad election: a raider on an assault (see {@link IRaider#isAttemptingCapture})
 * periodically searches for the nearest {@link IRaiderBoss} within
 * {@link Config#RAIDER_SQUAD_SEARCH_RADIUS} and adopts it as its leader.
 *
 * <p>The goal declares no {@link Goal.Flag}s, so the search can run concurrently
 * with any other goal -- most importantly while {@link FleeGoal} is active, letting
 * a newly arrived boss end the retreat immediately. Leadership is conferred in
 * {@link #start()} and the goal finishes the same tick.
 */
public class JoinSquadGoal<T extends PathfinderMob & IRaider> extends Goal {
    // Ticks between boss searches while the raider has no leader
    private static final int SEARCH_INTERVAL_TICKS = 20;

    private final T raider;
    private int searchCooldown;
    @Nullable
    private IRaiderBoss electedLeader;

    public JoinSquadGoal(T raider) {
        this.raider = raider;
    }

    @Override
    public boolean canUse() {
        // Only squad members on an assault look for a leader, and only once
        if (!this.raider.canJoinSquad()
                || !this.raider.isAttemptingCapture()
                || this.raider.getSquadLeader() != null) {
            return false;
        }
        if (--this.searchCooldown > 0) {
            return false;
        }
        this.searchCooldown = SEARCH_INTERVAL_TICKS;
        this.electedLeader = this.findNearestBoss();
        return this.electedLeader != null;
    }

    @Override
    public boolean canContinueToUse() {
        // start() already handed out the leadership; nothing to sustain
        return false;
    }

    @Override
    public void start() {
        this.raider.setSquadLeader(this.electedLeader);
        this.electedLeader = null;
    }

    @Nullable
    private IRaiderBoss findNearestBoss() {
        double radius = Config.RAIDER_SQUAD_SEARCH_RADIUS.get();
        return IRaiderBoss.nearby(this.raider.level(), this.raider.getBoundingBox().inflate(radius))
                .stream()
                .filter(boss -> !boss.mob().isRemoved())
                .min(Comparator.comparingDouble(boss -> boss.mob().distanceToSqr(this.raider)))
                .orElse(null);
    }
}
