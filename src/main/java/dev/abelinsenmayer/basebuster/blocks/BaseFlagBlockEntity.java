package dev.abelinsenmayer.basebuster.blocks;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

import static dev.abelinsenmayer.basebuster.BaseBuster.BASE_FLAG_BLOCK_ENTITY;

public class BaseFlagBlockEntity extends BlockEntity {
    private Player owner = null;
    private String ownerName = "NONE";
    private List<RepairTarget> repairTargets = new ArrayList<>();

    public BaseFlagBlockEntity(BlockPos pos, BlockState blockState) {
        super(BASE_FLAG_BLOCK_ENTITY.get(), pos, blockState);
    }

    public Component getInfoForDisplay() {
        return Component.literal("Owner: " + ownerName + "\nRepairs needed: " + repairTargets.size());
    }

    public void addRepairTarget(BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) {
        // Multiple events can fire for a single destruction (e.g. BreakEvent and BlockDropsEvent);
        // only record the first report for a given position.
        if (repairTargets.stream().anyMatch(target -> target.pos().equals(pos))) {
            return;
        }
        repairTargets.add(new RepairTarget(pos, state, blockEntity));
        setChanged();
    }

    public int getRepairTargetCount() {
        return repairTargets.size();
    }

    /**
     * Discards all stored repair targets, dropping each saved block's loot-table drops and
     * inventory contents just above the flag, as though {@code player} had broken the
     * original blocks with a diamond pickaxe.
     *
     * @param player the player who broke the flag
     */
    public void abandonRepairs(@Nullable Player player) {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }

        ItemStack breakingTool = new ItemStack(Items.DIAMOND_PICKAXE);
        BlockPos dropPos = getBlockPos().above();
        for (RepairTarget target : repairTargets) {
            Block.dropResources(target.state(), serverLevel, dropPos, target.blockEntity(), player, breakingTool);
            // Containers keep their contents in the block entity, not the loot table; drop them
            // like a real break would. Shulker boxes are skipped because their contents are
            // already copied into the dropped item by the loot table.
            if (target.blockEntity() instanceof Container container && !(target.state().getBlock() instanceof ShulkerBoxBlock)) {
                Containers.dropContents(serverLevel, dropPos, container);
            }
        }
        repairTargets.clear();
        setChanged();
    }

    /**
     * Repairs up to {@code maxRepairs} randomly chosen repair targets, restoring the saved block
     * state and block entity at each saved position. Any block occupying a repair position is
     * broken as though by {@code breakingTool} wielded by {@code player}, so its drops are left behind.
     *
     * @param maxRepairs   the maximum number of targets to attempt this call
     * @param breakingTool the tool used to break obstructing blocks
     * @param player       the player performing the repair
     * @return the number of repair targets still remaining
     */
    public int repairRandomTargets(int maxRepairs, ItemStack breakingTool, Player player) {
        if (!(this.level instanceof ServerLevel serverLevel) || repairTargets.isEmpty()) {
            return repairTargets.size();
        }

        List<RepairTarget> candidates = new ArrayList<>(repairTargets);
        int attempts = 0;
        while (attempts < maxRepairs && !candidates.isEmpty()) {
            RepairTarget target = candidates.remove(serverLevel.random.nextInt(candidates.size()));
            attempts++;
            if (tryRepairTarget(serverLevel, target, breakingTool, player)) {
                repairTargets.remove(target);
            }
        }
        setChanged();
        return repairTargets.size();
    }

    private boolean tryRepairTarget(ServerLevel serverLevel, RepairTarget target, ItemStack breakingTool, Player player) {
        BlockPos pos = target.pos();
        BlockState currentState = serverLevel.getBlockState(pos);

        if (currentState.equals(target.state())) {
            // Already restored by other means; nothing to do
            return true;
        }

        if (!currentState.isAir()) {
            // A block was placed here since the destruction; break it like a pickaxe would
            if (currentState.getDestroySpeed(serverLevel, pos) < 0) {
                // Unbreakable (e.g. bedrock) - leave the target for later
                return false;
            }
            BlockEntity obstructingEntity = serverLevel.getBlockEntity(pos);
            serverLevel.removeBlockEntity(pos);
            Block.dropResources(currentState, serverLevel, pos, obstructingEntity, player, breakingTool);
        }

        if (!serverLevel.setBlock(pos, target.state(), 3)) {
            return false;
        }

        BlockEntity savedEntity = target.blockEntity();
        if (savedEntity != null && target.state().hasBlockEntity()) {
            serverLevel.setBlockEntity(savedEntity);
        }
        return true;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("owner_name") && this.level != null) {
            this.ownerName = tag.getString("owner_name");
            getOwner();  // Populate owner from online players (if possible)
        }
        if (tag.contains("repair_targets")) {
            CompoundTag repairTargetsTag = tag.getCompound("repair_targets");
            int count = tag.getInt("repair_targets_count");
            this.repairTargets = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                repairTargets.add(RepairTarget.deserialize(repairTargetsTag.getCompound("target" + i), registries));
            }
        }
    }

    // Save values into the passed CompoundTag here.
    @Override
    public void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (owner != null) {
            tag.putString("owner_name", owner.getDisplayName().getString());
        }
        if (!repairTargets.isEmpty()) {
            CompoundTag repairTargetsTag = new CompoundTag();
            int i = 0;
            for (RepairTarget repairTarget : repairTargets) {
                CompoundTag targetTag = repairTarget.serialize(registries);
                repairTargetsTag.put("target" + i, targetTag);
                i += 1;
            }
            tag.putInt("repair_targets_count", i);
            tag.put("repair_targets", repairTargetsTag);
        }
    }

    public Player getOwner() {
        if (owner == null && this.level != null) {
            this.level.players().stream().filter(player -> player.getDisplayName().getString().equals(this.ownerName)).findFirst().ifPresent(player -> this.owner = player);
        }
        return owner;
    }

    public void setOwner(Player player) {
        this.owner = player;
        this.ownerName = player.getDisplayName().getString();
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    public record RepairTarget(BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) {
        public CompoundTag serialize(HolderLookup.Provider registries) {
            CompoundTag tag = new CompoundTag();
            // Rehydrate with BlockPos.of(tag.getLong("pos"))
            tag.putLong("pos", pos.asLong());
            // Rehydrate with NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("state"))
            tag.put("state", NbtUtils.writeBlockState(state));
            // Full metadata (id + x/y/z + additional data) so BlockEntity.loadStatic(pos, state, tag, registries) can rebuild it
            if (blockEntity != null) {
                tag.put("block_entity", blockEntity.saveWithFullMetadata(registries));
            }
            return tag;
        }

        public static RepairTarget deserialize(CompoundTag tag, HolderLookup.Provider registries) {
            BlockPos pos = BlockPos.of(tag.getLong("pos"));
            BlockState state = NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("state"));
            BlockEntity blockEntity = tag.contains("block_entity")
                    ? BlockEntity.loadStatic(pos, state, tag.getCompound("block_entity"), registries)
                    : null;
            return new RepairTarget(pos, state, blockEntity);
        }
    }
}
