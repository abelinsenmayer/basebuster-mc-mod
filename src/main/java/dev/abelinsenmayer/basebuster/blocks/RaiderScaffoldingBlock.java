package dev.abelinsenmayer.basebuster.blocks;

import com.mojang.serialization.MapCodec;
import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Scaffolding variant placed by raiders while bridging and pillaring. Behaves
 * like vanilla scaffolding (same shapes and climbability) but with these
 * raider-specific rules:
 *
 * <ul>
 *   <li><b>No stability limit</b>: never collapses or falls no matter how far
 *   it extends horizontally from the nearest supported block, so raider
 *   bridges of any length stay up.
 *   <li><b>No drops and no repairs</b>: registered with {@code noLootTable()},
 *   and {@code BaseBusterEventBus.handleBlockGriefing} skips it entirely so
 *   nearby base flags never log it for repair.
 *   <li><b>Self-despawn</b>: a {@link RaiderScaffoldingBlockEntity} counts down
 *   {@link RaiderScaffoldingBlockEntity#LIFETIME_TICKS} and silently removes
 *   the block, so raider-built access routes clean themselves up.
 *   <li><b>Ignores entity obstruction</b>: placed via {@code Level.setBlock},
 *   which performs no entity-free-space check the way item placement does.
 * </ul>
 *
 * <p>The despawn countdown lives on a block entity rather than a scheduled
 * block tick because {@code LevelTicks} permits only one pending tick per
 * position per block -- the scaffolding block already needs that slot for its
 * {@code DISTANCE}/{@code BOTTOM} updates.
 *
 * <p>{@link #getDistance} is kept (rather than inherited) because vanilla
 * {@link ScaffoldingBlock#getDistance} only counts {@code Blocks.SCAFFOLDING}
 * as support, so this subclass would never recognize its own kind in a column.
 * Its result still drives the {@code DISTANCE}/{@code BOTTOM} render
 * properties, but no longer gates survival.
 */
public class RaiderScaffoldingBlock extends ScaffoldingBlock implements EntityBlock {
    public static final MapCodec<ScaffoldingBlock> CODEC =
            simpleCodec(properties -> new RaiderScaffoldingBlock(properties));

    public RaiderScaffoldingBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<ScaffoldingBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RaiderScaffoldingBlockEntity(pos, state);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != BaseBuster.RAIDER_SCAFFOLDING_BLOCK_ENTITY.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<RaiderScaffoldingBlockEntity>) RaiderScaffoldingBlockEntity::tick;
    }

    /**
     * Distance to the nearest supported scaffold or solid top face, capped at
     * {@link #STABILITY_MAX_DISTANCE} (the {@code DISTANCE} property's max
     * value). Mirrors vanilla's algorithm except any {@link ScaffoldingBlock}
     * counts as support -- vanilla's version matches only
     * {@code Blocks.SCAFFOLDING} by registry identity. Used only to maintain
     * the {@code DISTANCE}/{@code BOTTOM} render properties; it no longer
     * decides whether the block can stand.
     */
    public static int getDistance(BlockGetter level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = pos.mutable().move(Direction.DOWN);
        BlockState belowState = level.getBlockState(cursor);
        int distance = STABILITY_MAX_DISTANCE;
        if (belowState.getBlock() instanceof ScaffoldingBlock) {
            distance = belowState.getValue(DISTANCE);
        } else if (belowState.isFaceSturdy(level, cursor, Direction.UP)
                || Block.isFaceFull(belowState.getCollisionShape(level, cursor), Direction.UP)) {
            // Vanilla anchors only on isFaceSturdy, which partial-height blocks
            // (bottom slabs, dirt paths, farmland, soul sand) fail despite
            // mobs standing on them fine. A full top face in the collision
            // shape is what "a mob can stand here" actually means -- anything
            // less (fence posts, walls) genuinely can't hold a pillar base
            return 0;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockState sideState = level.getBlockState(cursor.setWithOffset(pos, direction));
            if (sideState.getBlock() instanceof ScaffoldingBlock) {
                distance = Math.min(distance, sideState.getValue(DISTANCE) + 1);
                if (distance == 1) {
                    break;
                }
            }
        }
        return distance;
    }

    private static boolean isBottom(BlockGetter level, BlockPos pos, int distance) {
        return distance > 0 && !(level.getBlockState(pos.below()).getBlock() instanceof ScaffoldingBlock);
    }

    /**
     * Vanilla's tick minus the collapse: {@code DISTANCE}/{@code BOTTOM} still
     * update for rendering, but the block never falls or breaks at
     * {@link #STABILITY_MAX_DISTANCE}.
     */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        int distance = getDistance(level, pos);
        BlockState newState = state.setValue(DISTANCE, distance).setValue(BOTTOM, isBottom(level, pos, distance));
        if (state != newState) {
            level.setBlock(pos, newState, 3);
        }
    }

    /** No stability constraint: raider scaffolding survives anywhere. */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return true;
    }
}
