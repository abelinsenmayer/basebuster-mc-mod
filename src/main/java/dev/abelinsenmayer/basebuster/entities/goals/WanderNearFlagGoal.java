package dev.abelinsenmayer.basebuster.entities.goals;

import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;

/**
 * Loitering behavior for raiders standing in the protected area of a flag their
 * faction already owns. Instead of pathing toward the flag itself, the raider
 * picks random spots inside {@link BaseFlagBlock#FLAG_PROTECTION_RADIUS} of it
 * and strolls between them.
 */
public class WanderNearFlagGoal extends Goal {
    // Ticks between picking a new wander target
    private static final int WANDER_INTERVAL_TICKS = 100;
    // Maximum manhattan search distance from the raider for a wander spot; clamped
    // to the flag's protection radius by the projection below
    private static final int WANDER_RANGE = 10;

    private final PathfinderMob mob;
    private final double speedModifier;
    @Nullable
    private BlockPos flagPos;
    private double wantedX;
    private double wantedY;
    private double wantedZ;
    private int interval;

    public WanderNearFlagGoal(PathfinderMob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.interval = WANDER_INTERVAL_TICKS;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        BlockPos flag = this.nearestCapturedFlag();
        if (flag == null) {
            return false;
        }

        // The raider only loiters when already inside the flag's protection area
        if (this.mob.blockPosition().distSqr(flag)
                > (double) BaseFlagBlock.FLAG_PROTECTION_RADIUS * BaseFlagBlock.FLAG_PROTECTION_RADIUS) {
            return false;
        }

        if (this.interval > 0) {
            this.interval--;
            return false;
        }

        Vec3 wanderPos = this.pickWanderPos(flag);
        if (wanderPos == null) {
            return false;
        }

        this.flagPos = flag;
        this.wantedX = wanderPos.x;
        this.wantedY = wanderPos.y;
        this.wantedZ = wanderPos.z;
        this.interval = WANDER_INTERVAL_TICKS;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        this.mob.getNavigation().moveTo(this.wantedX, this.wantedY, this.wantedZ, this.speedModifier);
    }

    @Override
    public void stop() {
        this.flagPos = null;
        // Repopulate the delay immediately so the raider doesn't instantly pick
        // a new wander target and keep this goal running forever
        this.interval = WANDER_INTERVAL_TICKS;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    /**
     * A random reachable spot near the raider, projected back inside the flag's
     * protection radius if the roll landed outside it.
     */
    @Nullable
    private Vec3 pickWanderPos(BlockPos flag) {
        Vec3 random = DefaultRandomPos.getPos(this.mob, WANDER_RANGE, 7);
        if (random == null) {
            return null;
        }

        // Project the rolled position onto the flag's protection sphere so the
        // raider keeps patrolling the captured base instead of drifting away
        Vec3 flagCenter = Vec3.atCenterOf(flag);
        Vec3 offset = random.subtract(flagCenter);
        double radius = BaseFlagBlock.FLAG_PROTECTION_RADIUS;
        if (offset.length() > radius) {
            random = flagCenter.add(offset.normalize().scale(radius - 1.0));
        }
        return random;
    }

    @Nullable
    private BlockPos nearestCapturedFlag() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel)) {
            return null;
        }

        BlockPos mobPos = this.mob.blockPosition();
        return FlagsSavedData.getFromLevel(serverLevel).getCapturedFlagLocations().stream()
                .filter(this.mob::isWithinRestriction)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(mobPos)))
                .orElse(null);
    }
}
