package dev.abelinsenmayer.basebuster.blocks;

import com.mojang.authlib.GameProfile;
import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.client.FlagBoundaryDebugState;
import dev.abelinsenmayer.basebuster.raids.Faction;
import dev.abelinsenmayer.basebuster.raids.RaidSpawner;
import dev.abelinsenmayer.basebuster.world.FlagSpawnProtection;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static dev.abelinsenmayer.basebuster.BaseBuster.BASE_FLAG_BLOCK_ENTITY;

public class BaseFlagBlockEntity extends BannerBlockEntity {
    // Segments rendered as '=' in the owner's capture progress bar
    private static final int PROGRESS_BAR_SEGMENTS = 10;
    // Ticks between capture progress notifications to the owner
    private static final int NOTIFY_INTERVAL_TICKS = 10;
    // Ticks without a capture click before player capture progress resets (one second)
    private static final int PLAYER_CAPTURE_TIMEOUT_TICKS = 20;
    // Ticks per Minecraft day, used to turn the configured mean raid interval into a per-tick chance
    private static final int TICKS_PER_DAY = 24000;
    // Remaining countdown at which the owner gets a final warning (30 seconds)
    private static final int RAID_WARNING_TICKS = 30 * 20;
    // Mark the entity dirty at this interval while a raid countdown runs so it survives saves
    private static final int RAID_SAVE_INTERVAL_TICKS = 20;

    // The name of the scoreboard team that owns this flag; null while unowned.
    // Raider factions own flags through their faction teams (see
    // Faction#ensureTeams), so this one field covers player and faction
    // ownership alike -- a faction-owned flag is one whose owning team is a
    // faction's.
    @Nullable
    private String ownerTeamName = null;
    // Ownership as written by pre-team saves, held only until
    // migrateLegacyOwnership resolves it against the scoreboard once a level
    // is attached. Never written to new saves.
    @Nullable
    private String legacyOwnerName = null;
    @Nullable
    private UUID legacyOwnerUuid = null;
    private List<RepairTarget> repairTargets = new ArrayList<>();

    // Cumulative victory-banner counts needed to hold each level:
    // LEVEL_REQUIREMENTS[level - 1][tier - 1]. A level is attained only when
    // every row entry is met, so each tier builds on all those below it.
    private static final int[][] LEVEL_REQUIREMENTS = {
            {},              // Level 1: default, no banners required
            {2, 0, 0, 0, 0}, // Level 2: 2x tier 1 banners
            {2, 2, 0, 0, 0}, // Level 3: +2x tier 2 banners
            {2, 2, 4, 0, 0}, // Level 4: +4x tier 3 banners
            {2, 2, 4, 4, 0}, // Level 5: +4x tier 4 banners
    };
    public static final int MAX_BASE_LEVEL = LEVEL_REQUIREMENTS.length;

    // Base level derived from victory banners within MAX_PROTECTION_RADIUS
    private int baseLevel = 1;

    // Base color applied by the owner right-clicking with a banner; BLACK is
    // the flag's own default. Patterns live in the inherited BANNER_PATTERNS
    // component. A captured flag renders in its owning faction's color
    // instead (see getBaseColor).
    private DyeColor flagColor = DyeColor.BLACK;

    // Progress toward a raider capture, in [0, 1)
    private float captureProgress = 0.0F;
    // Game time of the last capture tick; progress resets after a one-tick gap
    private long lastCaptureTick = -1L;
    // Capturer id -> game time of its most recent capture tick
    private final Map<UUID, Long> capturerTicks = new HashMap<>();
    // The faction accumulating the current captureProgress; a different
    // faction's capturers restart the channel
    @Nullable
    private Faction capturerFaction = null;

    // Whether the debug overlay renders the protection-radius sphere for this flag.
    // Persisted and synced to clients; toggled by right-clicking with a stick.
    private boolean debugBoundary = false;

    // Progress toward a team's right-click capture, in [0, 1)
    private float playerCaptureProgress = 0.0F;
    // Name of the team currently capturing: a different team's click resets
    // progress, while teammates' clicks compound toward the same capture
    @Nullable
    private String playerCapturerTeam = null;
    // Game time of the capturer's most recent click
    private long lastPlayerCaptureTick = -1L;

    // Ticks until a pending raid spawns; 0 means no raid is incoming
    private int raidCountdownTicks = 0;

    public BaseFlagBlockEntity(BlockPos pos, BlockState blockState) {
        // BannerBlockEntity hardcodes the vanilla BANNER type internally; we
        // report our own type via getType() so save/load still round-trips
        // through this class, while BannerRenderer sees a BannerBlockEntity.
        super(pos, blockState);
    }

