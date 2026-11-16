package dev.abelinsenmayer.basebuster.blocks;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.items.RepairHammerItem;
import dev.abelinsenmayer.basebuster.raids.RaidSpawner;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

public class BaseFlagBlock extends BannerBlock implements EntityBlock {
    // Protection radius per base level (see BaseFlagBlockEntity#LEVEL_REQUIREMENTS);
    // levels beyond the table clamp to the last entry
    private static final int[] PROTECTION_RADII = {48, 64, 64, 96, 96};
    // Largest radius a flag can reach; used as the scan bound wherever any flag's
    // sphere could apply (banner level scans, candidate prefilters)
    public static final int MAX_PROTECTION_RADIUS = PROTECTION_RADII[PROTECTION_RADII.length - 1];
    // True once raiders capture the flag; renders as a red banner instead of black
    public static final BooleanProperty CAPTURED = BooleanProperty.create("captured");

    /** The protection radius for a base level (1-5, clamped). */
    public static int protectionRadius(int baseLevel) {
        return PROTECTION_RADII[Math.clamp(baseLevel, 1, PROTECTION_RADII.length) - 1];
    }

    /**
     * The protection radius of the flag at {@code flagPos}, or the level-1 radius
     * when no flag block entity is present there. May load the flag's chunk.
     */
    public static int protectionRadius(BlockGetter level, BlockPos flagPos) {
        if (level.getBlockEntity(flagPos) instanceof BaseFlagBlockEntity flagEntity) {
            return flagEntity.getProtectionRadius();
        }
        return protectionRadius(1);
    }

    /**
     * {@link #protectionRadius(BlockGetter, BlockPos)} variant that never loads
     * the flag's chunk; flags in unloaded chunks count as level 1. For callers
     * (e.g. entity goals) that check flags potentially far from themselves.
     */
    public static int protectionRadiusIfLoaded(Level level, BlockPos flagPos) {
        return level.hasChunkAt(flagPos) ? protectionRadius(level, flagPos) : protectionRadius(1);
    }

