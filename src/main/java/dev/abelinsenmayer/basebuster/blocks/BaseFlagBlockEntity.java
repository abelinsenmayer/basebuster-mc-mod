package dev.abelinsenmayer.basebuster.blocks;

import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.ChatFormatting;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.abelinsenmayer.basebuster.BaseBuster.BASE_FLAG_BLOCK_ENTITY;

public class BaseFlagBlockEntity extends BlockEntity {
    // Faction name assigned to flags captured by raiders
    public static final String RAIDER_FACTION = "RAIDERS";
    // Segments rendered as '=' in the owner's capture progress bar
    private static final int PROGRESS_BAR_SEGMENTS = 10;
    // Ticks between capture progress notifications to the owner
    private static final int NOTIFY_INTERVAL_TICKS = 10;

    private Player owner = null;
    private String ownerName = "NONE";
    // Faction ownership replaces player ownership (e.g. RAIDER_FACTION).
    // While set, getOwner() returns null so grief protection no longer applies.
    @Nullable
    private String ownerFaction = null;
    private List<RepairTarget> repairTargets = new ArrayList<>();

    // Progress toward a raider capture, in [0, 1)
    private float captureProgress = 0.0F;
    // Game time of the last capture tick; progress resets after a one-tick gap
    private long lastCaptureTick = -1L;
    // Capturer id -> game time of its most recent capture tick
    private final Map<UUID, Long> capturerTicks = new HashMap<>();

    // Progress toward a player's right-click capture, in [0, 1)
    private float playerCaptureProgress = 0.0F;
    // The player currently capturing; a different player's click resets progress
    @Nullable
    private UUID playerCapturerId = null;

    public BaseFlagBlockEntity(BlockPos pos, BlockState blockState) {
        super(BASE_FLAG_BLOCK_ENTITY.get(), pos, blockState);
    }