    @Override
    public BlockEntityType<?> getType() {
        return BASE_FLAG_BLOCK_ENTITY.get();
    }

    @Override
    public DyeColor getBaseColor() {
        // The owner's applied color (black until then); a captured flag flies
        // its owning faction's color instead
        BlockState state = getBlockState();
        if (state.hasProperty(BaseFlagBlock.CAPTURED) && state.getValue(BaseFlagBlock.CAPTURED)) {
            // Flags captured before factions existed still read red
            Faction owner = this.ownerFaction();
            return owner != null ? owner.baseFlagColor() : DyeColor.RED;
        }
        return this.flagColor;
    }

    /**
     * The raider {@link Faction} this flag belongs to, or null when the
     * owning team isn't a faction's team (player-team-owned and unowned
     * flags). A flag is faction-owned exactly when its owning team is the
     * team {@link Faction#ensureTeams} created for that faction.
     */
    @Nullable
    private Faction ownerFaction() {
        return this.ownerTeamName != null ? Faction.byName(this.ownerTeamName) : null;
    }

    /**
     * Renames this flag; the base management GUI is the only caller.
     * {@code BannerBlockEntity} stores its name only in the implicit component
     * data (it exposes no setter), so this goes through the same component
     * machinery that applies named items on placement.
     */
    public void setFlagName(Component name) {
        applyComponents(
                DataComponentMap.EMPTY,
                DataComponentPatch.builder().set(DataComponents.CUSTOM_NAME, name).build());
        setChanged();
        syncSavedFlagInfo();
    }

    /**
     * Assigns this flag the next free "Base N" name for its owning team,
     * overwriting whatever name it had -- including names carried in by
     * anvil-named flag items, which the base management GUI now owns.
     */
    private void assignNextBaseName() {
        if (!(this.level instanceof ServerLevel serverLevel)
                || serverLevel.getServer() == null
                || this.ownerTeamName == null) {
            return;
        }
        setFlagName(Component.literal(FlagsSavedData.nextBaseName(
                serverLevel.getServer(), this.ownerTeamName,
                serverLevel, getBlockPos())));
    }

    /**
     * Keeps the "each base has a unique name within its team" invariant for
     * player-team-owned flags: a missing or colliding name is replaced by the
     * next free "Base N". Called when ownership lands on a player team and
     * when a flag is (re)loaded so pre-GUI names migrate.
     */
    private void ensureOwnedBaseName() {
        if (!(this.level instanceof ServerLevel serverLevel)
                || serverLevel.getServer() == null
                || this.ownerTeamName == null
                || this.ownerFaction() != null) {
            return;
        }
        Component customName = getCustomName();
        String current = customName != null ? customName.getString() : null;
        if (current == null || FlagsSavedData.isBaseNameTaken(
                serverLevel.getServer(), this.ownerTeamName, current,
                serverLevel, getBlockPos())) {
            assignNextBaseName();
        }
    }

    /**
     * Mirrors this flag's owner and base name into the level's
     * {@link FlagsSavedData} so names stay known -- and unique per team --
     * while the flag's chunk is unloaded.
     */
    private void syncSavedFlagInfo() {
        if (this.level instanceof ServerLevel serverLevel) {
            Component customName = getCustomName();
            FlagsSavedData.getFromLevel(serverLevel).setFlagInfo(getBlockPos(),
                    new FlagsSavedData.FlagInfo(
                            this.ownerTeamName,
                            customName != null ? customName.getString() : null));
        }
    }

    /**
     * Copies a banner item's base color and pattern layers onto this flag, e.g.
     * when the owner right-clicks with a banner.
     *
     * @return the applied design, so callers can record it for the player's
     *         other flags
     */
    public FlagsSavedData.FlagDesign applyBannerDesign(ItemStack banner) {
        BannerPatternLayers patterns = banner.get(DataComponents.BANNER_PATTERNS);
        if (patterns == null) {
            patterns = BannerPatternLayers.EMPTY;
        }
        DyeColor color = banner.getItem() instanceof BannerItem bannerItem
                ? bannerItem.getColor()
                : this.flagColor;
        this.applyDesign(color, patterns);
        return new FlagsSavedData.FlagDesign(color, patterns);
    }

