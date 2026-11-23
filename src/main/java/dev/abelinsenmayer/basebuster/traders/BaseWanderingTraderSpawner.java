package dev.abelinsenmayer.basebuster.traders;

import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacementType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.horse.TraderLlama;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.entity.npc.WanderingTraderSpawner;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.ServerLevelData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The mod's wandering trader spawner, swapped in for the vanilla
 * {@code WanderingTraderSpawner} via {@code ModifyCustomSpawnersEvent} (see
 * {@link dev.abelinsenmayer.basebuster.events.BaseBusterEventBus#onModifyCustomSpawners}).
 * The countdown cadence is vanilla's: every {@link #TICK_DELAY} ticks the
 * persisted spawn delay counts down one notch, and when it lapses the
 * persisted spawn chance is rolled, so this rides the same world-data timer
 * the vanilla spawner would have used.
 *
 * <p>Which flavor of trader spawning runs is chosen per tick: while
 * {@code wanderingTraders.replaceVanilla} is off, ticks delegate to the
 * vanilla spawner this one replaced, so the config applies live without
 * rebuilding the level's spawner list. The vanilla
 * {@code doTraderSpawning} game rule gates both flavors -- vanilla checks
 * it itself, and this spawner checks it before its own logic.
 *
 * <p>Where vanilla picks a random player, this picks a random loaded base
 * flag. The caravan's wander target is the nearest {@code MEETING} POI within
 * the base's protection radius, or the flag itself when the base has none.
 * The first trader lands on a random bearing {@link #MIN_SPAWN_BUFFER} to
 * {@link #MAX_SPAWN_BUFFER} blocks outside that radius; the rest of the
 * caravan scatters around it. One trader spawns per base level, and the
 * llama train scales with the caravan ({@link #llamasFor}).
 */
public class BaseWanderingTraderSpawner implements CustomSpawner {
    // Vanilla cadence (WanderingTraderSpawner): a notch of spawn delay falls
    // every tick delay; once it lapses, a rising spawn chance is rolled
    private static final int TICK_DELAY = 1200;
    private static final int SPAWN_DELAY = 24000;
    private static final int MIN_SPAWN_CHANCE = 25;
    private static final int MAX_SPAWN_CHANCE = 75;
    private static final int SPAWN_CHANCE_INCREASE = 25;
    // One-in-N roll gating each lapse's spawn attempt
    private static final int SPAWN_ONE_IN_X_CHANCE = 10;
    // Bearings tried per caravan before giving up on the ring
    private static final int SPAWN_POSITION_ATTEMPTS = 16;
    // Vanilla: the trader despawns two Minecraft days after spawning
    private static final int DESPAWN_DELAY_TICKS = 48000;
    // Vanilla: the trader's restriction radius around its wander target
    private static final int RESTRICT_RADIUS = 16;
    // Spawn ring: this many blocks beyond the flag's protection radius
    private static final int MIN_SPAWN_BUFFER = 1;
    private static final int MAX_SPAWN_BUFFER = 30;
    // Extra traders and llamas scatter within this distance of the caravan's first trader
    private static final int CARAVAN_SPAWN_RADIUS = 8;
    // Attempts to find each caravan member its own spot before sharing the first trader's
    private static final int CARAVAN_SPAWN_ATTEMPTS = 10;

    private final RandomSource random = RandomSource.create();
    private final ServerLevelData serverLevelData;
    // The vanilla spawner this one replaced; ticks delegate to it while
    // wanderingTraders.replaceVanilla is off
    private final WanderingTraderSpawner vanillaDelegate;
    private int tickDelay;
    private int spawnDelay;
    private int spawnChance;

    public BaseWanderingTraderSpawner(ServerLevelData serverLevelData, WanderingTraderSpawner vanillaDelegate) {
        this.vanillaDelegate = vanillaDelegate;
        this.serverLevelData = serverLevelData;
        this.tickDelay = TICK_DELAY;
        this.spawnDelay = serverLevelData.getWanderingTraderSpawnDelay();
        this.spawnChance = serverLevelData.getWanderingTraderSpawnChance();
        if (this.spawnDelay == 0 && this.spawnChance == 0) {
            this.spawnDelay = SPAWN_DELAY;
            serverLevelData.setWanderingTraderSpawnDelay(this.spawnDelay);
            this.spawnChance = MIN_SPAWN_CHANCE;
            serverLevelData.setWanderingTraderSpawnChance(this.spawnChance);
        }
    }

    @Override
    public int tick(ServerLevel level, boolean spawnEnemies, boolean spawnFriendlies) {
        if (!Config.WANDERING_TRADER_SPAWNS_REPLACE_VANILLA.get()) {
            return this.vanillaDelegate.tick(level, spawnEnemies, spawnFriendlies);
        } else if (!level.getGameRules().getBoolean(GameRules.RULE_DO_TRADER_SPAWNING)) {
            return 0;
        } else if (--this.tickDelay > 0) {
            return 0;
        } else {
            this.tickDelay = TICK_DELAY;
            this.spawnDelay -= TICK_DELAY;
            this.serverLevelData.setWanderingTraderSpawnDelay(this.spawnDelay);
            if (this.spawnDelay > 0) {
                return 0;
            } else {
                this.spawnDelay = SPAWN_DELAY;
                if (!level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
                    return 0;
                } else {
                    int i = this.spawnChance;
                    this.spawnChance = Mth.clamp(this.spawnChance + SPAWN_CHANCE_INCREASE, MIN_SPAWN_CHANCE, MAX_SPAWN_CHANCE);
                    this.serverLevelData.setWanderingTraderSpawnChance(this.spawnChance);
                    if (this.random.nextInt(100) > i) {
                        return 0;
                    } else if (this.spawn(level)) {
                        this.spawnChance = MIN_SPAWN_CHANCE;
                        return 1;
                    } else {
                        return 0;
                    }
                }
            }
        }
    }

    /**
     * One caravan for a random loaded base, or nothing. Like vanilla's
     * no-player path, no eligible base counts as a success so the spawn
     * chance resets instead of climbing while no base is loaded.
     */
    private boolean spawn(ServerLevel level) {
        BlockPos flagPos = pickRandomLoadedFlag(level);
        if (flagPos == null) {
            return true;
        } else if (this.random.nextInt(SPAWN_ONE_IN_X_CHANCE) != 0) {
            return false;
        } else {
            int baseLevel = level.getBlockEntity(flagPos) instanceof BaseFlagBlockEntity flagEntity
                    ? flagEntity.getBaseLevel()
                    : 1;
            int protectionRadius = BaseFlagBlock.protectionRadius(level, flagPos);

            // The caravan's wander target: a meeting point inside the base if
            // it has one, otherwise the flag itself (the base's center)
            PoiManager poiManager = level.getPoiManager();
            BlockPos target = poiManager.find(
                    holder -> holder.is(PoiTypes.MEETING), pos -> true,
                    flagPos, protectionRadius, PoiManager.Occupancy.ANY)
                    .orElse(flagPos);

            BlockPos spawn = this.findSpawnPositionOnRing(level, flagPos, protectionRadius);
            if (spawn == null || !this.hasEnoughSpace(level, spawn)) {
                return false;
            }
            if (level.getBiome(spawn).is(BiomeTags.WITHOUT_WANDERING_TRADER_SPAWNS)) {
                return false;
            }

            int traderCount = Mth.clamp(baseLevel, 1, BaseFlagBlockEntity.MAX_BASE_LEVEL);
            List<WanderingTrader> caravan = new ArrayList<>();
            for (int i = 0; i < traderCount; i++) {
                // The first trader takes the ring spot; the rest fan out beside it
                BlockPos memberPos = i == 0
                        ? spawn
                        : findSpawnPositionNear(level, spawn, CARAVAN_SPAWN_RADIUS, CARAVAN_SPAWN_ATTEMPTS);
                WanderingTrader trader = EntityType.WANDERING_TRADER.spawn(
                        level, memberPos != null ? memberPos : spawn, MobSpawnType.EVENT);
                if (trader != null) {
                    trader.setDespawnDelay(DESPAWN_DELAY_TICKS);
                    trader.setWanderTarget(target);
                    trader.restrictTo(target, RESTRICT_RADIUS);
                    caravan.add(trader);
                }
            }
            if (caravan.isEmpty()) {
                return false;
            }
            // Each llama leashes to a random trader in the caravan
            for (int i = 0; i < llamasFor(caravan.size()); i++) {
                this.tryToSpawnLlamaFor(level, caravan.get(this.random.nextInt(caravan.size())), CARAVAN_SPAWN_RADIUS);
            }
            this.serverLevelData.setWanderingTraderId(caravan.get(0).getUUID());
            return true;
        }
    }

    /**
     * Llamas for a caravan of {@code traders}: scales at three per five, so a
     * lone trader still leads a single llama and a level-5 caravan leads three.
     */
    private static int llamasFor(int traders) {
        return Math.max(1, Mth.ceil(traders * 3.0 / 5.0));
    }

    /**
     * A random flag whose chunk is loaded in this level. Flags in unloaded
     * chunks can't host a caravan (they'd spawn in dead air), so they aren't
     * candidates.
     */
    @Nullable
    private BlockPos pickRandomLoadedFlag(ServerLevel level) {
        List<BlockPos> loaded = new ArrayList<>();
        for (BlockPos flagPos : FlagsSavedData.getFromLevel(level).getFlagLocations()) {
            if (level.isLoaded(flagPos)) {
                loaded.add(flagPos);
            }
        }
        return loaded.isEmpty() ? null : loaded.get(this.random.nextInt(loaded.size()));
    }

    /**
     * A surface position on a random bearing {@link #MIN_SPAWN_BUFFER} to
     * {@link #MAX_SPAWN_BUFFER} blocks outside the flag's protection radius,
     * or null after {@link #SPAWN_POSITION_ATTEMPTS} tries. The distance
     * check keeps the ring honest when the rounded bearing lands inside the
     * sphere.
     */
    @Nullable
    private BlockPos findSpawnPositionOnRing(ServerLevel level, BlockPos flagPos, int protectionRadius) {
        SpawnPlacementType placementType = SpawnPlacements.getPlacementType(EntityType.WANDERING_TRADER);
        for (int i = 0; i < SPAWN_POSITION_ATTEMPTS; i++) {
            double angle = this.random.nextDouble() * Math.PI * 2;
            int distance = protectionRadius + MIN_SPAWN_BUFFER
                    + this.random.nextInt(MAX_SPAWN_BUFFER - MIN_SPAWN_BUFFER + 1);
            int x = flagPos.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = flagPos.getZ() + (int) Math.round(Math.sin(angle) * distance);
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (candidate.distSqr(flagPos) > (double) protectionRadius * protectionRadius
                    && placementType.isSpawnPositionOk(level, candidate, EntityType.WANDERING_TRADER)) {
                return candidate;
            }
        }
        return null;
    }

    private void tryToSpawnLlamaFor(ServerLevel level, WanderingTrader trader, int maxDistance) {
        BlockPos blockpos = findSpawnPositionNear(level, trader.blockPosition(), maxDistance, CARAVAN_SPAWN_ATTEMPTS);
        if (blockpos != null) {
            TraderLlama llama = EntityType.TRADER_LLAMA.spawn(level, blockpos, MobSpawnType.EVENT);
            if (llama != null) {
                llama.setLeashedTo(trader, true);
            }
        }
    }

    /**
     * A surface position within {@code maxDistance} of {@code pos}, per
     * vanilla's square-offset probing; null after {@code attempts} tries.
     */
    @Nullable
    private BlockPos findSpawnPositionNear(LevelReader level, BlockPos pos, int maxDistance, int attempts) {
        SpawnPlacementType placementType = SpawnPlacements.getPlacementType(EntityType.WANDERING_TRADER);
        for (int i = 0; i < attempts; i++) {
            int x = pos.getX() + this.random.nextInt(maxDistance * 2) - maxDistance;
            int z = pos.getZ() + this.random.nextInt(maxDistance * 2) - maxDistance;
            int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (placementType.isSpawnPositionOk(level, candidate, EntityType.WANDERING_TRADER)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean hasEnoughSpace(BlockGetter level, BlockPos pos) {
        for (BlockPos blockpos : BlockPos.betweenClosed(pos, pos.offset(1, 2, 1))) {
            if (!level.getBlockState(blockpos).getCollisionShape(level, blockpos).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
