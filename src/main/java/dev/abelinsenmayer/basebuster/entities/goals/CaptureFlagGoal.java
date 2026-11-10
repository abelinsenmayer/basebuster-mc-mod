package dev.abelinsenmayer.basebuster.entities.goals;

import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.EnumSet;

/**
 * The actual flag-capture activity: while a raider stands next to a flag not yet
 * owned by the raider faction, it beats a heartbeat on the flag's block entity
 * each tick via {@link BaseFlagBlockEntity#tickCapture}. The entity owns all
 * progress bookkeeping -- counting participants, scaling the capture rate, and
 * resetting progress when every capturer stops.
 *
 * <p>Movement to reach the flag is {@link MoveToFlagGoal}'s job; this goal only
 * runs in the handoff range where that goal considers the flag reached. It holds
 * the MOVE flag so nothing else steers the raider while it captures.
 */
public class CaptureFlagGoal extends Goal {
    // Distance from the flag center at which a raider can begin capturing
    private static final double CAPTURE_RADIUS = 3.0;
    // Larger exit radius gives hysteresis so the goal doesn't flicker off when
    // the raider is bumped or stands at the edge of the capture ring
    private static final double EXIT_RADIUS = 4.0;
    // While past CAPTURE_RADIUS but still inside EXIT_RADIUS, drift back in
    private static final double REAPPROACH_SPEED = 1.0;

    private final PathfinderMob mob;
    @Nullable
    private BlockPos flagPos;

    public CaptureFlagGoal(PathfinderMob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        BlockPos flag = this.nearestUncapturedFlag();
        if (flag == null) {
            return false;
        }
        if (this.mob.distanceToSqr(flag.getX() + 0.5, flag.getY() + 0.5, flag.getZ() + 0.5)
                > CAPTURE_RADIUS * CAPTURE_RADIUS) {
            return false;
        }
        this.flagPos = flag;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.flagPos == null || !(this.mob.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (!(serverLevel.getBlockState(this.flagPos).getBlock() instanceof BaseFlagBlock)) {
            return false;
        }
        if (FlagsSavedData.getFromLevel(serverLevel).getCapturedFlagLocations().contains(this.flagPos)) {
            return false;
        }
        return this.mob.distanceToSqr(
                this.flagPos.getX() + 0.5, this.flagPos.getY() + 0.5, this.flagPos.getZ() + 0.5)
                <= EXIT_RADIUS * EXIT_RADIUS;
    }

    @Override
    public void start() {
        this.mob.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (this.flagPos == null || !(this.mob.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        double distanceSqr = this.mob.distanceToSqr(
                this.flagPos.getX() + 0.5, this.flagPos.getY() + 0.5, this.flagPos.getZ() + 0.5);
        if (distanceSqr > CAPTURE_RADIUS * CAPTURE_RADIUS) {
            // Shoved to the edge of the ring: step back in rather than dropping
            // the goal and bouncing to a full repath
            this.mob.getNavigation()
                    .moveTo(this.flagPos.getX() + 0.5, this.flagPos.getY(), this.flagPos.getZ() + 0.5, REAPPROACH_SPEED);
            return;
        }

        this.mob.getNavigation().stop();
        this.mob.getLookControl()
                .setLookAt(this.flagPos.getX() + 0.5, this.flagPos.getY() + 0.5, this.flagPos.getZ() + 0.5);

        BlockEntity blockEntity = serverLevel.getBlockEntity(this.flagPos);
        if (blockEntity instanceof BaseFlagBlockEntity flagEntity) {
            flagEntity.tickCapture(this.mob.getUUID());
        }

        if (this.mob.getRandom().nextInt(10) == 0) {
            this.mob.swing(InteractionHand.MAIN_HAND);
        }
    }

    @Override
    public void stop() {
        this.flagPos = null;
    }

    /**
     * The closest flag not yet owned by the raider faction. Raider-owned flags
     * are read from {@link FlagsSavedData} so flags whose chunks are still
     * unloading are never offered as capture targets.
     */
    @Nullable
    private BlockPos nearestUncapturedFlag() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel)) {
            return null;
        }

        FlagsSavedData flagsSavedData = FlagsSavedData.getFromLevel(serverLevel);
        BlockPos mobPos = this.mob.blockPosition();
        return flagsSavedData.getFlagLocations().stream()
                .filter(pos -> !flagsSavedData.getCapturedFlagLocations().contains(pos))
                .filter(this.mob::isWithinRestriction)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(mobPos)))
                .orElse(null);
    }
}