    /**
     * Applies a stored {@link FlagsSavedData.FlagDesign} to this flag. Patterns
     * arrive through the same implicit-component machinery that applies anvil
     * names on placement; the base color lives on the banner's item type, not
     * in a data component, so it's recorded separately. Callers are
     * responsible for {@code sendBlockUpdated} when clients need the change.
     */
    public void applyDesign(DyeColor color, BannerPatternLayers patterns) {
        applyComponents(
                DataComponentMap.EMPTY,
                DataComponentPatch.builder().set(DataComponents.BANNER_PATTERNS, patterns).build());
        this.flagColor = color;
        setChanged();
    }

    /**
     * The owning faction's standard -- the pattern layers it hoists on
     * captured flags. Empty while the flag isn't faction-owned or the level
     * isn't known yet.
     */
    private BannerPatternLayers factionPatternLayers() {
        Faction owner = this.ownerFaction();
        if (this.level == null || owner == null) {
            return BannerPatternLayers.EMPTY;
        }
        return owner.baseFlagPatterns(this.level.registryAccess());
    }

    /**
     * The name of the team that controls this flag's territory -- a player
     * faction's name, or the raider faction's name for a captured flag. Null
     * while the flag is unowned.
     */
    @Nullable
    public String getTerritoryOwnerName() {
        return getOwnerTeamName();
    }

    public Component getInfoForDisplay() {
        String teamName = getOwnerTeamName();
        String ownerDisplay = teamName == null
                ? "NONE"
                : teamName + (this.ownerFaction() != null ? " (faction)" : "");
        Component name = getCustomName();
        return Component.literal(
                (name != null ? "Name: " + name.getString() + "\n" : "")
                        + "Owner: " + ownerDisplay + "\nLevel: " + baseLevel + "\nRepairs needed: " + repairTargets.size());
    }

    public int getBaseLevel() {
        return baseLevel;
    }

    /** This flag's protection radius, scaled to its current base level. */
    public int getProtectionRadius() {
        return BaseFlagBlock.protectionRadius(this.baseLevel);
    }

    /**
     * Flips the client-side protection-boundary overlay and returns its new state.
     * The caller is responsible for {@code sendBlockUpdated} so clients learn of the change.
     */
    public boolean toggleDebugBoundary() {
        this.debugBoundary = !this.debugBoundary;
        setChanged();
        return this.debugBoundary;
    }

    /** Whether the protection-boundary overlay is on for this flag. */
    public boolean isDebugBoundary() {
        return this.debugBoundary;
    }

    /**
     * Sets the protection-boundary overlay directly, e.g. from the base
     * management GUI's "Show base border" checkbox. The caller is responsible
     * for {@code sendBlockUpdated} so clients learn of the change.
     */
    public void setDebugBoundary(boolean visible) {
        this.debugBoundary = visible;
        setChanged();
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        migrateLegacyOwnership();
        // Loaded flags keep the saved-data name records fresh and claim a
        // unique base name if they were last saved before names existed
        ensureOwnedBaseName();
        syncSavedFlagInfo();
        syncDebugOverlayState();
        syncOwnerDesign();
    }

    /**
     * Brings a freshly loaded flag in line with the banner design its owner
     * last stamped on any of their flags. Runs when the flag's chunk registers
     * its block entities with the level, covering flags that were unloaded when
     * the design was applied; the corrected data rides the chunk's block-entity
     * payload to clients, so no sendBlockUpdated is needed.
     */
    private void syncOwnerDesign() {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (this.ownerFaction() != null) {
            // Faction-owned flags fly their faction's standard; ones captured
            // before it existed pick it up as they load
            BannerPatternLayers standard = this.factionPatternLayers();
            BannerPatternLayers current = this.components()
                    .getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
            if (!standard.equals(current)) {
                this.applyDesign(this.flagColor, standard);
            }
            return;
        }
        String team = this.getOwnerTeamName();
        if (team == null) {
            return;
        }
        FlagsSavedData.FlagDesign design =
                FlagsSavedData.getOverworld(serverLevel).getTeamDesign(team);
        if (design == null) {
            return;
        }
        BannerPatternLayers current = this.components()
                .getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        if (this.flagColor == design.color() && design.patterns().equals(current)) {
            return;
        }
        this.applyDesign(design.color(), design.patterns());
    }

    @Override
    public void setRemoved() {
        // Removed from the client level (unloaded chunk, broken flag): stop rendering
        FlagBoundaryDebugState.remove(getBlockPos());
        super.setRemoved();
    }

    // Keeps the client-side overlay set in step with the persisted flag. No-ops server-side
    // and whenever the level isn't known yet (e.g. mid chunk deserialization).
    private void syncDebugOverlayState() {
        if (this.level == null || !this.level.isClientSide) {
            return;
        }
        if (this.debugBoundary) {
            FlagBoundaryDebugState.add(getBlockPos());
        } else {
            FlagBoundaryDebugState.remove(getBlockPos());
        }
    }

