package dev.abelinsenmayer.basebuster.entities.goals;

import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.MoveToBlockGoal;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Paths the mob toward the closest registered {@link BaseFlagBlock}.
 *
 * <p>Target selection comes from {@link FlagsSavedData}, which tracks flag positions
 * globally, rather than the local block scan vanilla {@link MoveToBlockGoal} performs.
 * A raider will therefore march toward a flag from any distance. The pathfinder only
 * reaches a limited number of nodes per search, so faraway flags are approached in
 * steps each time the path is recalculated.
 */
public class MoveToFlagGoal extends MoveToBlockGoal {
    // How often the running goal re-checks FlagsSavedData for a closer flag
    private static final int RETARGET_INTERVAL_TICKS = 40;
    // Floor on repath attempts triggered by an exhausted path, so a mob that can't
    // path at all doesn't run a full A* search every tick
    private static final int IDLE_REPATH_INTERVAL_TICKS = 10;

    // Path nodes ahead of the mob checked for diggable obstructions
    private static final int TUNNEL_LOOKAHEAD_NODES = 4;
    // Max manhattan distance from the mob at which it will start breaking a node
    private static final int TUNNEL_BREAK_REACH = 2;
    // Ticks spent breaking through a node before block hardness is factored in
    private static final int TUNNEL_BREAK_BASE_TICKS = 60;
    // Additional break ticks per point of hardness in the obstructing column
    private static final float TUNNEL_BREAK_TICKS_PER_HARDNESS = 10.0F;
    // Upward velocity while the mob climbs inside a scaffolding column
    private static final float SCAFFOLD_CLIMB_SPEED = 0.25F;

    private int idleRepathTimestamp = -IDLE_REPATH_INTERVAL_TICKS;
    @Nullable
    private BlockPos breakingNodePos;
    private int breakTime;
    private int lastBreakProgress = -1;

    public MoveToFlagGoal(PathfinderMob mob, double speedModifier) {
        // searchRange is unused: findNearestBlock() consults FlagsSavedData instead of scanning blocks
        super(mob, speedModifier, 1);
    }

    @Override
    protected boolean findNearestBlock() {
        BlockPos nearestFlag = this.nearestFlag();
        if (nearestFlag == null) {
            return false;
        }

        this.blockPos = nearestFlag;
        return true;
    }

