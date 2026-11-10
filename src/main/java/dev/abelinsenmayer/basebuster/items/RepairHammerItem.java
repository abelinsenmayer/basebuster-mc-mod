package dev.abelinsenmayer.basebuster.items;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.level.LevelReader;

public class RepairHammerItem extends TieredItem {
    public RepairHammerItem(Tier tier, Properties properties) {
        super(tier, properties);
    }

    // Allow blocks to receive use interactions while the player sneaks with this hammer
    // (e.g. shift-right-clicking a flag to toggle repair mode).
    @Override
    public boolean doesSneakBypassUse(ItemStack stack, LevelReader level, BlockPos pos, Player player) {
        return true;
    }
}
