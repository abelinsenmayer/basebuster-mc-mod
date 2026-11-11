package dev.abelinsenmayer.basebuster.raids;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderBossEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderKit;
import dev.abelinsenmayer.basebuster.entities.RaiderSkinTheme;
import dev.abelinsenmayer.basebuster.pathfinding.RaiderNodeEvaluator;
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
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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
 *   <li>Run one {@link PathFinder} search from the flag with every candidate as
 *   a potential target, using {@link RaiderNodeEvaluator} so tunneling,
 *   bridging, and pillar moves are priced into the route.
 *   <li>A* reaches the cheapest reachable candidate first (or reports the
 *   best-approached candidate on a partial path), which becomes the first
 *   group's spawn spot and approximates "the nearest area a raider could
 *   actually get in from."
 *   <li>Raids with multiple bosses get one spot per boss: the extras take
 *   the candidates nearest evenly spaced angles around the flag, so groups
 *   spread radially when the ring allows it. When terrain leaves too few
 *   candidates, spots repeat rather than shrinking the raid.
 * </ol>
 *
 * <p>The search is synchronous and bounded by {@link #PATH_NODE_BUDGET}, so it
 * runs once per raid trigger rather than per tick.
 */
public final class RaidSpawner {
    // Randomized buffer added to the protection radius, per the raid spec
    private static final int MIN_SPAWN_BUFFER = 1;
    private static final int MAX_SPAWN_BUFFER = 40;
    // Direction samples around the ring; more = better coverage of "nearest area"
    private static final int RING_CANDIDATES = 16;
    // Node expansion budget for the spawn-point search
    private static final int PATH_NODE_BUDGET = 8192;
    // Manhattan distance from a candidate at which the path counts as reached
    private static final int TARGET_ACCURACY = 3;
    // Raid composition per flag base level:
    // RAID_COMPOSITION[level - 1] = {bosses, bowmen, sword-and-board, tanks}.
    // Levels past the table clamp to the last row.
    private static final int[][] RAID_COMPOSITION = {
            {1, 4, 6, 0},    // Level 1
            {2, 6, 8, 2},    // Level 2
            {3, 12, 14, 6},  // Level 3
            {4, 26, 20, 8},  // Level 4
            {5, 26, 30, 10}, // Level 5
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
     * Attempts to start a raid on the flag at {@code flagPos}.
     *
     * @return whether the raid was spawned
     */
    public static boolean startRaid(ServerLevel level, BlockPos flagPos) {
        RandomSource random = level.getRandom();
        int raidTier = raidTier(level, flagPos);
        int[] composition = RAID_COMPOSITION[raidTier - 1];
        int partySize = Arrays.stream(composition).sum();
        // Large parties scatter wider so the anchor area can hold them all
        int spreadRadius = Math.max(MEMBER_SPREAD_RADIUS, (int) Math.ceil(Math.sqrt(partySize)));

        // Probe with the boss: it's the largest member, so routes and spawn
        // spots that fit the boss fit everyone
        RaiderBossEntity boss = BaseBuster.RAIDER_BOSS.get().create(level);
        if (boss == null) {
            return false;
        }
        boss.moveTo(flagPos.getX() + 0.5, flagPos.getY(), flagPos.getZ() + 0.5);
        // One theme per raid; every member draws a random skin from it
        RaiderSkinTheme theme = RaiderSkinTheme.random(random);
        boss.setSkin(theme.randomSkin(random));

        int distance = BaseFlagBlock.FLAG_PROTECTION_RADIUS + MIN_SPAWN_BUFFER + random.nextInt(MAX_SPAWN_BUFFER);
        List<BlockPos> anchors = surfaceAnchors(level, flagPos, distance, boss, random);
        if (anchors.isEmpty()) {
            return false;
        }

        // One id per raid, shared by every boss it spawns, so a dying boss can
        // tell when it's the last one standing
        UUID raidId = UUID.randomUUID();
        boss.setRaidId(raidId);
        boss.setRaidTier(raidTier);

        // One spawn spot per boss, spread radially around the flag when the
        // candidate terrain allows it
        List<BlockPos> spots = pickAnchors(level, flagPos, anchors, boss, distance, composition[0]);
        spawnMember(level, boss, spots.get(0));

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
            extra.setSkin(theme.randomSkin(random));
            spawnMember(level, extra, findSafeSpawnNear(level, extra, spots.get(i), spreadRadius, random));
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
                raider.setSkin(theme.randomSkin(random));
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
        return true;
    }

    /**
     * The raid tier for the flag's current base level, which indexes
     * {@link #RAID_COMPOSITION} and decides the dropped banner's tier. Flags
     * without a {@link BaseFlagBlockEntity} get a level-1 raid; levels beyond
     * the table clamp to the last row.
     */
    private static int raidTier(ServerLevel level, BlockPos flagPos) {
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
     * Picks the candidate that the raider path evaluator can reach from the flag
     * for the least cost. A single multi-target A* yields the cheapest reached
     * anchor; when the budget runs out first, the partial path still points at
     * the best-approached candidate. Falls back to a random anchor if the search
     * finds nothing at all.
     */
    private static BlockPos pickAnchor(ServerLevel level, BlockPos flagPos, List<BlockPos> anchors, Mob probe, int distance) {
        RaiderNodeEvaluator evaluator = new RaiderNodeEvaluator(probe);
        evaluator.setCanPassDoors(true);
        evaluator.setCanOpenDoors(true);
        PathFinder finder = new PathFinder(evaluator, PATH_NODE_BUDGET);

        // PathNavigationRegion expects the low corner first (it sizes its chunk
        // array by subtraction), so build explicit min/max corners
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : anchors) {
            minX = Math.min(minX, pos.getX());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        minX = Math.min(minX, flagPos.getX()) - REGION_PADDING;
        minZ = Math.min(minZ, flagPos.getZ()) - REGION_PADDING;
        maxX = Math.max(maxX, flagPos.getX()) + REGION_PADDING;
        maxZ = Math.max(maxZ, flagPos.getZ()) + REGION_PADDING;
        PathNavigationRegion region = new PathNavigationRegion(
                level,
                new BlockPos(minX, level.getMinBuildHeight(), minZ),
                new BlockPos(maxX, level.getMaxBuildHeight() - 1, maxZ));

        Path path = finder.findPath(region, probe, Set.copyOf(anchors),
                (float) distance * MAX_RANGE_MULTIPLIER, TARGET_ACCURACY, 1.0F);
        if (path != null) {
            // Reached anchor, or the best-approached one on a partial path
            return path.getTarget();
        }
        return anchors.get(level.getRandom().nextInt(anchors.size()));
    }

    /**
     * The {@code count} spawn spots for the raid's groups — one per boss. The
     * first is {@link #pickAnchor}'s cheapest reachable candidate; the rest
     * take the candidates nearest evenly spaced angles around the flag, so
     * groups spread radially. Each candidate hosts at most one group until
     * they run out; after that, spots repeat (guaranteeing {@code count}
     * spots) so a cramped ring still yields a full raid.
     */
    private static List<BlockPos> pickAnchors(ServerLevel level, BlockPos flagPos, List<BlockPos> anchors, Mob probe, int distance, int count) {
        List<BlockPos> spots = new ArrayList<>();
        BlockPos primary = pickAnchor(level, flagPos, anchors, probe, distance);
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
