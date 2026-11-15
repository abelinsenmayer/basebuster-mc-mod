package dev.abelinsenmayer.basebuster.raids;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderBossEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderKit;
import dev.abelinsenmayer.basebuster.entities.RaiderSkin;
import dev.abelinsenmayer.basebuster.pathfinding.PathSearchScheduler;
import dev.abelinsenmayer.basebuster.pathfinding.ResumablePathSearch;
import dev.abelinsenmayer.basebuster.pathfinding.SquadPathSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Spawns a raiding party ({@link RaiderBossEntity} bosses plus a mix of
 * {@link RaiderEntity} kits) just outside a flag's protection radius, biased
 * toward the cheapest approach a raider could take to the flag. Party size and
 * makeup scale with the flag's base level; see {@link #RAID_COMPOSITION}.
 *
 * <p>Because the spawn ring sits well beyond a single pathing search's reach,
 * this uses an approximate, single-search strategy:
 *
 * <ol>
 *   <li>Sample {@link #RING_CANDIDATES} evenly spaced points on a ring at
 *   {@link BaseFlagBlock#FLAG_PROTECTION_RADIUS} + a random 1-40 block buffer,
 *   snapping each to a safe surface position.
 *   <li>Run one {@link ResumablePathSearch} from the flag with every candidate
 *   as a potential target, using {@link RaiderNodeEvaluator} so tunneling,
 *   bridging, and pillar moves are priced into the route.
 *   <li>A* reaches the cheapest reachable candidate first (or reports the
 *   best-approached candidate on a partial path), which becomes the first
 *   group's spawn spot and approximates "the nearest area a raider could
 *   actually get in from."
 *   <li>Raids with multiple bosses get one spot per boss: the extras take
 *   the candidates nearest evenly spaced angles around the flag, so groups
 *   spread radially when the ring allows it. When terrain leaves too few
 *   candidates — or when {@code raidSpawning.singleSpawnPoint} is set —
 *   spots repeat rather than shrinking the raid.
 *   <li>Every group's spot gets its own flag-to-spot search; each resulting
 *   path is decimated into waypoints and stored on that group's boss as the
 *   squad's shared rough route (see {@link SquadPathSearch}).
 * </ol>
 *
 * <p>Both searches run as {@link ResumablePathSearch}es stepped across ticks
 * by {@link PathSearchScheduler} (see {@link PendingRaid}), so the party
 * materializes some ticks after the trigger rather than freezing one tick on
 * a very large search. Each search is bounded by
 * {@link SquadPathSearch#SEARCH_TIME_LIMIT}, the same wall-clock cap squad
 * route planning uses.
 */
public final class RaidSpawner {
    // Randomized buffer added to the protection radius, per the raid spec
    private static final int MIN_SPAWN_BUFFER = 1;
    private static final int MAX_SPAWN_BUFFER = 40;
    // Direction samples around the ring; more = better coverage of "nearest area"
    private static final int RING_CANDIDATES = 16;
    // Manhattan distance from a candidate at which the path counts as reached
    private static final int TARGET_ACCURACY = 3;
    // Raid composition per flag base level:
    // RAID_COMPOSITION[level - 1] = {bosses, bowmen, sword-and-board, tanks}.
    // Levels past the table clamp to the last row.
    private static final int[][] RAID_COMPOSITION = {
            {1, 6, 9, 0},    // Level 1
            {2, 9, 12, 3},   // Level 2
            {3, 18, 21, 9},  // Level 3
            {4, 39, 30, 12}, // Level 4
            {5, 39, 45, 15}, // Level 5
    };
    // Kits assigned to the member columns of RAID_COMPOSITION, in order
    private static final RaiderKit[] MEMBER_KITS = {RaiderKit.BOWMAN, RaiderKit.SWORD_AND_BOARD, RaiderKit.TANK};
    // Minimum distance from the party anchor a member may appear; large
    // parties spread wider (see startRaid)
    private static final int MEMBER_SPREAD_RADIUS = 4;
    // Attempts to find a safe spot for each raider before falling back to the anchor
    private static final int MEMBER_SPAWN_ATTEMPTS = 16;
    // Vertical offsets around the heightmap position probed for a safe anchor
    private static final int[] SURFACE_Y_OFFSETS = {0, 1, -1, 2, -2};
    // Padding added around the flag/anchor bounding box for the path region
    private static final int REGION_PADDING = 16;
    // Cap on path length and distance from the start, generous for detours
    private static final int MAX_RANGE_MULTIPLIER = 4;

    private RaidSpawner() {
    }

    /**
     * Attempts to start a raid on the flag at {@code flagPos}, placing the
     * party at the cheapest reachable point on the outer spawn ring. The
     * party materializes once its path searches finish, some ticks later;
     * {@code onPartySpawned} runs then (and only then), so callers can
     * notify once the raid is real rather than merely scheduled.
     *
     * @return whether the raid was accepted and scheduled
     */
    public static boolean startRaid(ServerLevel level, BlockPos flagPos, Runnable onPartySpawned) {
        // Probe with the boss: it's the largest member, so routes and spawn
        // spots that fit the boss fit everyone
        RaiderBossEntity boss = newPartyProbe(level, flagPos);
        if (boss == null) {
            return false;
        }
        RandomSource random = level.getRandom();
        int distance = BaseFlagBlock.FLAG_PROTECTION_RADIUS + MIN_SPAWN_BUFFER + random.nextInt(MAX_SPAWN_BUFFER);
        List<BlockPos> anchors = surfaceAnchors(level, flagPos, distance, boss, random);
        if (anchors.isEmpty()) {
            return false;
        }
        int groupCount = Config.RAID_SINGLE_SPAWN_POINT.get()
                ? 1
                : RAID_COMPOSITION[raidTier(level, flagPos) - 1][0];
        return scheduleParty(level, flagPos, boss, anchors, distance, groupCount, onPartySpawned);
    }

    /**
     * Testing variant of {@link #startRaid}: spawns the party at
     * {@code spawnPos} (snapped a couple blocks for safety) rather than
     * picking a point on the spawn ring. A single anchor means every group
     * shares the clicked spot, matching the effect of
     * {@code raidSpawning.singleSpawnPoint}.
     *
     * @return whether the raid was accepted and scheduled
     */
    public static boolean startRaidAt(ServerLevel level, BlockPos flagPos, BlockPos spawnPos, Runnable onPartySpawned) {
        RaiderBossEntity boss = newPartyProbe(level, flagPos);
        if (boss == null) {
            return false;
        }
        BlockPos anchor = safeSpawnNear(level, spawnPos, boss);
        if (anchor == null) {
            return false;
        }
        int distance = Math.max(flagPos.distManhattan(anchor), 1);
        int groupCount = RAID_COMPOSITION[raidTier(level, flagPos) - 1][0];
        return scheduleParty(level, flagPos, boss, List.of(anchor), distance, groupCount, onPartySpawned);
    }

    /**
     * A boss stood at the flag, used to probe spawn safety and as the start
     * of every flag-to-spot search; the probe itself spawns as the first
     * group's boss.
     */
    @Nullable
    private static RaiderBossEntity newPartyProbe(ServerLevel level, BlockPos flagPos) {
        RaiderBossEntity probe = BaseBuster.RAIDER_BOSS.get().create(level);
        if (probe == null) {
            return null;
        }
        probe.moveTo(flagPos.getX() + 0.5, flagPos.getY(), flagPos.getZ() + 0.5);
        // Every spawned raider wears the generic zombie skin for now; restore
        // a per-raid RaiderSkinTheme draw when varied skins come back
        probe.setSkin(RaiderSkin.ZOMBIE);
        return probe;
    }

    /**
     * The nearest safe spot to {@code pos} within {@link #SURFACE_Y_OFFSETS},
     * or null when none is. Unlike {@link #surfaceAnchors} this stays at the
     * given column rather than snapping to the heightmap, so clicked-point
     * raids land where the click happened even underground.
     */
    @Nullable
    private static BlockPos safeSpawnNear(ServerLevel level, BlockPos pos, Mob probe) {
        for (int dy : SURFACE_Y_OFFSETS) {
            BlockPos feet = pos.above(dy);
            if (isSafeSpawn(level, probe, feet)) {
                return feet.immutable();
            }
        }
        return null;
    }

    /**
     * Queues the searches a party needs and returns immediately; the party
     * itself spawns when they finish (see {@link PendingRaid}).
     */
    private static boolean scheduleParty(ServerLevel level, BlockPos flagPos, RaiderBossEntity probe, List<BlockPos> anchors, int distance, int groupCount, Runnable onPartySpawned) {
        new PendingRaid(level, flagPos, probe, distance, groupCount, raidTier(level, flagPos), onPartySpawned).start(anchors);
        return true;
    }

    /**
     * A raid in flight between the trigger and the party's appearance. Stage 1
     * is the multi-target anchor search ({@link #start}); its
     * result decides the group spots. Stage 2 is one flag-to-spot search per
     * unique spot, whose paths arrive over later ticks; {@link #finish} runs
     * once the last one lands. All state lives here so the stages can be
     * separated by arbitrary scheduler ticks.
     */
    private static final class PendingRaid {
        private final ServerLevel level;
        private final BlockPos flagPos;
        private final RaiderBossEntity probe;
        private final int distance;
        private final int groupCount;
        private final int raidTier;
        private final Runnable onPartySpawned;
        private List<BlockPos> spots = List.of();
        private List<Path> paths = List.of();
        private int pendingSearches;

        private PendingRaid(ServerLevel level, BlockPos flagPos, RaiderBossEntity probe, int distance, int groupCount, int raidTier, Runnable onPartySpawned) {
            this.level = level;
            this.flagPos = flagPos;
            this.probe = probe;
            this.distance = distance;
            this.groupCount = groupCount;
            this.raidTier = raidTier;
            this.onPartySpawned = onPartySpawned;
        }

        /**
         * Stage 1: the multi-target flag-to-anchors search that picks the
         * primary spot (and doubles as the first group's route).
         */
        void start(List<BlockPos> anchors) {
            ResumablePathSearch search = newCoarseSearch(this.probe);
            search.begin(flagRegion(this.level, this.flagPos, anchors), this.probe, Set.copyOf(anchors),
                    (float) this.distance * MAX_RANGE_MULTIPLIER, TARGET_ACCURACY, 1.0F);
            PathSearchScheduler.schedule(search, "raid anchors from flag " + this.flagPos, this.probe::isAlive,
                    path -> this.beginGroupSearches(anchors, path));
        }

        /**
         * Stage 2: spots from the anchor search's outcome, then one search per
         * unique spot. Repeated spots (single-point raids, cramped rings)
         * share a search by grouping their indices, so a finishing search
         * fills every group that uses the spot.
         */
        private void beginGroupSearches(List<BlockPos> anchors, @Nullable Path primaryPath) {
            this.spots = pickAnchors(this.level, this.flagPos, anchors, this.groupCount, primaryPath);
            List<Path> paths = new ArrayList<>(Collections.nCopies(this.spots.size(), (Path) null));
            this.paths = paths;
            if (!Config.RAIDER_SQUAD_PATHING_ENABLED.get()) {
                this.finish();
                return;
            }
            if (primaryPath != null) {
                paths.set(0, primaryPath);
            }
            Map<BlockPos, List<Integer>> indicesBySpot = new LinkedHashMap<>();
            for (int i = 0; i < this.spots.size(); i++) {
                indicesBySpot.computeIfAbsent(this.spots.get(i), spot -> new ArrayList<>()).add(i);
            }
            // One region shared by every group search, as before
            PathNavigationRegion region = flagRegion(this.level, this.flagPos, this.spots);
            for (Map.Entry<BlockPos, List<Integer>> entry : indicesBySpot.entrySet()) {
                List<Integer> indices = entry.getValue();
                Path existing = indices.stream().map(paths::get).filter(Objects::nonNull).findFirst().orElse(null);
                if (existing != null) {
                    indices.forEach(i -> paths.set(i, existing));
                    continue;
                }
                this.pendingSearches++;
                ResumablePathSearch search = newCoarseSearch(this.probe);
                search.begin(region, this.probe, Set.of(entry.getKey()),
                        (float) this.distance * MAX_RANGE_MULTIPLIER, TARGET_ACCURACY, 1.0F);
                PathSearchScheduler.schedule(search, "raid group " + indices + " to " + entry.getKey(), this.probe::isAlive,
                        path -> this.onGroupPathDone(indices, path));
            }
            if (this.pendingSearches == 0) {
                this.finish();
            }
        }

        private void onGroupPathDone(List<Integer> indices, @Nullable Path path) {
            for (int i : indices) {
                this.paths.set(i, path);
            }
            if (--this.pendingSearches == 0) {
                this.finish();
            }
        }

        private void finish() {
            spawnParty(this.level, this.flagPos, this.probe, this.spots, this.paths, this.raidTier);
            this.onPartySpawned.run();
        }
    }

    /**
     * Spawns the party itself once its searches have resolved: bosses take
     * their spots and routes, members scatter around the spots, and horns.
     */
    private static void spawnParty(ServerLevel level, BlockPos flagPos, RaiderBossEntity boss, List<BlockPos> spots, List<Path> groupPaths, int raidTier) {
        RandomSource random = level.getRandom();
        int[] composition = RAID_COMPOSITION[raidTier - 1];
        int partySize = Arrays.stream(composition).sum();
        // Large parties scatter wider so the anchor area can hold them all
        int spreadRadius = Math.max(MEMBER_SPREAD_RADIUS, (int) Math.ceil(Math.sqrt(partySize)));

        // One id per raid, shared by every boss it spawns, so a dying boss can
        // tell when it's the last one standing
        UUID raidId = UUID.randomUUID();
        boss.setRaidId(raidId);
        boss.setRaidTier(raidTier);

        spawnMember(level, boss, spots.get(0));
        assignSquadPath(boss, groupPaths.get(0), flagPos);

        List<RaiderBossEntity> bosses = new ArrayList<>();
        bosses.add(boss);
        // Each remaining boss leads its own group from its own spot. Their
        // kits stay unset so finalizeSpawn gives each a random one.
        for (int i = 1; i < composition[0]; i++) {
            RaiderBossEntity extra = BaseBuster.RAIDER_BOSS.get().create(level);
            if (extra == null) {
                continue;
            }
            extra.setRaidId(raidId);
            extra.setRaidTier(raidTier);
            extra.setSkin(RaiderSkin.ZOMBIE);
            spawnMember(level, extra, findSafeSpawnNear(level, extra, spots.get(i % spots.size()), spreadRadius, random));
            assignSquadPath(extra, groupPaths.get(i % groupPaths.size()), flagPos);
            bosses.add(extra);
        }

        int memberIndex = 0;
        for (int slot = 0; slot < MEMBER_KITS.length; slot++) {
            for (int i = 0; i < composition[slot + 1]; i++) {
                RaiderEntity raider = BaseBuster.RAIDER.get().create(level);
                if (raider == null) {
                    continue;
                }
                raider.setKit(MEMBER_KITS[slot]);
                // Tier must be set before finalizeSpawn runs the kit's equip
                raider.setRaidTier(raidTier);
                raider.setSkin(RaiderSkin.ZOMBIE);
                // Split members evenly across the group spots, then join
                // whichever boss actually ended up closest to where they spawned
                BlockPos spawn = findSafeSpawnNear(level, raider, spots.get(memberIndex % spots.size()), spreadRadius, random);
                spawnMember(level, raider, spawn);
                raider.setSquadLeader(nearestBoss(bosses, spawn));
                memberIndex++;
            }
        }

        // One horn per group, plus one at the besieged flag so the defender hears it too
        for (BlockPos spot : spots.stream().distinct().toList()) {
            level.playSound(null, spot, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 64.0F, 1.0F);
        }
        level.playSound(null, flagPos, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 64.0F, 1.0F);
        BaseBuster.LOGGER.info("Raid of {} in {} group(s) spawned at {} targeting flag at {}", partySize, spots.size(), spots, flagPos);
    }

    /**
     * The raid tier for the flag's current base level, which indexes
     * {@link #RAID_COMPOSITION} and decides the dropped banner's tier. Flags
     * without a {@link BaseFlagBlockEntity} get a level-1 raid; levels beyond
     * the table clamp to the last row.
     */
    public static int raidTier(ServerLevel level, BlockPos flagPos) {
        int baseLevel = 1;
        if (level.getBlockEntity(flagPos) instanceof BaseFlagBlockEntity flagEntity) {
            baseLevel = flagEntity.getBaseLevel();
        }
        return Math.clamp(baseLevel, 1, RAID_COMPOSITION.length);
    }

    /**
     * Safe surface positions on a ring of the given radius around the flag,
     * evenly spaced with a random starting angle. Candidates whose column can't
     * host the party are skipped.
     */
    private static List<BlockPos> surfaceAnchors(ServerLevel level, BlockPos flagPos, int distance, Mob probe, RandomSource random) {
        List<BlockPos> anchors = new ArrayList<>();
        double startAngle = random.nextDouble() * Math.PI * 2;
        for (int i = 0; i < RING_CANDIDATES; i++) {
            double angle = startAngle + i * (Math.PI * 2 / RING_CANDIDATES);
            int x = flagPos.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = flagPos.getZ() + (int) Math.round(Math.sin(angle) * distance);
            BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, flagPos.getY(), z));
            for (int dy : SURFACE_Y_OFFSETS) {
                BlockPos feet = surface.above(dy);
                if (isSafeSpawn(level, probe, feet)) {
                    anchors.add(feet.immutable());
                    break;
                }
            }
        }
        return anchors;
    }

    /**
     * A standalone resumable search from the flag, using the coarse evaluator
     * variant: spawn-time searches are long-range, so tunnel and aerial
     * emissions stay bounded rather than flooding the search in open sky or
     * fortifications.
     */
    private static ResumablePathSearch newCoarseSearch(Mob probe) {
        return new ResumablePathSearch(SquadPathSearch.coarseEvaluator(probe), SquadPathSearch.SEARCH_TIME_LIMIT);
    }

    /**
     * The search region covering the flag and every target plus padding.
     * PathNavigationRegion expects the low corner first (it sizes its chunk
     * array by subtraction), so build explicit min/max corners.
     */
    private static PathNavigationRegion flagRegion(ServerLevel level, BlockPos flagPos, Collection<BlockPos> targets) {
        int minX = flagPos.getX();
        int minZ = flagPos.getZ();
        int maxX = flagPos.getX();
        int maxZ = flagPos.getZ();
        for (BlockPos pos : targets) {
            minX = Math.min(minX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new PathNavigationRegion(
                level,
                new BlockPos(minX - REGION_PADDING, level.getMinBuildHeight(), minZ - REGION_PADDING),
                new BlockPos(maxX + REGION_PADDING, level.getMaxBuildHeight() - 1, maxZ + REGION_PADDING));
    }

    /**
     * Decimates a flag-to-spot search into waypoints and stores them as the
     * boss's squad route. The search reads flag-to-spot, so the list is
     * reversed into march order; null paths leave the squad on ordinary
     * partial pathing.
     */
    private static void assignSquadPath(RaiderBossEntity boss, @Nullable Path path, BlockPos flagPos) {
        if (path != null) {
            boss.setSquadPath(SquadPathSearch.decimate(path, true), flagPos);
        }
    }

    /**
     * The {@code count} spawn spots for the raid's groups — one per boss. The
     * first is the anchor the stage-1 multi-target search reached (or
     * best-approached), or a random one when it found nothing; the rest take
     * the candidates nearest evenly spaced angles around the flag, so groups
     * spread radially. Each candidate hosts at most one group until they run
     * out; after that, spots repeat (guaranteeing {@code count} spots) so a
     * cramped ring still yields a full raid. With
     * {@code raidSpawning.singleSpawnPoint} set, every group gets the primary
     * anchor instead.
     */
    private static List<BlockPos> pickAnchors(ServerLevel level, BlockPos flagPos, List<BlockPos> anchors, int count, @Nullable Path primaryPath) {
        List<BlockPos> spots = new ArrayList<>(count);
        // Reached anchor, or the best-approached one on a partial path
        BlockPos primary = primaryPath != null
                ? primaryPath.getTarget()
                : anchors.get(level.getRandom().nextInt(anchors.size()));

        // Single-point raids put every group on the optimal anchor
        if (Config.RAID_SINGLE_SPAWN_POINT.get()) {
            return Collections.nCopies(count, primary);
        }

        spots.add(primary);
        Set<BlockPos> used = new HashSet<>();
        used.add(primary);
        double startAngle = Math.atan2(primary.getZ() - flagPos.getZ(), primary.getX() - flagPos.getX());
        for (int i = 1; i < count; i++) {
            double desiredAngle = startAngle + i * (Math.PI * 2 / count);
            BlockPos nearest = nearestAnchorAtAngle(flagPos, anchors, used, desiredAngle);
            if (nearest == null) {
                // Every candidate already hosts a group; reuse the closest one
                nearest = nearestAnchorAtAngle(flagPos, anchors, Set.of(), desiredAngle);
            }
            spots.add(nearest);
            used.add(nearest);
        }
        return spots;
    }

    /**
     * The candidate in {@code anchors} but not in {@code excluded} whose angle
     * around the flag is closest to {@code angle}; null when every candidate
     * is excluded.
     */
    @Nullable
    private static BlockPos nearestAnchorAtAngle(BlockPos flagPos, List<BlockPos> anchors, Set<BlockPos> excluded, double angle) {
        BlockPos nearest = null;
        double bestDiff = Double.MAX_VALUE;
        for (BlockPos anchor : anchors) {
            if (excluded.contains(anchor)) {
                continue;
            }
            double anchorAngle = Math.atan2(anchor.getZ() - flagPos.getZ(), anchor.getX() - flagPos.getX());
            double diff = Math.abs(anchorAngle - angle) % (Math.PI * 2);
            if (diff > Math.PI) {
                diff = Math.PI * 2 - diff;
            }
            if (diff < bestDiff) {
                bestDiff = diff;
                nearest = anchor;
            }
        }
        return nearest;
    }

    /**
     * The boss closest to {@code pos}. Raiders join the group whose leader
     * they spawned nearest, which keeps squads local even when spots end up
     * clustered by the terrain.
     */
    private static RaiderBossEntity nearestBoss(List<RaiderBossEntity> bosses, BlockPos pos) {
        RaiderBossEntity nearest = bosses.get(0);
        double bestDist = nearest.blockPosition().distSqr(pos);
        for (int i = 1; i < bosses.size(); i++) {
            double dist = bosses.get(i).blockPosition().distSqr(pos);
            if (dist < bestDist) {
                bestDist = dist;
                nearest = bosses.get(i);
            }
        }
        return nearest;
    }

    /**
     * Whether {@code feet} is a safe spot for the mob to materialize: headroom,
     * real footing below, and no hazards (fire, lava, cactus, powder snow,
     * fluids) in or around its bounding box.
     */
    private static boolean isSafeSpawn(ServerLevel level, Mob probe, BlockPos feet) {
        PathType type = WalkNodeEvaluator.getPathTypeStatic(probe, feet);
        if (type.getMalus() < 0.0F) {
            return false;
        }
        switch (type) {
            case DANGER_FIRE, DANGER_OTHER, DANGER_POWDER_SNOW, DANGER_TRAPDOOR,
                 DAMAGE_FIRE, DAMAGE_OTHER, DAMAGE_CAUTIOUS,
                 LAVA, POWDER_SNOW, WATER, WATER_BORDER -> {
                return false;
            }
            default -> {
            }
        }

        BlockPos below = feet.below();
        BlockState ground = level.getBlockState(below);
        if (!ground.is(BlockTags.CLIMBABLE) && ground.getCollisionShape(level, below).isEmpty()) {
            return false;
        }

        AABB box = probe.getDimensions(Pose.STANDING).makeBoundingBox(Vec3.atBottomCenterOf(feet));
        return level.noCollision(probe, box);
    }

    /**
     * A safe position near the anchor for a party member, or the anchor itself
     * when nothing else pans out (the anchor is already boss-safe, and the boss
     * is bigger than any raider).
     */
    private static BlockPos findSafeSpawnNear(ServerLevel level, Mob probe, BlockPos anchor, int spreadRadius, RandomSource random) {
        for (int i = 0; i < MEMBER_SPAWN_ATTEMPTS; i++) {
            int dx = random.nextInt(spreadRadius * 2 + 1) - spreadRadius;
            int dz = random.nextInt(spreadRadius * 2 + 1) - spreadRadius;
            BlockPos feet = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, anchor.offset(dx, 0, dz));
            if (isSafeSpawn(level, probe, feet)) {
                return feet;
            }
        }
        return anchor;
    }

    private static void spawnMember(ServerLevel level, RaiderEntity member, BlockPos pos) {
        member.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5,
                level.getRandom().nextFloat() * 360.0F, 0.0F);
        member.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        // Raid members are scripted spawns, not ambient mobs; don't let them despawn
        member.setPersistenceRequired();
        level.addFreshEntity(member);
    }
}
