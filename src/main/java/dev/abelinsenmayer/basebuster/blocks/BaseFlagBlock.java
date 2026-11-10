package dev.abelinsenmayer.basebuster.blocks;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.items.RepairHammerItem;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

public class BaseFlagBlock extends Block implements EntityBlock {
    public static final int FLAG_PROTECTION_RADIUS = 32;

    public BaseFlagBlock(Properties properties) {
        super(
                Properties.of()
                        .mapColor(MapColor.WOOL)
                        .noCollission()
                        // Player-breakable hardness, bedrock-level blast resistance
                        .strength(1.0F, 3600000.0F)
                        // Pistons can't push or break it, and flowing liquids can't wash it away
                        .pushReaction(PushReaction.BLOCK)
        );
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BaseFlagBlockEntity(pos, state);
    }

    private BaseFlagBlockEntity getBlockEntity(Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof BaseFlagBlockEntity) {
            return (BaseFlagBlockEntity) blockEntity;
        }
        return null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (level.isClientSide) {
            if (player.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }
            // Show flag info to player via chat
            BaseFlagBlockEntity blockEntity = getBlockEntity(level, pos);
            if (blockEntity == null) {
                return InteractionResult.CONSUME;
            }
            Component infoMessage = blockEntity.getInfoForDisplay();
            player.displayClientMessage(infoMessage, false);
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            BaseBuster.LOGGER.info("Setting owner of flag at {} to {}", pos, player.getDisplayName().getString());
            BaseFlagBlockEntity blockEntity = getBlockEntity(level, pos);
            if (blockEntity == null) {
                return InteractionResult.CONSUME;
            }
            blockEntity.setOwner(player);
            level.sendBlockUpdated(pos, state, state, 3);
            player.displayClientMessage(Component.literal("Flag owner set to " + player.getDisplayName().getString()), true);
        }

        return InteractionResult.SUCCESS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!(stack.getItem() instanceof RepairHammerItem hammer)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        BaseFlagBlockEntity blockEntity = getBlockEntity(level, pos);
        if (blockEntity == null) {
            return ItemInteractionResult.CONSUME;
        }

        if (!level.isClientSide) {
            if (blockEntity.getRepairTargetCount() == 0) {
                player.displayClientMessage(Component.literal("No blocks left to repair"), true);
                return ItemInteractionResult.SUCCESS;
            }
            Tier tier = hammer.getTier();
            int remaining = blockEntity.repairRandomTargets(repairsPerClick(tier), pickaxeForTier(tier), player);
            level.sendBlockUpdated(pos, state, state, 3);
            player.displayClientMessage(
                    Component.literal(remaining == 0 ? "All repairs complete!" : remaining + " blocks left to repair"),
                    true
            );
        }
        return ItemInteractionResult.SUCCESS;
    }

    // Blocks repaired per click: stone -> 1, iron -> 3, diamond -> 6
    private static int repairsPerClick(Tier tier) {
        if (tier == Tiers.IRON) {
            return 3;
        }
        if (tier == Tiers.DIAMOND || tier == Tiers.NETHERITE) {
            return 6;
        }
        return 1;
    }

    // Pickaxe matching the hammer's tier, used to break blocks obstructing repair locations
    private static ItemStack pickaxeForTier(Tier tier) {
        Item item;
        if (tier == Tiers.NETHERITE) {
            item = Items.NETHERITE_PICKAXE;
        } else if (tier == Tiers.DIAMOND) {
            item = Items.DIAMOND_PICKAXE;
        } else if (tier == Tiers.IRON) {
            item = Items.IRON_PICKAXE;
        } else if (tier == Tiers.STONE) {
            item = Items.STONE_PICKAXE;
        } else if (tier == Tiers.GOLD) {
            item = Items.GOLDEN_PICKAXE;
        } else {
            item = Items.WOODEN_PICKAXE;
        }
        return new ItemStack(item);
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity, ItemStack tool) {
        super.playerDestroy(level, player, pos, state, blockEntity, tool);

        // The flag was broken by a player: abandon all stored repairs so their items
        // and inventory contents drop just above the flag, as if broken by a diamond pickaxe
        if (blockEntity instanceof BaseFlagBlockEntity flagEntity) {
            flagEntity.abandonRepairs(player);
        }
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean isMoving) {
        super.onPlace(state, level, pos, oldState, isMoving);

        if (level.isClientSide) {
            return;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        FlagsSavedData flagsSavedData = FlagsSavedData.getFromLevel(serverLevel);

        flagsSavedData.addFlagLocation(pos);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);

        if (level.isClientSide) {
            return;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        FlagsSavedData flagsSavedData = FlagsSavedData.getFromLevel(serverLevel);
        flagsSavedData.removeFlagLocation(pos);
    }
}