    @Nullable
    private BlockPos nearestFlag() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel)) {
            return null;
        }

        BlockPos mobPos = this.mob.blockPosition();
        return FlagsSavedData.getFromLevel(serverLevel).getFlagLocations().stream()
                .filter(this.mob::isWithinRestriction)
                .min(Comparator.comparingDouble(flagPos -> flagPos.distSqr(mobPos)))
                .orElse(null);
    }

    @Override
    public void tick() {
        super.tick();
        // MoveToBlockGoal never re-evaluates its target while running, so without this
        // a raider would march on to its original flag for up to 1200 ticks (or camp
        // there for up to maxStayTicks) even if a closer flag is placed meanwhile
        if (this.tryTicks % RETARGET_INTERVAL_TICKS == 0) {
            this.retargetNearestFlag();
        }
        this.tickTunneling();
        this.tickScaffolding();
    }

    /**
     * Door-break-style digging: when the path leads through an obstructed node
     * (emitted by the tunneling node evaluator), spend a hardness-scaled time
     * breaking the blocking column, then remove the blocks and keep walking.
     */
    private void tickTunneling() {
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return;
        }

        BlockPos blocking = this.blockingNode(level);
        if (blocking == null) {
            this.resetBreaking(level);
            return;
        }

        if (!blocking.equals(this.breakingNodePos)) {
            this.resetBreaking(level);
            this.breakingNodePos = blocking;
            this.breakTime = 0;
        }

        List<BlockPos> blocks = this.blocksToBreak(level, blocking);
        if (blocks.isEmpty()) {
            this.resetBreaking(level);
            return;
        }

        int requiredTicks = this.requiredBreakTicks(level, blocks);
        this.mob.getLookControl()
                .setLookAt(blocking.getX() + 0.5, blocking.getY() + 0.5, blocking.getZ() + 0.5);
        if (this.mob.getRandom().nextInt(20) == 0) {
            level.levelEvent(1019, blocking, 0); // zombie pounding on a door
            this.mob.swing(InteractionHand.MAIN_HAND);
        }

        this.breakTime++;
        int progress = Math.min((int)(this.breakTime * 10.0F / requiredTicks), 9);
        if (progress != this.lastBreakProgress) {
            for (BlockPos pos : blocks) {
                level.destroyBlockProgress(this.mob.getId(), pos, progress);
            }
            this.lastBreakProgress = progress;
        }

        if (this.breakTime >= requiredTicks) {
            for (BlockPos pos : blocks) {
                level.destroyBlock(pos, false, this.mob);
            }
            this.resetBreaking(level);
            // The wall is open: push a fresh path so the raider walks through
            // without waiting for the next recalculation
            this.moveMobToBlock();
        }
    }

    /**
     * Builds the scaffold moves the path evaluator priced in: a scaffolding
     * bridge laid under the feet of gap nodes, and a pillar built in the mob's
     * own column when the path climbs vertically.
     */
    private void tickScaffolding() {
        if (!(this.mob.level() instanceof ServerLevel level)) {
            return;
        }
        Path path = this.mob.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return;
        }

        int nextIndex = path.getNextNodeIndex();
        Node next = path.getNode(nextIndex);
        BlockPos mobFeet = this.mob.blockPosition();

        // Pillar ascent: the next node is directly overhead in the mob's column,
        // reachable only by climbing a scaffold stack built in place
        if (next.x == mobFeet.getX() && next.z == mobFeet.getZ() && next.y > mobFeet.getY()) {
            this.ensureScaffolding(level, mobFeet);
            this.ensureScaffolding(level, mobFeet.above());
            this.mob.getJumpControl().jump();
            if (level.getBlockState(mobFeet).getBlock() instanceof ScaffoldingBlock
                    && this.mob.getDeltaMovement().y < SCAFFOLD_CLIMB_SPEED) {
                this.mob.setDeltaMovement(
                        this.mob.getDeltaMovement().x, SCAFFOLD_CLIMB_SPEED, this.mob.getDeltaMovement().z);
            }
            return;
        }

        // Bridge: an upcoming node hovers over air -- lay scaffolding under it
        int end = Math.min(path.getNodeCount(), nextIndex + TUNNEL_LOOKAHEAD_NODES);
        for (int i = nextIndex; i < end; i++) {
            Node node = path.getNode(i);
            BlockPos pos = new BlockPos(node.x, node.y, node.z);
            if (pos.distManhattan(mobFeet) > TUNNEL_BREAK_REACH) {
                continue;
            }
            BlockPos below = pos.below();
            BlockState belowState = level.getBlockState(below);
            if (this.isTraversable(level, pos)
                    && !(belowState.getBlock() instanceof ScaffoldingBlock)
                    && (belowState.isAir() || belowState.canBeReplaced())
                    && belowState.getFluidState().isEmpty()) {
                this.ensureScaffolding(level, below);
            }
        }
    }

    /**
     * Whether the mob could occupy the column at pos with a floor under it:
     * no collision and no scaffolding in the cells of the mob's height.
     */
    private boolean isTraversable(ServerLevel level, BlockPos pos) {
        for (int dy = 0; dy < Mth.ceil(this.mob.getBbHeight()); dy++) {
            BlockPos check = pos.above(dy);
            BlockState state = level.getBlockState(check);
            if (state.getBlock() instanceof ScaffoldingBlock
                    || !state.getCollisionShape(level, check).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void ensureScaffolding(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ScaffoldingBlock) {
            return;
        }
        if ((!state.isAir() && !state.canBeReplaced()) || !state.getFluidState().isEmpty()) {
            return;
        }
        level.setBlock(pos, Blocks.SCAFFOLDING.defaultBlockState(), 3);
        level.playSound(null, pos,
                Blocks.SCAFFOLDING.asItem().getBreakingSound(),
                SoundSource.BLOCKS, 1.0F, 0.8F);
        if (this.mob.getRandom().nextInt(4) == 0) {
            this.mob.swing(InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Finds the next path node the mob cannot walk into because its mob-height
     * column is blocked by breakable blocks, or null if none is in reach.
     */
    @Nullable
    private BlockPos blockingNode(ServerLevel level) {
        Path path = this.mob.getNavigation().getPath();
        if (path == null) {
            return null;
        }

        int start;
        int end;
        if (path.isDone()) {
            // A partial path ends wherever the search budget ran out, which is
            // often the inside of a wall. Without digging out the terminal node,
            // every repath ends at the same spot and the raider stalls here forever.
            if (path.canReach()) {
                return null;
            }
            start = Math.max(0, path.getNodeCount() - TUNNEL_LOOKAHEAD_NODES);
            end = path.getNodeCount();
        } else {
            int nextIndex = path.getNextNodeIndex();
            // The last consumed node may still be inside the wall the mob is pressed against
            start = Math.max(0, nextIndex - 1);
            end = Math.min(path.getNodeCount(), nextIndex + TUNNEL_LOOKAHEAD_NODES);
        }
        for (int i = start; i < end; i++) {
            Node node = path.getNode(i);
            BlockPos pos = new BlockPos(node.x, node.y, node.z);
            if (pos.distManhattan(this.mob.blockPosition()) > TUNNEL_BREAK_REACH
                    || Math.abs(node.y - this.mob.getBlockY()) > 1) {
                continue;
            }
            if (!this.blocksToBreak(level, pos).isEmpty()) {
                return pos;
            }
        }
        return null;
    }

    /**
     * The solid, breakable blocks in the mob-height column at nodePos -- the cells
     * the mob must remove before it can occupy the node. Empty when nothing
     * breakable stands in the way (open space, or unbreakable obstructions).
     */
    private List<BlockPos> blocksToBreak(ServerLevel level, BlockPos nodePos) {
        List<BlockPos> blocks = new ArrayList<>(2);
        for (int dy = 0; dy < Mth.ceil(this.mob.getBbHeight()); dy++) {
            BlockPos pos = nodePos.above(dy);
            BlockState state = level.getBlockState(pos);
            if (state.getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            if (state.getDestroySpeed(level, pos) < 0.0F) {
                return List.of();
            }
            blocks.add(pos.immutable());
        }
        return blocks;
    }

    private int requiredBreakTicks(ServerLevel level, List<BlockPos> blocks) {
        float hardness = 0.0F;
        for (BlockPos pos : blocks) {
            hardness += level.getBlockState(pos).getDestroySpeed(level, pos);
        }
        return TUNNEL_BREAK_BASE_TICKS + (int)Math.ceil(hardness * TUNNEL_BREAK_TICKS_PER_HARDNESS);
    }

    private void resetBreaking(ServerLevel level) {
        if (this.breakingNodePos != null) {
            for (BlockPos pos : this.blocksToBreak(level, this.breakingNodePos)) {
                level.destroyBlockProgress(this.mob.getId(), pos, -1);
            }
        }
        this.breakingNodePos = null;
        this.breakTime = 0;
        this.lastBreakProgress = -1;
    }

    private void retargetNearestFlag() {
        BlockPos nearestFlag = this.nearestFlag();
        if (nearestFlag == null || nearestFlag.equals(this.blockPos)) {
            return;
        }

        this.blockPos = nearestFlag;
        this.moveMobToBlock();
    }

    @Override
    public boolean shouldRecalculatePath() {
        // While mid-dig the path is stale by definition; a repath here would be
        // discarded when the wall opens anyway, and every one of these calls is
        // a full synchronous A* on the server thread
        if (this.breakingNodePos != null) {
            return false;
        }
        if (super.shouldRecalculatePath()) {
            return true;
        }

        // Paths to faraway flags end short of the target (node cap per search), leaving
        // the mob idle until the next scheduled recalculation. Repath as soon as the
        // current path runs out instead, throttled a little so failed paths don't
        // trigger a full A* every tick.
        if (this.mob.getNavigation().isDone()
                && this.mob.tickCount - this.idleRepathTimestamp >= IDLE_REPATH_INTERVAL_TICKS) {
            this.idleRepathTimestamp = this.mob.tickCount;
            return true;
        }

        return false;
    }

    @Override
    protected boolean isValidTarget(LevelReader level, BlockPos pos) {
        // Flags are tracked in FlagsSavedData, so trust that while the chunk is
        // unloaded; verify the block is still there once its chunk is loaded
        return !level.hasChunkAt(pos) || level.getBlockState(pos).getBlock() instanceof BaseFlagBlock;
    }

    @Override
    protected BlockPos getMoveToTarget() {
        // Aim at the flag's own position, not the block above it
        return this.blockPos;
    }

    @Override
    protected void moveMobToBlock() {
        BlockPos target = this.getMoveToTarget();
        this.mob.getNavigation()
                .moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, this.speedModifier);
    }

    @Override
    public double acceptedDistance() {
        // Loose enough that standing next to the flag counts as reached
        return 2.0;
    }

    @Override
    public void stop() {
        if (this.mob.level() instanceof ServerLevel serverLevel) {
            this.resetBreaking(serverLevel);
        }
        super.stop();
        // If the goal ended and the flag's chunk is loaded but the flag is gone,
        // the saved data is stale: prune it so raiders stop chasing ghosts
        if (this.mob.level() instanceof ServerLevel serverLevel
                && serverLevel.hasChunkAt(this.blockPos)
                && !(serverLevel.getBlockState(this.blockPos).getBlock() instanceof BaseFlagBlock)) {
            FlagsSavedData.getFromLevel(serverLevel).removeFlagLocation(this.blockPos);
        }
    }
}