    /**
     * Recomputes this flag's base level by counting victory banners (standing and
     * wall-mounted) inside {@link BaseFlagBlock#MAX_PROTECTION_RADIUS}. Banners
     * are always counted at the largest radius so the level doesn't depend on the
     * very radius it produces; the flag's own scaled radius then applies to
     * protection checks. Called by the banners' place/remove hooks whenever one
     * appears or disappears in range, and when the flag itself is placed.
     */
    public void recalculateLevel() {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockPos flagPos = getBlockPos();
        int radius = BaseFlagBlock.MAX_PROTECTION_RADIUS;
        double radiusSqr = Math.pow(radius, 2);
        int[] counts = new int[VictoryBannerBlock.MAX_TIER];
        for (BlockPos pos : BlockPos.betweenClosed(
                flagPos.offset(-radius, -radius, -radius),
                flagPos.offset(radius, radius, radius))) {
            // Same spherical boundary as grief protection
            if (pos.distSqr(flagPos) > radiusSqr) {
                continue;
            }
            int tier = VictoryBannerBlock.victoryBannerTier(serverLevel.getBlockState(pos));
            if (tier > 0) {
                counts[tier - 1]++;
            }
        }

        int newLevel = 1;
        for (int level = 2; level <= MAX_BASE_LEVEL; level++) {
            int[] requirements = LEVEL_REQUIREMENTS[level - 1];
            boolean met = true;
            for (int tier = 0; tier < requirements.length; tier++) {
                if (counts[tier] < requirements[tier]) {
                    met = false;
                    break;
                }
            }
            if (!met) {
                break;
            }
            newLevel = level;
        }

        if (newLevel != this.baseLevel) {
            boolean leveledUp = newLevel > this.baseLevel;
            this.baseLevel = newLevel;
            notifyOwnerTeam(
                    Component.literal(leveledUp
                                    ? "Your team's flag" + nameSuffix() + " has leveled up to level " + newLevel + "!"
                                    : "Your team's flag" + nameSuffix() + " has dropped to level " + newLevel + ".")
                            .withStyle(leveledUp ? ChatFormatting.GREEN : ChatFormatting.RED),
                    true);
            setChanged();
            serverLevel.sendBlockUpdated(flagPos, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * Server-side tick, registered by {@link BaseFlagBlock#getTicker}. While a
     * raid countdown is pending it ticks down toward the raid's arrival;
     * otherwise each tick rolls a fresh chance of starting one, so real
     * intervals scatter around {@link Config#RAID_TIMER_MEAN_DAYS} rather than
     * firing on a fixed schedule. Only flags whose owning team has a member
     * online roll -- unowned and faction-owned flags are never raided, and
     * nobody hears the warning with the whole team offline.
     */
    public static void tick(Level level, BlockPos pos, BlockState state, BaseFlagBlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (blockEntity.raidCountdownTicks > 0) {
            blockEntity.tickRaidCountdown(serverLevel, pos);
            return;
        }

        double meanDays = Config.RAID_TIMER_MEAN_DAYS.get();
        if (meanDays <= 0.0) {
            return;
        }
        if (!blockEntity.isPlayerTeamOwned() || blockEntity.onlineOwnerMembers().isEmpty()) {
            return;
        }
        if (serverLevel.getRandom().nextDouble() * meanDays * TICKS_PER_DAY >= 1.0) {
            return;
        }
        // Don't stack a second raid warning on a flag already under attack
        if (RaidSpawner.isRaidInProgress(serverLevel, pos)) {
            return;
        }
        blockEntity.beginRaidCountdown(serverLevel, pos);
    }

    /**
     * Starts this flag's raid countdown: the wither-spawn cue and the owning
     * team's warning message. A zero-second countdown skips the message and
     * goes straight to {@link #launchRaid}.
     */
    private void beginRaidCountdown(ServerLevel serverLevel, BlockPos pos) {
        int countdownSeconds = Config.RAID_COUNTDOWN_SECONDS.get();
        for (ServerPlayer member : onlineOwnerMembers()) {
            member.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.0F, 1.0F);
        }
        if (countdownSeconds <= 0) {
            this.launchRaid(serverLevel, pos);
            return;
        }
        this.raidCountdownTicks = countdownSeconds * 20;
        notifyOwnerTeam(approachWarning(pos, countdownSeconds), false);
        setChanged();
    }

    /**
     * Starts a raid countdown on this flag, e.g. when a player carrying the
     * Raid Omen effect crosses into its territory. After {@code delaySeconds}
     * the countdown hands off to {@link RaidSpawner} exactly like the periodic
     * raid timer does. Unlike the periodic timer this doesn't need anyone
     * online -- the warning message and sound are skipped when the whole
     * owning team is away.
     *
     * @return whether the countdown started; false when one is already running
     *         or a raid is in progress (this mod doesn't stack raids on a flag)
     */
    public boolean startRaidCountdown(int delaySeconds) {
        if (this.raidCountdownTicks > 0
                || (this.level instanceof ServerLevel serverLevel
                        && RaidSpawner.isRaidInProgress(serverLevel, getBlockPos()))) {
            return false;
        }
        for (ServerPlayer member : onlineOwnerMembers()) {
            member.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1.0F, 1.0F);
        }
        this.raidCountdownTicks = delaySeconds * 20;
        notifyOwnerTeam(approachWarning(getBlockPos(), delaySeconds), false);
        setChanged();
        return true;
    }

    /**
     * The owning team's "raiders are approaching" warning for a countdown
     * that just started, naming the flag when it has a custom name.
     */
    private Component approachWarning(BlockPos pos, int countdownSeconds) {
        return Component.literal("Raiders are approaching " + flagDisplayName() + " at " + formatPos(pos)
                        + "! You have " + formatMinutesSeconds(countdownSeconds) + " to prepare.")
                .withStyle(ChatFormatting.RED);
    }

    /** "your team's flag" or 'your team's flag "<custom name>"' when the flag is named. */
    private String flagDisplayName() {
        return "your team's flag" + nameSuffix();
    }

    /** ' "<custom name>"' when the flag is named, "" otherwise -- for embedding in messages. */
    private String nameSuffix() {
        Component name = getCustomName();
        return name != null ? " \"" + name.getString() + "\"" : "";
    }

    private void tickRaidCountdown(ServerLevel serverLevel, BlockPos pos) {
        if (--this.raidCountdownTicks <= 0) {
            this.raidCountdownTicks = 0;
            this.launchRaid(serverLevel, pos);
            return;
        }
        if (this.raidCountdownTicks == RAID_WARNING_TICKS) {
            notifyOwnerTeam(
                    Component.literal("30 seconds until raiders reach " + flagDisplayName()
                                    + " at " + formatPos(pos) + "!")
                            .withStyle(ChatFormatting.RED),
                    false);
        }
        if (this.raidCountdownTicks % RAID_SAVE_INTERVAL_TICKS == 0) {
            setChanged();
        }
    }

    /**
     * Hands the countdown's end to {@link RaidSpawner}. The party materializes
     * once its spawn searches finish, so the owner's "begun" message rides the
     * same callback the manual raid triggers use.
     */
    private void launchRaid(ServerLevel serverLevel, BlockPos pos) {
        RaidSpawner.startRaid(serverLevel, pos, () -> notifyOwnerTeam(
                Component.literal("The raid on " + flagDisplayName() + " at " + formatPos(pos) + " has begun!")
                        .withStyle(ChatFormatting.RED),
                false));
        setChanged();
    }

    private static String formatPos(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }

    /** "4 minutes and 32 seconds"; drops the minutes clause under a minute. */
    private static String formatMinutesSeconds(int totalSeconds) {
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        if (minutes <= 0) {
            return seconds + " seconds";
        }
        return minutes + " minutes and " + seconds + " seconds";
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
        if (tag.contains("owner_team")) {
            this.ownerTeamName = tag.getString("owner_team");
        } else if (tag.contains("owner_faction")) {
            // Pre-team saves recorded the raider faction itself; the faction's
            // team shares its name, so this maps straight onto team ownership
            Faction faction = Faction.byName(tag.getString("owner_faction"));
            this.ownerTeamName = faction != null ? faction.name() : null;
            // Unknown faction names (e.g. from an older save) load as unowned
        } else {
            // Pre-team saves recorded the owning player; migrateLegacyOwnership
            // maps them onto their current team once a level is attached
            if (tag.contains("owner_name")) {
                this.legacyOwnerName = tag.getString("owner_name");
            }
            if (tag.hasUUID("owner_uuid")) {
                this.legacyOwnerUuid = tag.getUUID("owner_uuid");
            }
        }
        if (tag.contains("base_level")) {
            this.baseLevel = tag.getInt("base_level");
        }
        // Absent means the flag's own default black; only non-default colors are saved
        this.flagColor = tag.contains("flag_color") ? DyeColor.byId(tag.getInt("flag_color")) : DyeColor.BLACK;
        // Absent means off: saveAdditional only writes debug_boundary while it's true
        this.debugBoundary = tag.getBoolean("debug_boundary");
        syncDebugOverlayState();
        if (tag.contains("raid_countdown_ticks")) {
            this.raidCountdownTicks = tag.getInt("raid_countdown_ticks");
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
        if (this.ownerTeamName != null) {
            tag.putString("owner_team", this.ownerTeamName);
        }
        tag.putInt("base_level", this.baseLevel);
        if (this.flagColor != DyeColor.BLACK) {
            tag.putInt("flag_color", this.flagColor.getId());
        }
        if (this.debugBoundary) {
            tag.putBoolean("debug_boundary", true);
        }
        if (this.raidCountdownTicks > 0) {
            tag.putInt("raid_countdown_ticks", this.raidCountdownTicks);
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

    /**
     * The scoreboard team that owns this flag, or null while unowned (or when
     * the recorded team no longer exists). Resolves on both logical sides:
     * the client scoreboard mirrors the server's teams.
     */
    @Nullable
    public PlayerTeam getOwnerTeam() {
        String team = this.getOwnerTeamName();
        return team != null && this.level != null
                ? this.level.getScoreboard().getPlayerTeam(team)
                : null;
    }

    /**
     * The name of the owning team as persisted in NBT, or null while
     * unowned. Unlike {@link #getOwnerTeam} this still reports the name when
     * the team was deleted from the scoreboard.
     */
    @Nullable
    public String getOwnerTeamName() {
        this.migrateLegacyOwnership();
        return this.ownerTeamName;
    }

    /**
     * Resolves pre-team saves once the flag has a server level to consult:
     * flags a player owned become owned by the team they're on now (or
     * unowned when they have none), matching the join-a-team requirement
     * this version introduces.
     */
    private void migrateLegacyOwnership() {
        if (!(this.level instanceof ServerLevel serverLevel)
                || this.ownerTeamName != null
                || (this.legacyOwnerName == null && this.legacyOwnerUuid == null)) {
            return;
        }
        String scoreboardName = this.legacyOwnerName;
        if (this.legacyOwnerUuid != null) {
            // getDisplayName can carry team decorations; the profile cache
            // holds the bare scoreboard name for anyone who ever played here
            Optional<GameProfile> profile = serverLevel.getServer().getProfileCache().get(this.legacyOwnerUuid);
            if (profile.isPresent()) {
                scoreboardName = profile.get().getName();
            }
        }
        this.ownerTeamName = null;
        if (scoreboardName != null) {
            PlayerTeam team = this.level.getScoreboard().getPlayersTeam(scoreboardName);
            if (team != null) {
                this.ownerTeamName = team.getName();
            }
        }
        this.legacyOwnerName = null;
        this.legacyOwnerUuid = null;
        setChanged();
        serverLevel.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        ensureOwnedBaseName();
        syncSavedFlagInfo();
    }

    /**
     * Whether {@code entity} is on the team that owns this flag; unowned
     * flags answer false for everyone. The team comparison works for any
     * entity -- players and raiders alike are scoreboard members.
     */
    public boolean isOwnedBy(Entity entity) {
        PlayerTeam team = this.getOwnerTeam();
        return team != null && entity.getTeam() == team;
    }

    /**
     * Whether a raider {@link Faction} owns this flag -- i.e. the owning team
     * is a faction's team. Faction-controlled areas block player respawn
     * points and can neither be raided nor recaptured by raiders.
     */
    public boolean isFactionOwned() {
        return this.ownerFaction() != null;
    }

    /** Whether a player team (as opposed to a raider faction) owns this flag. */
    public boolean isPlayerTeamOwned() {
        return this.getOwnerTeamName() != null && !this.isFactionOwned();
    }

    public boolean isUnowned() {
        return this.getOwnerTeamName() == null;
    }

    /** Every online player on the owning team; empty while the flag is unowned. */
    private List<ServerPlayer> onlineOwnerMembers() {
        PlayerTeam team = this.getOwnerTeam();
        MinecraftServer server = this.level instanceof ServerLevel serverLevel ? serverLevel.getServer() : null;
        if (team == null || server == null) {
            return List.of();
        }
        return team.getPlayers().stream()
                .map(server.getPlayerList()::getPlayerByName)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Sends a message to every online member of the owning team. */
    private void notifyOwnerTeam(Component message, boolean overlay) {
        for (ServerPlayer member : onlineOwnerMembers()) {
            member.displayClientMessage(message, overlay);
        }
    }

    /**
     * Transfers this flag to {@code team}: in-progress captures by raiders
     * and rival teams reset, and a reclaimed raider flag restores its
     * uncaptured look. The flag takes on the banner design the new team last
     * stamped on one of its flags, if any.
     */
    public void setOwnerTeam(Team team) {
        this.ownerTeamName = team.getName();
        this.legacyOwnerName = null;
        this.legacyOwnerUuid = null;
        this.captureProgress = 0.0F;
        this.capturerTicks.clear();
        this.capturerFaction = null;
        this.playerCaptureProgress = 0.0F;
        this.playerCapturerTeam = null;
        this.lastPlayerCaptureTick = -1L;
        if (this.level instanceof ServerLevel serverLevel) {
            // Reclaiming a raider-captured flag restores its uncaptured appearance
            if (getBlockState().hasProperty(BaseFlagBlock.CAPTURED)
                    && getBlockState().getValue(BaseFlagBlock.CAPTURED)) {
                BlockPos pos = getBlockPos();
                serverLevel.setBlock(pos, getBlockState().setValue(BaseFlagBlock.CAPTURED, false), 3);
                FlagsSavedData.getFromLevel(serverLevel).removeCapturedFlagLocation(pos);
            }

            // The flag takes on the banner design its new owning team last
            // stamped on one of its flags, if any
            FlagsSavedData.FlagDesign design =
                    FlagsSavedData.getOverworld(serverLevel).getTeamDesign(team.getName());
            if (design != null) {
                this.applyDesign(design.color(), design.patterns());
            } else {
                // Reclaiming a captured flag lowers the faction's standard
                this.applyDesign(this.flagColor, BannerPatternLayers.EMPTY);
            }
        }
        // A flag changing hands keeps its name only while it's still unique
        // in the new team; otherwise it's renamed like a fresh placement
        ensureOwnedBaseName();
        setChanged();
        syncSavedFlagInfo();
    }

    /**
     * Records one tick of capture activity by a raider standing next to this flag.
     * Progress resets when a full tick passes with no capturer. The capture rate
     * gains {@link Config#FLAG_CAPTURE_SPEED_BONUS} per participating raider, capped
     * at {@link Config#FLAG_CAPTURE_MAX_SPEED}, so a lone raider needs
     * {@link Config#FLAG_CAPTURE_SECONDS} seconds to finish.
     */
    public void tickCapture(UUID capturerId, Faction faction) {
        if (!(this.level instanceof ServerLevel serverLevel) || isFactionOwned()) {
            return;
        }

        long now = serverLevel.getGameTime();
        if (this.lastCaptureTick >= 0 && now - this.lastCaptureTick > 1) {
            this.captureProgress = 0.0F;
            this.capturerTicks.clear();
            this.capturerFaction = null;
        }
        if (this.capturerFaction != null && this.capturerFaction != faction) {
            // A different faction taking over the channel restarts it
            this.captureProgress = 0.0F;
            this.capturerTicks.clear();
        }
        this.capturerFaction = faction;
        this.lastCaptureTick = now;
        this.capturerTicks.put(capturerId, now);
        this.capturerTicks.values().removeIf(tick -> tick < now - 1);

        int participants = Math.max(1, this.capturerTicks.size());
        double speed = Math.min(
                1.0 + participants * Config.FLAG_CAPTURE_SPEED_BONUS.get(),
                Config.FLAG_CAPTURE_MAX_SPEED.get());
        this.captureProgress += (float) (speed / (participants * this.requiredCaptureTicks()));

        if (this.captureProgress >= 1.0F) {
            this.captureByFaction(faction, serverLevel);
            return;
        }

        if (now % NOTIFY_INTERVAL_TICKS == 0) {
            notifyOwnerTeam(this.captureProgressMessage(), true);
        }
        setChanged();
    }

    private double requiredCaptureTicks() {
        return Config.FLAG_CAPTURE_SECONDS.get() * 20.0;
    }

    private Component captureProgressMessage() {
        return Component.literal("Your team's flag" + nameSuffix() + " is being captured! " + progressBar(this.captureProgress))
                .withStyle(ChatFormatting.RED);
    }

    private static String progressBar(float progress) {
        int filled = Math.min(PROGRESS_BAR_SEGMENTS, (int) (progress * PROGRESS_BAR_SEGMENTS));
        String bar = "=".repeat(filled) + " ".repeat(PROGRESS_BAR_SEGMENTS - filled);
        return "[" + bar + "] " + (int) (progress * 100) + "%";
    }

    /**
     * Advances the clicking player's team capture of this flag by one
     * right-click.
     *
     * <p>The clicker must be on a team: flags belong to teams now, so a
     * teamless player is turned away in red. Only one team can hold capture
     * progress at a time -- a rival team's click discards the current
     * progress -- while teammates' clicks compound toward the same capture,
     * letting a squad take a flag faster together. Progress also resets if
     * more than a second passes between clicks. At 100% the flag transfers
     * to the clicking player's team, clearing any raider faction ownership
     * and captured appearance.
     *
     * @return whether the click was handled as a capture attempt (false when
     *         the clicker's team already owns the flag or it belongs to
     *         another player team)
     */
    public boolean advancePlayerCapture(Player player) {
        if (!(this.level instanceof ServerLevel serverLevel)) {
            return false;
        }

        Team playerTeam = player.getTeam();
        if (playerTeam == null) {
            player.displayClientMessage(
                    Component.literal("You have to join a team in order to claim a faction flag.")
                            .withStyle(ChatFormatting.RED),
                    true);
            return true;
        }

        if (this.isOwnedBy(player)) {
            return false;
        }

        // Flags owned by another player team can't be captured at all
        if (this.isPlayerTeamOwned()) {
            player.displayClientMessage(
                    Component.literal("You cannot capture a flag owned by another team.")
                            .withStyle(ChatFormatting.RED),
                    true);
            return false;
        }

        // A flag nobody owns is claimed instantly, no clicks needed
        if (this.isUnowned()) {
            this.completePlayerCapture(player, playerTeam);
            return true;
        }

        long now = serverLevel.getGameTime();
        if (this.lastPlayerCaptureTick >= 0 && now - this.lastPlayerCaptureTick > PLAYER_CAPTURE_TIMEOUT_TICKS) {
            // The previous capturer stopped clicking for over a second
            this.playerCaptureProgress = 0.0F;
            this.playerCapturerTeam = null;
        }
        this.lastPlayerCaptureTick = now;

        if (this.playerCapturerTeam != null && !this.playerCapturerTeam.equals(playerTeam.getName())) {
            // Contested: another team was mid-capture, so their progress resets
            this.playerCaptureProgress = 0.0F;
        }
        this.playerCapturerTeam = playerTeam.getName();
        this.playerCaptureProgress += Config.FLAG_CAPTURE_PLAYER_PROGRESS_PER_CLICK.get().floatValue();

        if (this.playerCaptureProgress >= 1.0F) {
            this.completePlayerCapture(player, playerTeam);
            return true;
        }

        player.displayClientMessage(
                Component.literal("Capturing flag... " + progressBar(this.playerCaptureProgress))
                        .withStyle(ChatFormatting.RED),
                true);
        setChanged();
        return true;
    }

    private void completePlayerCapture(Player player, Team team) {
        this.playerCaptureProgress = 0.0F;
        this.playerCapturerTeam = null;
        this.lastPlayerCaptureTick = -1L;
        this.setOwnerTeam(team);
        if (this.level instanceof ServerLevel serverLevel) {
            serverLevel.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
        player.displayClientMessage(
                Component.literal("Flag captured! Your team now owns it.")
                        .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
                true);
    }

    private void captureByFaction(Faction faction, ServerLevel serverLevel) {
        // Snapshotted before the transfer so the displaced team still gets told
        List<ServerPlayer> formerOwnerMembers = this.onlineOwnerMembers();
        // A captured flag can no longer repair its surroundings; drop them like
        // a flag broken by a player, but with no player to credit
        this.abandonRepairs(null);

        // Ownership passes to the raider faction's own scoreboard team
        this.ownerTeamName = faction.name();
        this.legacyOwnerName = null;
        this.legacyOwnerUuid = null;
        this.captureProgress = 0.0F;
        this.capturerTicks.clear();
        this.capturerFaction = null;
        this.playerCaptureProgress = 0.0F;
        this.playerCapturerTeam = null;
        this.lastPlayerCaptureTick = -1L;

        BlockPos pos = getBlockPos();
        serverLevel.setBlock(pos, getBlockState().setValue(BaseFlagBlock.CAPTURED, true), 3);
        FlagsSavedData.getFromLevel(serverLevel).addCapturedFlagLocation(pos);
        syncSavedFlagInfo();

        // The capturing faction hoists its own standard over the flag
        this.applyDesign(this.flagColor, faction.baseFlagPatterns(serverLevel.registryAccess()));

        // The area is now faction-controlled: no one may keep a respawn point in it
        FlagSpawnProtection.clearSpawnsInArea(serverLevel, pos);

        for (ServerPlayer member : formerOwnerMembers) {
            member.displayClientMessage(
                    Component.literal("Your team's flag" + nameSuffix() + " has been captured!")
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
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
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
