package dev.abelinsenmayer.basebuster.blocks;

import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * A standing banner awarded for defeating a raid, in five tiers. Extends the
 * vanilla banner so placement, collision, and rendering all behave exactly
 * like a normal banner ({@code BannerRenderer} keys the standing model on
 * {@code instanceof BannerBlock}). Solid-colored for now; pattern layers can
 * be added later via {@code DataComponents.BANNER_PATTERNS} on the item.
 */
public class VictoryBannerBlock extends BannerBlock {
    public static final int MIN_TIER = 1;
    public static final int MAX_TIER = 5;
    // Base color per tier, escalating in vividness
    private static final DyeColor[] TIER_COLORS = {
            DyeColor.WHITE, DyeColor.YELLOW, DyeColor.ORANGE, DyeColor.RED, DyeColor.PURPLE
    };

    private final int tier;

    public VictoryBannerBlock(int tier, Properties properties) {
        super(colorForTier(tier), properties);
        this.tier = tier;
        // BannerBlock's constructor inserts this block into its private static
        // BY_COLOR map, which BannerBlockEntity#getItem consults. Left alone,
        // cloning a vanilla banner of this tier's color would yield a victory
        // banner, so restore the vanilla entry.
        restoreVanillaColorEntry(colorForTier(tier));
    }

    public static DyeColor colorForTier(int tier) {
        return TIER_COLORS[tier - MIN_TIER];
    }

    public int getTier() {
        return this.tier;
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        // AbstractBannerBlock resolves the cloned item through
        // BannerBlock.byColor, which now maps this color back to the vanilla
        // banner — hand back our own item instead
        return victoryBannerStack(this, level, pos);
    }

    /**
     * An item stack for the given banner block, copying pattern/name components
     * off its block entity like vanilla's clone does. Shared with
     * {@link VictoryWallBannerBlock}.
     */
    static ItemStack victoryBannerStack(Block bannerBlock, LevelReader level, BlockPos pos) {
        ItemStack stack = new ItemStack(bannerBlock.asItem());
        if (level.getBlockEntity(pos) instanceof BannerBlockEntity banner) {
            stack.applyComponents(banner.collectComponents());
        }
        return stack;
    }

    @SuppressWarnings("unchecked")
    private static void restoreVanillaColorEntry(DyeColor color) {
        try {
            Field byColorField = BannerBlock.class.getDeclaredField("BY_COLOR");
            byColorField.setAccessible(true);
            Map<DyeColor, Block> byColor = (Map<DyeColor, Block>) byColorField.get(null);
            // Vanilla standing banners register as <color>_banner
            Block vanilla = BuiltInRegistries.BLOCK.get(
                    ResourceLocation.withDefaultNamespace(color.getName() + "_banner"));
            if (vanilla instanceof BannerBlock) {
                byColor.put(color, vanilla);
            }
        } catch (Exception e) {
            BaseBuster.LOGGER.warn("Could not restore BannerBlock.BY_COLOR for {}", color, e);
        }
    }
}
