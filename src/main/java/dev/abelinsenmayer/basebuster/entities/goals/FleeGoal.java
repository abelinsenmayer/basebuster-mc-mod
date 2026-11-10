package dev.abelinsenmayer.basebuster.entities.goals;

import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.entities.RaiderEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Cold feet: a raider on an assault that cannot find a squad leader turns tail.
 *
 * <p>The raider flees the nearest player within {@value #PLAYER_FLEE_RADIUS}
 * blocks; with no player that close it instead retreats from the nearest flag not
 * owned by the raiders. Movement uses {@link Config#RAIDER_FLEE_SPEED_MULTIPLIER}
 * so the rout looks panicked.
 *
 * <p>Every {@value #DESPAWN_CHECK_INTERVAL_TICKS} ticks the raider checks whether
 * it should vanish: it despawns once it is farther than
 * {@link BaseFlagBlock#FLAG_PROTECTION_RADIUS} from the nearest flag and has no
 * line of sight to the nearest player, so fleeing stragglers don't litter the map.
 */
public class FleeGoal extends Goal {
    // A player closer than this is what the raider runs from
    private static final int PLAYER_FLEE_RADIUS = 12;
    // Nearest-player search range for the despawn line-of-sight check
    private static final double PLAYER_SCAN_RANGE = 128.0;
    // How far ahead of the raider to aim when picking a fallback flee spot
    private static final double FLEE_DISTANCE = 16.0;
    // Horizontal/vertical range for randomized flee targets
    private static final int FLEE_RANGE_HORIZONTAL = 16;
    private static final int FLEE_RANGE_VERTICAL = 7;
    // Ticks between repathing away from the threat (it may be moving too)
    private static final int REPATH_INTERVAL_TICKS = 10;
    // Ticks between despawn eligibility checks
    private static final int DESPAWN_CHECK_INTERVAL_TICKS = 40;

    private final RaiderEntity raider;
    private int repathTimestamp;
    private int despawnCheckTimestamp;

    public FleeGoal(RaiderEntity raider) {
        this.raider = raider;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // Only squad-eligible raiders flee; bosses never do, and neither do
        // raiders guarding an already-captured flag
        return this.raider.canJoinSquad()
                && this.raider.isAttemptingCapture()
                && this.raider.getSquadLeader() == null
                && this.threatPos() != null;
    }

    @Override
    public boolean canContinueToUse() {
        // Keep running until a leader is found (or the threat disappears)
        return this.canUse();
    }

    @Override
    public void start() {
        this.repathToSafety();
    }

    @Override
    public void tick() {
        if (!(this.raider.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (this.raider.tickCount - this.repathTimestamp >= REPATH_INTERVAL_TICKS) {
            this.repathToSafety();
        }
        if (this.raider.tickCount - this.despawnCheckTimestamp >= DESPAWN_CHECK_INTERVAL_TICKS) {
            this.despawnCheckTimestamp = this.raider.tickCount;
            this.checkDespawn(serverLevel);
        }
    }

    /** The position the raider flees from: the nearest player, else the target flag. */
    @Nullable
    private Vec3 threatPos() {
        Player player = this.raider.level().getNearestPlayer(this.raider, PLAYER_FLEE_RADIUS);
        if (player != null) {
            return player.position();
        }
        BlockPos flag = this.raider.nearestUncapturedFlag();
        return flag != null ? Vec3.atCenterOf(flag) : null;
    }

    private void repathToSafety() {
        this.repathTimestamp = this.raider.tickCount;
        Vec3 threat = this.threatPos();
        if (threat == null) {
            return;
        }
        Vec3 fleePos = DefaultRandomPos.getPosAway(this.raider, FLEE_RANGE_HORIZONTAL, FLEE_RANGE_VERTICAL, threat);
        if (fleePos == null) {
            // Pathfinder found no reachable escape spot; bolt in a straight line
            Vec3 away = this.raider.position().subtract(threat);
            if (away.lengthSqr() < 1.0E-4) {
                away = new Vec3(1, 0, 0);
            }
            fleePos = this.raider.position().add(away.normalize().scale(FLEE_DISTANCE));
        }
        this.raider.getNavigation()
                .moveTo(fleePos.x, fleePos.y, fleePos.z, Config.RAIDER_FLEE_SPEED_MULTIPLIER.get());
    }

    /**
     * The raider vanishes once it is outside the nearest flag's protection radius
     * and the nearest player cannot see it.
     */
    private void checkDespawn(ServerLevel level) {
        BlockPos flag = this.raider.nearestFlag();
        if (flag != null
                && this.raider.blockPosition().distSqr(flag)
                        <= (double) BaseFlagBlock.FLAG_PROTECTION_RADIUS * BaseFlagBlock.FLAG_PROTECTION_RADIUS) {
            return;
        }
        Player player = level.getNearestPlayer(this.raider, PLAYER_SCAN_RANGE);
        if (player != null && this.raider.hasLineOfSight(player)) {
            return;
        }
        this.raider.discard();
    }
}