    public Component getInfoForDisplay() {
        String ownerDisplay = ownerFaction != null ? ownerFaction + " (faction)" : ownerName;
        return Component.literal("Owner: " + ownerDisplay + "\nRepairs needed: " + repairTargets.size());
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
        if (tag.contains("owner_faction")) {
            this.ownerFaction = tag.getString("owner_faction");
            this.owner = null;
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
        if (ownerFaction != null) {
            tag.putString("owner_name", this.ownerName);
            tag.putString("owner_faction", this.ownerFaction);
        } else if (owner != null) {
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
        // Faction-owned flags have no player owner
        if (ownerFaction != null) {
            return null;
        }
        if (owner == null && this.level != null) {
            this.level.players().stream().filter(player -> player.getDisplayName().getString().equals(this.ownerName)).findFirst().ifPresent(player -> this.owner = player);
        }
        return owner;
    }

    public void setOwner(Player player) {
        this.owner = player;
        this.ownerName = player.getDisplayName().getString();
        this.ownerFaction = null;
        this.captureProgress = 0.0F;
        this.capturerTicks.clear();
        this.playerCaptureProgress = 0.0F;
        this.playerCapturerId = null;
        // Reclaiming a raider-captured flag restores its uncaptured appearance
        if (this.level instanceof ServerLevel serverLevel
                && getBlockState().hasProperty(BaseFlagBlock.CAPTURED)
                && getBlockState().getValue(BaseFlagBlock.CAPTURED)) {
            BlockPos pos = getBlockPos();
            serverLevel.setBlock(pos, getBlockState().setValue(BaseFlagBlock.CAPTURED, false), 3);
            FlagsSavedData.getFromLevel(serverLevel).removeCapturedFlagLocation(pos);
        }
        setChanged();
    }

    public boolean isRaiderOwned() {
        return RAIDER_FACTION.equals(this.ownerFaction);
    }

    public boolean isUnowned() {
        return this.ownerName == null && this.ownerFaction == null;
    }

    /**
     * Records one tick of capture activity by a raider standing next to this flag.
     * Progress resets when a full tick passes with no capturer. The capture rate
     * gains {@link Config#FLAG_CAPTURE_SPEED_BONUS} per participating raider, capped
     * at {@link Config#FLAG_CAPTURE_MAX_SPEED}, so a lone raider needs
     * {@link Config#FLAG_CAPTURE_SECONDS} seconds to finish.
     */
    public void tickCapture(UUID capturerId) {
        if (!(this.level instanceof ServerLevel serverLevel) || isRaiderOwned()) {
            return;
        }

        long now = serverLevel.getGameTime();
        if (this.lastCaptureTick >= 0 && now - this.lastCaptureTick > 1) {
            this.captureProgress = 0.0F;
            this.capturerTicks.clear();
        }
        this.lastCaptureTick = now;
        this.capturerTicks.put(capturerId, now);
        this.capturerTicks.values().removeIf(tick -> tick < now - 1);

        int participants = Math.max(1, this.capturerTicks.size());
        double speed = Math.min(
                1.0 + participants * Config.FLAG_CAPTURE_SPEED_BONUS.get(),
                Config.FLAG_CAPTURE_MAX_SPEED.get());
        this.captureProgress += (float) (speed / (participants * this.requiredCaptureTicks()));

        if (this.captureProgress >= 1.0F) {
            this.captureByRaiders(serverLevel);
            return;
        }

        if (now % NOTIFY_INTERVAL_TICKS == 0) {
            Player ownerPlayer = this.getOwner();
            if (ownerPlayer != null) {
                ownerPlayer.displayClientMessage(this.captureProgressMessage(), true);
            }
        }
        setChanged();
    }

    private double requiredCaptureTicks() {
        return Config.FLAG_CAPTURE_SECONDS.get() * 20.0;
    }

    private Component captureProgressMessage() {
        return Component.literal("Your flag is being captured! " + progressBar(this.captureProgress))
                .withStyle(ChatFormatting.RED);
    }

    private static String progressBar(float progress) {
        int filled = Math.min(PROGRESS_BAR_SEGMENTS, (int) (progress * PROGRESS_BAR_SEGMENTS));
        String bar = "=".repeat(filled) + " ".repeat(PROGRESS_BAR_SEGMENTS - filled);
        return "[" + bar + "] " + (int) (progress * 100) + "%";
    }

    /**
     * Advances a player's capture of this flag by one right-click.
     *
     * <p>Only one player can hold capture progress at a time: a click from a
     * different player discards the previous capturer's progress and starts the
     * new player's count. At 100% the flag transfers to the clicking player,
     * clearing any raider faction ownership and captured appearance.
     *
     * @return whether the click was handled as a capture attempt (false when the
     *         clicker already owns the flag)
     */
    public boolean advancePlayerCapture(Player player) {
        if (!(this.level instanceof ServerLevel)) {
            return false;
        }

        Player currentOwner = this.getOwner();
        if (currentOwner != null && currentOwner.getUUID().equals(player.getUUID())) {
            return false;
        }

        // A flag nobody owns is claimed instantly, no clicks needed
        if (this.isUnowned()) {
            this.completePlayerCapture(player);
            return true;
        }

        if (this.playerCapturerId != null && !this.playerCapturerId.equals(player.getUUID())) {
            // Contested: someone else was mid-capture, so their progress resets
            this.playerCaptureProgress = 0.0F;
        }
        this.playerCapturerId = player.getUUID();
        this.playerCaptureProgress += Config.FLAG_CAPTURE_PLAYER_PROGRESS_PER_CLICK.get().floatValue();

        if (this.playerCaptureProgress >= 1.0F) {
            this.completePlayerCapture(player);
            return true;
        }

        player.displayClientMessage(
                Component.literal("Capturing flag... " + progressBar(this.playerCaptureProgress))
                        .withStyle(ChatFormatting.RED),
                true);
        setChanged();
        return true;
    }

    private void completePlayerCapture(Player player) {
        this.playerCaptureProgress = 0.0F;
        this.playerCapturerId = null;
        this.setOwner(player);
        if (this.level instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
        player.displayClientMessage(
                Component.literal("Flag captured! You are now the owner.")
                        .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                true);
    }

    private void captureByRaiders(ServerLevel serverLevel) {
        Player formerOwner = this.getOwner();
        // A captured flag can no longer repair its surroundings; drop them like
        // a flag broken by a player, but with no player to credit
        this.abandonRepairs(null);

        this.owner = null;
        this.ownerName = RAIDER_FACTION;
        this.ownerFaction = RAIDER_FACTION;
        this.captureProgress = 0.0F;
        this.capturerTicks.clear();
        this.playerCaptureProgress = 0.0F;
        this.playerCapturerId = null;

        BlockPos pos = getBlockPos();
        serverLevel.setBlock(pos, getBlockState().setValue(BaseFlagBlock.CAPTURED, true), 3);
        FlagsSavedData.getFromLevel(serverLevel).addCapturedFlagLocation(pos);

        if (formerOwner != null) {
            formerOwner.displayClientMessage(
                    Component.literal("Your base flag has been captured!")
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                    true);
        }
        setChanged();
        serverLevel.sendBlockUpdated(pos, getBlockState(), getBlockState(), 3);
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
