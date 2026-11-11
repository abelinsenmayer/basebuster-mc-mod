package dev.abelinsenmayer.basebuster.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import static dev.abelinsenmayer.basebuster.BaseBuster.RAIDER_SCAFFOLDING_BLOCK_ENTITY;

/**
 * Despawn timer for {@link RaiderScaffoldingBlock}. Counts down once per game
 * tick and silently removes the block when it reaches zero.
 *
 * <p>The countdown is tick-based (not wall-clock based on game time) so a
 * scaffold in an unloaded chunk keeps its remaining life instead of vanishing
 * while nobody is watching.
 *
 * <p>Removal uses {@code Level.removeBlock}, which swaps the state without
 * drops and without firing any of the break events base flags listen to, so a
 * despawning scaffold is never logged as a repair target.
 */
public class RaiderScaffoldingBlockEntity extends BlockEntity {
    // One minute at 20 ticks per second
    public static final int LIFETIME_TICKS = 20 * 60;
    // Mark the entity dirty at this interval so the countdown survives saves
    // without paying a chunk-dirty write on every tick
    private static final int SAVE_INTERVAL_TICKS = 20;

    private int remainingTicks = LIFETIME_TICKS;

    public RaiderScaffoldingBlockEntity(BlockPos pos, BlockState state) {
        super(RAIDER_SCAFFOLDING_BLOCK_ENTITY.get(), pos, state);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, RaiderScaffoldingBlockEntity blockEntity) {
        if (--blockEntity.remainingTicks <= 0) {
            level.removeBlock(pos, false);
            // Break particles/sound so the vanish reads as a crumble, not a pop
            level.levelEvent(2001, pos, Block.getId(state));
            return;
        }
        if (blockEntity.remainingTicks % SAVE_INTERVAL_TICKS == 0) {
            blockEntity.setChanged();
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("remaining_ticks")) {
            this.remainingTicks = tag.getInt("remaining_ticks");
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("remaining_ticks", this.remainingTicks);
    }
}
