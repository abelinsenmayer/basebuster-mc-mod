package dev.abelinsenmayer.basebuster.blocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The wall-mounted counterpart to {@link VictoryBannerBlock}. A single banner
 * item places this when used against a wall, exactly like vanilla banners;
 * {@code BannerRenderer} draws the wall-mounted model based on the FACING
 * property inherited from {@link WallBannerBlock}.
 */
public class VictoryWallBannerBlock extends WallBannerBlock {
    private final int tier;

    public VictoryWallBannerBlock(int tier, Properties properties) {
        super(VictoryBannerBlock.colorForTier(tier), properties);
        this.tier = tier;
    }

    public int getTier() {
        return this.tier;
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return VictoryBannerBlock.victoryBannerStack(this, level, pos);
    }
}
