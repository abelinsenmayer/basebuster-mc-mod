package dev.abelinsenmayer.basebuster.events;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import dev.abelinsenmayer.basebuster.blocks.RaiderScaffoldingBlock;
import dev.abelinsenmayer.basebuster.blocks.VictoryBannerBlock;
import dev.abelinsenmayer.basebuster.entities.IRaider;
import dev.abelinsenmayer.basebuster.items.CitizenshipCertificateItem;
import dev.abelinsenmayer.basebuster.pathfinding.PathSearchScheduler;
import dev.abelinsenmayer.basebuster.raids.Faction;
import dev.abelinsenmayer.basebuster.raids.RaidSpawner;
import dev.abelinsenmayer.basebuster.world.FlagSpawnProtection;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDestroyBlockEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class BaseBusterEventBus {
    // Ticks between territory-entry checks for each player
    private static final int TERRITORY_CHECK_INTERVAL_TICKS = 20;
    // Extra blocks of slack before a player counts as having left a flag's
    // sphere, so hovering on the boundary doesn't spam enter/exit messages
    private static final int TERRITORY_EXIT_MARGIN = 4;

    // Registry id stored on a citizen roster entry for a librarian
    private static final ResourceLocation LIBRARIAN_PROFESSION = ResourceLocation.withDefaultNamespace("librarian");

    // Registry ids a golem smith's roster neighbors count for its
    // bonus-egg scaling
    private static final Set<ResourceLocation> GOLEM_SMITH_BONUS_PROFESSIONS = Set.of(
            ResourceLocation.withDefaultNamespace("toolsmith"),
            ResourceLocation.withDefaultNamespace("weaponsmith"));

    // Player id -> flag position whose territory currently contains them.
    // In-memory only; a stale entry just delays a re-entry message.
    private final Map<UUID, BlockPos> playerTerritories = new HashMap<>();

    // Item entity id -> scoreboard name of the player who threw a pledge
    // bound to their own faction. Renouncing a faction means burning one of
    // these, so the burn check only needs to consult this map.
    private final Map<UUID, String> pledgeThrowers = new HashMap<>();

    /** Advances all in-progress long-range path searches by their per-tick slice. */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        PathSearchScheduler.onServerTick();
    }

    /** Drops pending path searches; their state is in-memory only. */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        PathSearchScheduler.onServerStopping();
    }

    /**
     * Keeps the vanilla {@code disableRaids} game rule in sync with the
     * config. The game rule suppresses {@code Raids.createOrExtendRaid} (the
     * sole vanilla raid trigger) and stops any raid already in progress,
     * while leaving the raid omen effect itself fully intact.
     *
     * <p>Also backfills the scoreboard team every {@link Faction} rides under:
     * running at each server start means worlds created before a faction
     * existed still get its team, and new worlds get them all up front.
     */
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        syncDisableRaidsGameRule(event.getServer());
        Faction.ensureTeams(event.getServer());
    }

    /**
     * Every raider that enters the world joins its faction's scoreboard team,
     * which puts faction creatures and faction-owned flags under one team
     * identity. Team membership isn't persisted on entities, so this fires on
     * every join -- fresh spawns, spawn eggs, summons, and chunk reloads alike.
     */
    @SubscribeEvent
    public void onEntityJoinWorld(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)
                || !(event.getEntity() instanceof IRaider raider)
                || raider.getFaction() == null) {
            return;
        }
        PlayerTeam team = raider.getFaction().getTeam(serverLevel.getServer());
        if (team != null) {
            serverLevel.getScoreboard().addPlayerToTeam(event.getEntity().getScoreboardName(), team);
        }
    }

    /** Applies the config to the {@code disableRaids} game rule; call on the server thread. */
    public static void syncDisableRaidsGameRule(MinecraftServer server) {
        server.getGameRules()
                .getRule(GameRules.RULE_DISABLE_RAIDS)
                .set(!Config.VANILLA_RAIDS_ENABLED.getAsBoolean(), server);
    }

    /**
     * Same-faction immunity: any damage sourced from a raider -- melee or a
     * stray arrow (getEntity() resolves to the shooter) -- deals no damage to
     * another raider, which also keeps HurtByTargetGoal from triggering
     * infighting. Event-level (not a hurt override) so it covers every
     * {@link IRaider} species and cross-species hits uniformly.
     *
     * <p>The faction teams' friendlyFire flag ({@link Faction#ensureTeams})
     * does not replace this: vanilla only consults allowFriendlyFire when the
     * victim is a player (Player.canHarmPlayer), so mob->mob damage and
     * teammate retaliation would go through unchecked.
     */
    @SubscribeEvent
    public void onRaiderFriendlyFire(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof IRaider
                && event.getSource().getEntity() instanceof IRaider) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void livingDestroyBlock(LivingDestroyBlockEvent event) {
        Entity destroyer = event.getEntity();
        if (destroyer.level() instanceof ServerLevel serverLevel
                && protectedByRaid(serverLevel, event.getPos(), event.getState())) {
            event.setCanceled(true);
            return;
        }
        // The event fires before destruction, so the block entity is still in the world
        handleBlockGriefing(destroyer, event.getPos(), event.getState(), destroyer.level().getBlockEntity(event.getPos()));
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (enforceFlagBreakProtection(event)) {
            return;
        }
        handleBlockGriefing(event.getPlayer(), event.getPos(), event.getState(), event.getLevel().getBlockEntity(event.getPos()));
    }

    @SubscribeEvent
    public void onExplosionDetonate(ExplosionEvent.Detonate event) {
        // A flag under attack and its victory banners shrug off every explosion
        // for the raid's duration, no matter who set it off
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            event.getAffectedBlocks().removeIf(pos ->
                    protectedByRaid(serverLevel, pos, serverLevel.getBlockState(pos)));
        }
        Explosion explosion = event.getExplosion();
        // Prefer the entity that indirectly caused the explosion (e.g. the player who ignited the TNT)
        Entity responsible = explosion.getIndirectSourceEntity();
        if (responsible == null) {
            responsible = explosion.getDirectSourceEntity();
        }
        if (responsible == null) {
            return;
        }
        for (BlockPos pos : event.getAffectedBlocks()) {
            if (handleBlockGriefing(responsible, pos, event.getLevel().getBlockState(pos), event.getLevel().getBlockEntity(pos))) {
                event.getLevel().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    /**
     * Gates flag placement. First, flags belong to teams: a player without a
     * team can't place one at all, and is told so in red. Second, keeps flag
     * protection areas from overlapping: a new flag must sit far enough from
     * every existing flag that their spheres can never touch, even after
     * either side levels up. Spacing uses the max radius for the existing
     * flag (its current level can grow) plus the new flag's level-1 radius.
     * Cancelling also refunds the item to the placer.
     */
    @SubscribeEvent
    public void onFlagPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getPlacedBlock().getBlock() instanceof BaseFlagBlock)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (event.getEntity() instanceof ServerPlayer placer && placer.getTeam() == null) {
            event.setCanceled(true);
            placer.sendSystemMessage(Component.literal(
                    "You have to join a team in order to claim a faction flag.")
                    .withStyle(ChatFormatting.RED));
            return;
        }

        BlockPos pos = event.getPos();
        int minSeparation = BaseFlagBlock.MAX_PROTECTION_RADIUS * 2;
        BlockPos conflict = FlagsSavedData.getFromLevel(serverLevel).getFlagLocations().stream()
                .filter(flagPos -> flagPos.distSqr(pos) < (double) minSeparation * minSeparation)
                .min(Comparator.comparingDouble(flagPos -> flagPos.distSqr(pos)))
                .orElse(null);
        if (conflict == null) {
            return;
        }

        event.setCanceled(true);
        if (event.getEntity() instanceof ServerPlayer player) {
            Component conflictName = serverLevel.getBlockEntity(conflict) instanceof BaseFlagBlockEntity flagEntity
                    ? flagEntity.getCustomName()
                    : null;
            player.sendSystemMessage(Component.literal(
                    "Too close to the flag" + (conflictName != null ? " \"" + conflictName.getString() + "\"" : "")
                            + " at " + conflict.getX() + " " + conflict.getY() + " " + conflict.getZ()
                            + ": base flags must be at least " + minSeparation + " blocks apart.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Bars players from setting a respawn point (bed click, respawn anchor,
     * /spawnpoint) inside a faction-owned flag's protected area. Cancelling
     * the event also suppresses the vanilla "Respawn point set" message.
     */
    @SubscribeEvent
    public void onPlayerSetSpawn(PlayerSetSpawnEvent event) {
        BlockPos newSpawn = event.getNewSpawn();
        if (newSpawn == null || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel spawnLevel = player.server.getLevel(event.getSpawnLevel());
        if (spawnLevel == null) {
            return;
        }
        if (FlagSpawnProtection.controllingFlag(spawnLevel, newSpawn) != null) {
            event.setCanceled(true);
            player.sendSystemMessage(Component.literal(
                    "You can't set your respawn point here: the area is controlled by a hostile faction.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Drops a stored respawn point that ended up inside a faction-controlled
     * area while the player was offline (e.g. their flag was captured).
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            FlagSpawnProtection.clearSpawnIfControlled(player);
        }
    }

    /**
     * Announces territory entries: when a player crosses into an owned flag's
     * protection sphere, they get a brief action-bar message naming the owner
     * (and the flag itself, if it has a custom name). Tracked per player so the
     * message fires on entry only; a small exit margin keeps boundary jitter
     * from retriggering it.
     */
    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || player.tickCount % TERRITORY_CHECK_INTERVAL_TICKS != 0) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockPos pos = player.blockPosition();
        BlockPos previous = this.playerTerritories.get(player.getUUID());
        BlockPos current = nearestCoveringFlag(level, pos);

        if (current == null && previous != null
                && FlagsSavedData.getFromLevel(level).getFlagLocations().contains(previous)) {
            int radius = BaseFlagBlock.protectionRadius(level, previous) + TERRITORY_EXIT_MARGIN;
            if (previous.distSqr(pos) <= (double) radius * radius) {
                // Just outside the sphere but inside the margin: still "in"
                current = previous;
            }
        }

        if (Objects.equals(previous, current)) {
            return;
        }
        if (current == null) {
            this.playerTerritories.remove(player.getUUID());
            return;
        }
        this.playerTerritories.put(player.getUUID(), current);
        notifyTerritoryEntry(level, current, player);
        tryTriggerOmenRaid(level, current, player);
    }

    /**
     * Omen trigger: a player carrying Bad Omen or Raid Omen who enters
     * territory claimed by a <em>different</em> team loses the effect, and
     * the flag's own raid countdown (the same persisted timer periodic raids
     * use) launches a raid on that flag after
     * {@link Config#RAID_OMEN_COUNTDOWN_SECONDS} seconds. Unowned flags, a
     * flag the player's own team holds, and faction-held territory don't
     * consume the effect, nor does a flag that can't start a countdown (one
     * pending, or a raid already in progress).
     */
    private static void tryTriggerOmenRaid(ServerLevel level, BlockPos flagPos, ServerPlayer player) {
        boolean hasRaidOmen = player.hasEffect(MobEffects.RAID_OMEN);
        boolean hasBadOmen = player.hasEffect(MobEffects.BAD_OMEN);
        if (!hasRaidOmen && !hasBadOmen) {
            return;
        }
        if (!(level.getBlockEntity(flagPos) instanceof BaseFlagBlockEntity flagEntity)
                || flagEntity.isFactionOwned()
                || flagEntity.isOwnedBy(player)) {
            return;
        }
        String owner = flagEntity.getTerritoryOwnerName();
        if (owner == null) {
            return;
        }
        int countdownSeconds = Config.RAID_OMEN_COUNTDOWN_SECONDS.get();
        if (!flagEntity.startRaidCountdown(countdownSeconds)) {
            return;
        }
        player.removeEffect(MobEffects.RAID_OMEN);
        player.removeEffect(MobEffects.BAD_OMEN);
        Component flagName = flagEntity.getCustomName();
        player.displayClientMessage(
                Component.literal("Your omen stirs -- a raid will strike " + owner + "'s flag"
                                + (flagName != null ? " \"" + flagName.getString() + "\"" : "")
                                + " in " + countdownSeconds + " seconds")
                        .withStyle(ChatFormatting.DARK_RED),
                false);
    }

    private static void notifyTerritoryEntry(ServerLevel level, BlockPos flagPos, ServerPlayer player) {
        if (!(level.getBlockEntity(flagPos) instanceof BaseFlagBlockEntity flagEntity)) {
            return;
        }
        String owner = flagEntity.getTerritoryOwnerName();
        if (owner == null) {
            return;
        }
        MutableComponent message = Component.literal("You have entered " + owner + "'s territory");
        Component flagName = flagEntity.getCustomName();
        if (flagName != null) {
            message.append(": ").append(flagName);
        }
        player.displayClientMessage(message.withStyle(ChatFormatting.GRAY), true);
    }

    /** Drops the player's territory tracking entry when they disconnect. */
    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        this.playerTerritories.remove(event.getEntity().getUUID());
    }

    /**
     * Remembers who threw a faction pledge bound to their own team. Only
     * those pledges can renounce a faction when burned -- a pledge bound to
     * another faction, or an unbound one, is just trash in the fire.
     */
    @SubscribeEvent
    public void onPledgeToss(ItemTossEvent event) {
        ItemEntity item = event.getEntity();
        if (!(item.level() instanceof ServerLevel)) {
            return;
        }
        String boundFaction = item.getItem().get(BaseBuster.FACTION.get());
        PlayerTeam team = event.getPlayer().getTeam();
        if (boundFaction != null && team != null && boundFaction.equals(team.getName())) {
            this.pledgeThrowers.put(item.getUUID(), event.getPlayer().getScoreboardName());
        }
    }

    /**
     * The renunciation: when a tracked pledge burns in fire or lava, its
     * thrower leaves the bound faction -- provided they're still on that
     * team (switching factions between toss and burn cancels it). The
     * pledge is discarded either way since it's about to be destroyed
     * anyway; this just makes single-fire explicit.
     */
    @SubscribeEvent
    public void onItemEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof ItemEntity item)
                || !(item.level() instanceof ServerLevel serverLevel)
                || !pledgeThrowers.containsKey(item.getUUID())) {
            return;
        }
        if (!item.isOnFire() && !item.isInLava()) {
            return;
        }
        String playerName = pledgeThrowers.remove(item.getUUID());
        item.discard();
        String boundFaction = item.getItem().get(BaseBuster.FACTION.get());
        if (boundFaction == null) {
            return;
        }
        Scoreboard scoreboard = serverLevel.getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(playerName);
        if (team == null || !team.getName().equals(boundFaction)) {
            return;
        }
        scoreboard.removePlayerFromTeam(playerName, team);
        Player thrower = serverLevel.getServer().getPlayerList().getPlayerByName(playerName);
        if (thrower != null) {
            thrower.sendSystemMessage(Component.translatable(
                    "item.basebuster.faction_pledge.left", team.getDisplayName()));
        }
    }

    /** Drops a tracked pledge's toss record when its entity leaves the world. */
    @SubscribeEvent
    public void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        this.pledgeThrowers.remove(event.getEntity().getUUID());
    }

    /**
     * A dead citizen leaves its flag's roster immediately -- the flag's
     * periodic scan would otherwise take minutes to notice the absence.
     * Citizens are matched by roster, not by where they died, so one that
     * wandered out of the sphere is still caught. Only loaded flags answer;
     * a citizen dying while its flag's chunk is unloaded stays covered by
     * the scan once the flag next ticks.
     */
    @SubscribeEvent
    public void onVillagerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Villager villager)
                || !(villager.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        UUID villagerId = villager.getUUID();
        for (BlockPos flagPos : FlagsSavedData.getFromLevel(serverLevel).getFlagLocations()) {
            // getBlockEntity would load an unloaded flag's chunk; skip it
            if (!serverLevel.isLoaded(flagPos)
                    || !(serverLevel.getBlockEntity(flagPos) instanceof BaseFlagBlockEntity flagEntity)) {
                continue;
            }
            if (flagEntity.onCitizenDeath(villagerId)) {
                serverLevel.sendBlockUpdated(flagPos, flagEntity.getBlockState(), flagEntity.getBlockState(), 3);
                break;
            }
        }
    }

    /**
     * Papers, please: a faction pledge pressed against a villager becomes a
     * {@link CitizenshipCertificateItem} carrying the villager's full NBT.
     * The interaction is canceled so the trade GUI never opens. Canceled on
     * both sides to keep the client's prediction in step (the swing plays
     * either way), but the pledge conversion itself is server-side only.
     *
     * <p>{@code EntityInteractSpecific} is the primary path -- the client
     * predicts a villager right-click as consumed, so only the INTERACT_AT
     * packet reaches the server. {@code EntityInteract} is a fallback for
     * predictions that pass through to a plain interact packet.
     */
    @SubscribeEvent
    public void onVillagerPledgeInteractAt(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!(event.getTarget() instanceof Villager villager) || !isFactionPledge(event.getItemStack())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        convertPledgeToCertificate(event.getEntity(), event.getHand(), villager, event.getItemStack());
    }

    @SubscribeEvent
    public void onVillagerPledgeInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Villager villager) || !isFactionPledge(event.getItemStack())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        convertPledgeToCertificate(event.getEntity(), event.getHand(), villager, event.getItemStack());
    }

    /**
     * Any faction pledge can issue citizenship, bound or not: a bound pledge
     * joins its faction on ordinary right-click, but on a villager it always
     * issues a certificate instead.
     */
    private static boolean isFactionPledge(ItemStack stack) {
        return stack.is(BaseBuster.FACTION_PLEDGE.get());
    }

    /** Server side: turns the held pledge into a certificate holding the villager, who leaves the world. */
    private static void convertPledgeToCertificate(Player player, InteractionHand hand, Villager villager, ItemStack stack) {
        if (villager.level().isClientSide() || villager.isRemoved()) {
            return;
        }
        ItemStack certificate = CitizenshipCertificateItem.capture(villager);
        villager.discard();
        if (stack.getCount() == 1) {
            player.setItemInHand(hand, certificate);
        } else {
            stack.shrink(1);
            if (!player.addItem(certificate)) {
                player.drop(certificate, false);
            }
        }
    }

    /**
     * Citizen trade discounts: a player opening trade with a villager that is
     * a citizen of the flag whose protection sphere contains it pays
     * {@link Config#CITIZEN_TRADE_DISCOUNT_PER_LEVEL} per base level less on
     * every offer. The discount rides {@code MerchantOffer.specialPriceDiff},
     * the same field vanilla's reputation and Hero-of-the-Village discounts
     * use, so it shows in the GUI as a reduced price and governs what the
     * trade actually costs. Diffs reset when the menu closes
     * ({@code Villager.stopTrading}), so each trade session recomputes afresh.
     *
     * <p>{@code EntityInteractSpecific} covers the INTERACT_AT packet, which
     * is what the client sends for villager right-clicks; {@code EntityInteract}
     * covers plain INTERACT as a fallback -- see the pledge handlers above.
     * Server-side only: mutating client-side offers would never reach a packet.
     *
     * <p>The same hook also scales an arcanist citizen's Bottle o' Enchanting
     * results by the flag's librarian headcount, a golem smith citizen's
     * Iron Golem spawn egg results by its toolsmith and weaponsmith
     * headcount, and a tax collector citizen's emerald payout by the whole
     * roster -- see {@link #scaleArcanistOffers},
     * {@link #scaleGolemSmithOffers}, and {@link #scaleTaxCollectorOffers}.
     */
    @SubscribeEvent
    public void onCitizenTradeInteractAt(PlayerInteractEvent.EntityInteractSpecific event) {
        applyCitizenTradeDiscount(event.getEntity(), event.getTarget());
    }

    @SubscribeEvent
    public void onCitizenTradeInteract(PlayerInteractEvent.EntityInteract event) {
        applyCitizenTradeDiscount(event.getEntity(), event.getTarget());
    }

    private static void applyCitizenTradeDiscount(Player player, Entity target) {
        if (!(target instanceof Villager villager)
                || !(villager.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        // Mirror the preconditions under which Villager.mobInteract opens the
        // trade GUI. A diff applied without the GUI opening would linger on
        // the offer -- nothing would reset it -- and compound on the next click.
        if (!villager.isAlive() || villager.isTrading() || villager.isSleeping()
                || villager.isBaby() || villager.getOffers().isEmpty()
                || player.isSecondaryUseActive()) {
            return;
        }
        // Same team as the trader -- citizens join the flag's owner team
        if (!player.isAlliedTo(villager)) {
            return;
        }
        // Nearest flag whose sphere covers the villager; being outside every
        // sphere means no flag's citizen discount applies
        BlockPos flagPos = nearestCoveringFlag(serverLevel, villager.blockPosition());
        if (flagPos == null
                || !(serverLevel.getBlockEntity(flagPos) instanceof BaseFlagBlockEntity flagEntity)
                || !flagEntity.isCitizen(villager.getUUID())) {
            return;
        }
        double fraction = Config.CITIZEN_TRADE_DISCOUNT_PER_LEVEL.get() * flagEntity.getBaseLevel();
        if (fraction > 0.0) {
            for (MerchantOffer offer : villager.getOffers()) {
                int discount = (int) Math.floor(fraction * offer.getBaseCostA().getCount());
                offer.addToSpecialPriceDiff(-discount);
            }
        }
        scaleArcanistOffers(villager, flagEntity);
        scaleGolemSmithOffers(villager, flagEntity);
        scaleTaxCollectorOffers(villager, flagEntity);
    }

    /**
     * Rewrites an arcanist citizen's offers so each yields the table's base
     * bottle count plus {@link Config#ARCANIST_BOTTLES_PER_LIBRARIAN} per
     * librarian on the flag's roster. {@code MerchantOffer.getResult()} hands
     * out the live stack ({@code assemble} copies it on trade), so setting
     * its count changes both the GUI listing and the payout; unlike
     * {@code specialPriceDiff} nothing resets it, so counts are recomputed
     * absolute -- never compounded -- on every trade open, which is also what
     * shrinks them back when librarians leave.
     *
     * <p>Offer index maps to table tier because the arcanist's table produces
     * exactly one offer per level; {@code Items.EXPERIENCE_BOTTLE} is a
     * defensive check, not the mapping. Roster entries track the live
     * villager's profession each scan, so a citizen that just picked up
     * library work counts once the next scan lands.
     */
    private static void scaleArcanistOffers(Villager villager, BaseFlagBlockEntity flagEntity) {
        if (villager.getVillagerData().getProfession() != BaseBuster.ARCANIST.get()) {
            return;
        }
        int librarians = (int) flagEntity.getCitizens().stream()
                .filter(citizen -> LIBRARIAN_PROFESSION.equals(citizen.profession()))
                .count();
        int bonus = librarians * Config.ARCANIST_BOTTLES_PER_LIBRARIAN.get();
        int[] bases = BaseBuster.ARCANIST_TIER_BOTTLES;
        var offers = villager.getOffers();
        for (int i = 0; i < offers.size() && i < bases.length; i++) {
            MerchantOffer offer = offers.get(i);
            if (offer.getResult().is(Items.EXPERIENCE_BOTTLE)) {
                offer.getResult().setCount(Math.min(bases[i] + bonus,
                        Items.EXPERIENCE_BOTTLE.getDefaultMaxStackSize()));
            }
        }
    }

    /**
     * Rewrites a golem smith citizen's offers so each yields the table's base
     * egg count plus {@link Config#GOLEM_SMITH_EGGS_PER_SMITH} per toolsmith
     * and weaponsmith on the flag's roster. Same absolute-recompute scheme as
     * {@link #scaleArcanistOffers}: counts reset to base + bonus on every
     * trade open rather than compounding.
     *
     * <p>Offer index maps to table tier because the golem smith's table
     * produces exactly one offer per level; {@code Items.IRON_GOLEM_SPAWN_EGG}
     * is a defensive check, not the mapping.
     */
    private static void scaleGolemSmithOffers(Villager villager, BaseFlagBlockEntity flagEntity) {
        if (villager.getVillagerData().getProfession() != BaseBuster.GOLEM_SMITH.get()) {
            return;
        }
        int smiths = (int) flagEntity.getCitizens().stream()
                .filter(citizen -> GOLEM_SMITH_BONUS_PROFESSIONS.contains(citizen.profession()))
                .count();
        int bonus = smiths * Config.GOLEM_SMITH_EGGS_PER_SMITH.get();
        int[] bases = BaseBuster.GOLEM_SMITH_TIER_EGGS;
        var offers = villager.getOffers();
        for (int i = 0; i < offers.size() && i < bases.length; i++) {
            MerchantOffer offer = offers.get(i);
            if (offer.getResult().is(Items.IRON_GOLEM_SPAWN_EGG)) {
                offer.getResult().setCount(Math.min(bases[i] + bonus,
                        Items.IRON_GOLEM_SPAWN_EGG.getDefaultMaxStackSize()));
            }
        }
    }

    /**
     * Rewrites a tax collector citizen's offer so it yields one emerald plus
     * one bonus emerald per
     * {@link BaseBuster#TAX_COLLECTOR_CITIZENS_PER_EMERALD} citizens on the
     * flag's roster -- a ratio that improves with base level from one per 7
     * down to one per 2 at level 5. The roster counts include the collector
     * itself. Same absolute-recompute scheme as the other scalers: the
     * result resets on every trade open rather than compounding.
     */
    private static void scaleTaxCollectorOffers(Villager villager, BaseFlagBlockEntity flagEntity) {
        if (villager.getVillagerData().getProfession() != BaseBuster.TAX_COLLECTOR.get()) {
            return;
        }
        int index = Math.min(Math.max(flagEntity.getBaseLevel(), 1),
                BaseBuster.TAX_COLLECTOR_CITIZENS_PER_EMERALD.length) - 1;
        int citizensPerEmerald = BaseBuster.TAX_COLLECTOR_CITIZENS_PER_EMERALD[index];
        int count = BaseBuster.TAX_COLLECTOR_TRADE_EMERALDS
                + flagEntity.getCitizens().size() / citizensPerEmerald;
        for (MerchantOffer offer : villager.getOffers()) {
            if (offer.getResult().is(Items.EMERALD)) {
                offer.getResult().setCount(Math.min(count, Items.EMERALD.getDefaultMaxStackSize()));
            }
        }
    }

    /**
     * Lets a sneaking player use the flag's right-click interactions (capture,
     * debug toggle, repairs, raid triggers) even while holding an item such as
     * an offhand shield. Vanilla skips block interaction entirely when sneaking
     * with either hand occupied, which would otherwise make shift-click captures
     * possible only with empty hands. The flag's own handlers still decide what
     * happens, and items keep their normal behavior whenever the flag passes the
     * interaction through.
     */
    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity().isSecondaryUseActive()
                && event.getLevel().getBlockState(event.getPos()).getBlock() instanceof BaseFlagBlock) {
            event.setUseBlock(TriState.TRUE);
        }
    }

    @SubscribeEvent
    public void onBlockDrop(BlockDropsEvent event) {
        Entity breaker = event.getBreaker();
        if (breaker == null) {
            return;
        }
        if (handleBlockGriefing(breaker, event.getPos(), event.getState(), event.getBlockEntity())) {
            // Cancel the event so we don't drop any items or experience
            event.setCanceled(true);
        }
    }

    /**
     * Cancels a player break that flag protection forbids: a flag being raided
     * (or one of its victory banners) cannot be broken at all except by
     * Creative-mode players, and non-Creative players can only break flags and
     * attached banners they own.
     *
     * @return whether the event was canceled
     */
    private boolean enforceFlagBreakProtection(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player.isCreative() || !(event.getLevel() instanceof ServerLevel serverLevel)) {
            return false;
        }
        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        if (state.getBlock() instanceof BaseFlagBlock) {
            return guardFlagBreak(event, serverLevel, pos, player, pos);
        }
        if (VictoryBannerBlock.victoryBannerTier(state) > 0) {
            BlockPos flagPos = nearestCoveringFlag(serverLevel, pos);
            if (flagPos != null) {
                return guardFlagBreak(event, serverLevel, flagPos, player, pos);
            }
        }
        return false;
    }

    /**
     * Applies the break rules for {@code brokenPos}, which is either the flag
     * at {@code flagPos} or a victory banner inside its protection radius.
     *
     * @return whether the break is forbidden
     */
    private boolean guardFlagBreak(BlockEvent.BreakEvent event, ServerLevel level, BlockPos flagPos, Player player, BlockPos brokenPos) {
        if (RaidSpawner.isRaidInProgress(level, flagPos)) {
            denyBreak(event, player, brokenPos.equals(flagPos)
                    ? "This flag cannot be broken while a raid is attacking it."
                    : "This banner cannot be broken while a raid is attacking its flag.");
            return true;
        }
        BaseFlagBlockEntity flagEntity = level.getBlockEntity(flagPos) instanceof BaseFlagBlockEntity entity
                ? entity
                : null;
        if (flagEntity == null || !flagEntity.isOwnedBy(player)) {
            denyBreak(event, player, brokenPos.equals(flagPos)
                    ? "You cannot break a flag your team does not own."
                    : "You cannot break banners attached to a flag your team does not own.");
            return true;
        }
        return false;
    }

    private static void denyBreak(BlockEvent.BreakEvent event, Player player, String message) {
        event.setCanceled(true);
        player.displayClientMessage(Component.literal(message).withStyle(ChatFormatting.RED), true);
    }

    /**
     * Whether a raid currently protects the block at {@code pos}: it is a flag
     * under attack, or a victory banner attached to a flag under attack.
     */
    private static boolean protectedByRaid(ServerLevel level, BlockPos pos, BlockState state) {
        BlockPos flagPos;
        if (state.getBlock() instanceof BaseFlagBlock) {
            flagPos = pos;
        } else if (VictoryBannerBlock.victoryBannerTier(state) > 0) {
            flagPos = nearestCoveringFlag(level, pos);
        } else {
            return false;
        }
        return flagPos != null && RaidSpawner.isRaidInProgress(level, flagPos);
    }

    /**
     * The nearest registered flag whose protection radius covers {@code pos};
     * a farther higher-level flag can cover a spot a nearer lower-level flag
     * cannot. Null when no flag's sphere reaches the position.
     */
    @Nullable
    private static BlockPos nearestCoveringFlag(ServerLevel level, BlockPos pos) {
        double maxRadiusSqr = Math.pow(BaseFlagBlock.MAX_PROTECTION_RADIUS, 2);
        return FlagsSavedData.getFromLevel(level).getFlagLocations().stream()
                .filter(flagPos -> flagPos.distSqr(pos) <= maxRadiusSqr)
                .filter(flagPos -> flagPos.distSqr(pos)
                        <= Math.pow(BaseFlagBlock.protectionRadius(level, flagPos), 2))
                .min(Comparator.comparingDouble(flagPos -> flagPos.distSqr(pos)))
                .orElse(null);
    }

    /**
     * Determines whether a block destroyed by a known entity falls under a nearby flag's protection.
     * If the culprit isn't on the team that owns the protecting flag, the block is registered
     * as a repair target so the flag can restore it later.
     *
     * @param culprit     the entity responsible for destroying the block
     * @param pos         the position of the destroyed block
     * @param state       the block state prior to destruction
     * @param blockEntity the block entity that was at {@code pos}, if any
     * @return whether the block was registered as a repair target on a flag
     */
    public static boolean handleBlockGriefing(Entity culprit, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) {
        if (state.getBlock() == Blocks.AIR) {
            return false;
        }

        // A broken flag can't repair itself
        if (state.getBlock() instanceof BaseFlagBlock) {
            return false;
        }

        // Raider scaffolding is temporary clutter, not part of the base:
        // repairing it would just respawn blocks that remove themselves anyway
        if (state.getBlock() instanceof RaiderScaffoldingBlock) {
            return false;
        }

        if (!(culprit.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        // See if there's a flag nearby
        // The flag that handles the grief report is the nearest one whose own
        // radius reaches the broken block
        BlockPos nearestFlag = nearestCoveringFlag(serverLevel, pos);
        if (nearestFlag == null) {
            return false;
        }

        BaseFlagBlockEntity flagEntity = (BaseFlagBlockEntity) serverLevel.getBlockEntity(nearestFlag);
        if (flagEntity == null) {
            return false;
        }

        // If the flag has no owner, or the culprit is on the team that owns
        // it, do nothing
        if (flagEntity.isUnowned() || flagEntity.isOwnedBy(culprit)) {
            return false;
        }

        // If the culprit is not an owner, store the block as a repair target on the flag
        flagEntity.addRepairTarget(pos, state, blockEntity);
        // Remove the block entity before the block is destroyed so Containers.dropContentsOnDestroy
        // finds nothing at this position. Otherwise container contents (e.g. chests) would be
        // scattered as item entities and cleared from the saved block entity.
        serverLevel.removeBlockEntity(pos);
        serverLevel.sendBlockUpdated(flagEntity.getBlockPos(), flagEntity.getBlockState(), flagEntity.getBlockState(), 3);
        return true;
    }
}
