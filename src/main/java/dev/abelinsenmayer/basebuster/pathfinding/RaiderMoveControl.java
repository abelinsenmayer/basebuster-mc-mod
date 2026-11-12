package dev.abelinsenmayer.basebuster.pathfinding;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;

/**
 * MoveControl that can be told to ignore the pending wanted position for a
 * tick.
 *
 * <p>{@link net.minecraft.world.entity.ai.navigation.PathNavigation#tick()}
 * re-issues {@code setWantedPosition} at the next path node every tick while a
 * path is live. When the node is in the mob's own column (scaffold pillar
 * ascent/descent), that produces full-speed horizontal oscillation, and
 * {@link MoveControl#tick()} additionally auto-jumps toward the node overhead
 * -- the combination that walks a climbing raider off its scaffold. Goals
 * driving the mob vertically call {@link #holdPosition()} so none of that
 * steering or jumping applies.
 */
public class RaiderMoveControl extends MoveControl {
    private boolean holdPosition;

    public RaiderMoveControl(Mob mob) {
        super(mob);
    }

    /**
     * Suppresses the next {@link #tick()}: the pending wanted position is
     * dropped back to {@code WAIT} (which also clears a stale {@code JUMPING}
     * operation, which otherwise keeps steering at full speed for the whole
     * airborne climb) and no movement input is produced. Must be re-issued
     * each tick the hold should persist, since navigation refreshes the
     * wanted position every tick.
     */
    public void holdPosition() {
        this.holdPosition = true;
    }

    @Override
    public void tick() {
        if (this.holdPosition) {
            this.holdPosition = false;
            this.operation = Operation.WAIT;
            this.mob.setSpeed(0.0F);
            this.mob.setXxa(0.0F);
            this.mob.setZza(0.0F);
            return;
        }
        super.tick();
    }
}
