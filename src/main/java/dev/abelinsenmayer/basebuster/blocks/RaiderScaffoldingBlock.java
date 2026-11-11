package dev.abelinsenmayer.basebuster.blocks;

import com.mojang.serialization.MapCodec;
import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.FallingBlockEntity;
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
 * like vanilla scaffolding (same shapes, stability, falling) but with three
 * raider-specific rules:
 *
 * <ul>
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
 * vanilla stability checks.
 *
 * <p>Stability ({@link #tick} / {@link #canSurvive}) is reimplemented here
 * rather than inherited: vanilla {@link ScaffoldingBlock#getDistance} only
 * counts {@code Blocks.SCAFFOLDING} as support, so a subclass in a column
 * would never recognize its own kind and every block above the base would
 * fall. This class's {@link #getDistance} accepts any {@link ScaffoldingBlock}
 * as support, so raider scaffolding chains with itself and with vanilla
 * scaffolding.
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
     * {@link #STABILITY_MAX_DISTANCE}. Mirrors vanilla's algorithm except any
     * {@link ScaffoldingBlock} counts as support -- vanilla's version matches
     * only {@code Blocks.SCAFFOLDING} by registry identity.
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

    /** Vanilla's stability tick, computed with {@link #getDistance}. */
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        int distance = getDistance(level, pos);
        BlockState newState = state.setValue(DISTANCE, distance).setValue(BOTTOM, isBottom(level, pos, distance));
        if (newState.getValue(DISTANCE) == STABILITY_MAX_DISTANCE) {
            if (state.getValue(DISTANCE) == STABILITY_MAX_DISTANCE) {
                FallingBlockEntity.fall(level, pos, newState);
            } else {
                level.destroyBlock(pos, true);
            }
        } else if (state != newState) {
            level.setBlock(pos, newState, 3);
        }
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return getDistance(level, pos) < STABILITY_MAX_DISTANCE;
    }
}
