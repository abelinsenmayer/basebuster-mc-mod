package dev.abelinsenmayer.basebuster.blocks;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.items.RepairHammerItem;
import dev.abelinsenmayer.basebuster.raids.RaidSpawner;
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
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

public class BaseFlagBlock extends Block implements EntityBlock {
    public static final int FLAG_PROTECTION_RADIUS = 120;
    // True once raiders capture the flag; renders as red wool instead of white
    public static final BooleanProperty CAPTURED = BooleanProperty.create("captured");

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
        this.registerDefaultState(this.stateDefinition.any().setValue(CAPTURED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CAPTURED);
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

        BaseFlagBlockEntity blockEntity = getBlockEntity(level, pos);
        if (blockEntity == null) {
            return InteractionResult.CONSUME;
        }

        if (player.isShiftKeyDown()) {
            // Shift-click claims unowned flags instantly; owned flags need a full capture
            blockEntity.advancePlayerCapture(player);
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.SUCCESS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        // Raid triggers: a raider spawn egg or any ominous bottle (all five
        // types share one item; the amplifier lives in a data component)
        if (stack.is(BaseBuster.RAIDER_SPAWN_EGG.get()) || stack.is(Items.OMINOUS_BOTTLE)) {
            return this.useRaidTrigger(stack, level, pos, player);
        }

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

    /**
     * Starts a raid targeting this flag. Only player-owned flags can be raided:
     * an unowned flag is just a marker, and a raider-owned one is already captured.
     */
    private ItemInteractionResult useRaidTrigger(ItemStack stack, Level level, BlockPos pos, Player player) {
        BaseFlagBlockEntity blockEntity = getBlockEntity(level, pos);
        if (blockEntity == null) {
            return ItemInteractionResult.CONSUME;
        }

        if (!level.isClientSide) {
            if (blockEntity.getOwner() == null) {
                player.displayClientMessage(
                        Component.literal("Only a player-owned flag can be raided"), true);
                return ItemInteractionResult.CONSUME;
            }
            if (RaidSpawner.startRaid((ServerLevel) level, pos)) {
                player.displayClientMessage(
                        Component.literal("A level " + RaidSpawner.raidTier((ServerLevel) level, pos) + " raid has begun!"),
                        false);
                if (!player.hasInfiniteMaterials()) {
                    stack.shrink(1);
                }
            } else {
                player.displayClientMessage(
                        Component.literal("Could not find a safe raid spawn point"), true);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
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

        // Count victory banners already standing so a flag placed into a
        // decorated base starts at the right level. Same-block state changes
        // (e.g. the CAPTURED toggle) don't re-place the flag.
        if (!oldState.is(state.getBlock())) {
            BaseFlagBlockEntity flagEntity = getBlockEntity(level, pos);
            if (flagEntity != null) {
                flagEntity.recalculateLevel();
            }
        }
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