    public BaseFlagBlock(Properties properties) {
        super(
                DyeColor.BLACK,
                Properties.of()
                        .mapColor(MapColor.WOOL)
                        .noCollission()
                        // Player-breakable hardness, bedrock-level blast resistance
                        .strength(1.0F, 3600000.0F)
                        // Pistons can't push or break it, and flowing liquids can't wash it away
                        .pushReaction(PushReaction.BLOCK)
        );
        this.registerDefaultState(this.stateDefinition.any().setValue(CAPTURED, false));
        // BannerBlock's constructor inserts this block into its private static
        // BY_COLOR map, which BannerBlockEntity#getItem consults. Restore the
        // vanilla black-banner entry, same as VictoryBannerBlock does per tier.
        VictoryBannerBlock.restoreVanillaColorEntry(DyeColor.BLACK);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(CAPTURED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // Face the player on placement, same as BannerItem's standing placement
        return this.defaultBlockState()
                .setValue(ROTATION, Mth.floor((180.0F + context.getRotation()) * 16.0F / 360.0F) % 16);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        // AbstractBannerBlock applies the item's custom name to the block entity
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player) {
            BaseFlagBlockEntity blockEntity = getBlockEntity(level, pos);
            if (blockEntity != null) {
                blockEntity.setOwner(player);
            }
        }
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        // AbstractBannerBlock resolves clones through BannerBlock.byColor, which
        // maps BLACK back to the vanilla banner; hand back our item instead
        return VictoryBannerBlock.victoryBannerStack(this, level, pos);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BaseFlagBlockEntity(pos, state);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != BaseBuster.BASE_FLAG_BLOCK_ENTITY.get()) {
            return null;
        }
        return (BlockEntityTicker<T>) (BlockEntityTicker<BaseFlagBlockEntity>) BaseFlagBlockEntity::tick;
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
        // Debug toggle: right-clicking with a stick flips the protection-radius
        // overlay. Checked first so shift-clicking a flag with a stick can't
        // accidentally advance a capture.
        if (stack.is(Items.STICK)) {
            if (!level.isClientSide) {
                BaseFlagBlockEntity blockEntity = getBlockEntity(level, pos);
                if (blockEntity != null) {
                    boolean visible = blockEntity.toggleDebugBoundary();
                    level.sendBlockUpdated(pos, state, state, 3);
                    player.displayClientMessage(
                            Component.literal("Flag boundary " + (visible ? "shown" : "hidden")), true);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Renaming: anvil-named flag items carry their name into the block on
        // placement, and a named name tag renames a flag that's already down
        if (stack.is(Items.NAME_TAG)) {
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            // An unused (unnamed) tag falls through to the normal flag interaction
            if (name == null) {
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (!level.isClientSide) {
                BaseFlagBlockEntity blockEntity = getBlockEntity(level, pos);
                Player owner = blockEntity != null ? blockEntity.getOwner() : null;
                if (owner == null || !owner.getUUID().equals(player.getUUID())) {
                    player.displayClientMessage(
                            Component.literal("Only the flag's owner can rename it")
                                    .withStyle(ChatFormatting.RED),
                            true);
                    return ItemInteractionResult.sidedSuccess(level.isClientSide);
                }
                blockEntity.setFlagName(name);
                blockEntity.setChanged();
                level.sendBlockUpdated(pos, state, state, 3);
                if (!player.hasInfiniteMaterials()) {
                    stack.shrink(1);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Banner design: the owner can stamp a banner's base color and
        // patterns onto the flag; the banner is consumed like a name tag
        if (stack.getItem() instanceof BannerItem) {
            if (!level.isClientSide) {
                BaseFlagBlockEntity blockEntity = getBlockEntity(level, pos);
                Player owner = blockEntity != null ? blockEntity.getOwner() : null;
                if (owner == null || !owner.getUUID().equals(player.getUUID())) {
                    player.displayClientMessage(
                            Component.literal("Only the flag's owner can change its design")
                                    .withStyle(ChatFormatting.RED),
                            true);
                    return ItemInteractionResult.sidedSuccess(level.isClientSide);
                }
                FlagsSavedData.FlagDesign design = blockEntity.applyBannerDesign(stack);
                level.sendBlockUpdated(pos, state, state, 3);

                // Remember the design on the overworld's data so it follows the
                // player across dimensions: every flag they own takes it on,
                // including flags they place or capture later (see setOwner)
                FlagsSavedData.getOverworld((ServerLevel) level)
                        .setPlayerDesign(player.getUUID(), design);
                for (ServerLevel flagLevel : level.getServer().getAllLevels()) {
                    for (BlockPos flagPos : FlagsSavedData.getFromLevel(flagLevel).getFlagLocations()) {
                        // Flags in unloaded chunks aren't touched here (same
                        // no-chunk-loading stance as protectionRadiusIfLoaded);
                        // they pick up the design when they load -- see
                        // BaseFlagBlockEntity#syncOwnerDesign
                        if ((flagLevel == level && flagPos.equals(pos))
                                || !flagLevel.hasChunkAt(flagPos)) {
                            continue;
                        }
                        if (flagLevel.getBlockEntity(flagPos) instanceof BaseFlagBlockEntity other
                                && other.getOwner() != null
                                && other.getOwner().getUUID().equals(player.getUUID())) {
                            other.applyDesign(design.color(), design.patterns());
                            flagLevel.sendBlockUpdated(flagPos, other.getBlockState(), other.getBlockState(), 3);
                        }
                    }
                }
                if (!player.hasInfiniteMaterials()) {
                    stack.shrink(1);
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

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
            int raidTier = RaidSpawner.raidTier((ServerLevel) level, pos);
            // The party materializes once its path searches finish, some
            // ticks later; announce the raid then, not at trigger time
            if (RaidSpawner.startRaid((ServerLevel) level, pos, () -> player.displayClientMessage(
                    Component.literal("A level " + raidTier + " raid has begun!"), false))) {
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
