package dev.abelinsenmayer.basebuster.pathfinding;

import dev.abelinsenmayer.basebuster.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * One-off, long-range path searches that produce a squad's shared rough route
 * to a flag. Unlike the per-tick searches {@code PathNavigation} runs, these
 * are guarded rather than budgeted by follow range:
 *
 * <ul>
 *   <li><b>Distance band</b>: skipped when the flag is close enough for normal
 *   navigation ({@link #MIN_SEARCH_DISTANCE}) or beyond
 *   {@link Config#RAIDER_SQUAD_PATHING_MAX_DISTANCE}, where a search could
 *   never be bounded.
 *   <li><b>Loaded-chunk gate</b>: {@link #routeLoaded} requires every chunk in
 *   the search region to already be loaded -- {@link PathNavigationRegion}
 *   reads real chunks, so a distant flag would otherwise synchronously
 *   generate a corridor of terrain on the server thread.
 *   <li><b>Wall-clock limit</b>: {@link #SEARCH_TIME_LIMIT} caps total CPU
 *   across all of a search's per-tick slices, bounding worst-case cost
 *   regardless of how many expansions that buys.
 *   <li><b>Coarse evaluator</b>: {@link RaiderNodeEvaluator#coarse} bounds the
 *   search space itself -- tunnel hardness and aerial distance above support
 *   -- so thick fortifications and open sky can't flood the frontier. A
 *   mostly-walk corridor is fine since followers re-path each leg with the
 *   full evaluator anyway.
 * </ul>
 *
 * <p>A search that hits its time limit still returns the best-approach path,
 * which decimates into a useful partial corridor -- graceful degradation
 * rather than failure.
 */
public final class SquadPathSearch {
    // Padding around the start/target bounding box for the search region
    private static final int REGION_PADDING = 16;
    // Cap on path length and distance from the start, generous for detours
    private static final float MAX_RANGE_MULTIPLIER = 100.0F;
    // Every Nth path node becomes a waypoint handed to squad members
    private static final int WAYPOINT_STRIDE = 1;
    // Below this distance normal navigation reaches the flag on its own
    private static final double MIN_SEARCH_DISTANCE = 32.0;
    // Wall-clock cap on one coarse search, shared by squad routes and raid
    // spawn searches; generous since {@link PathSearchScheduler} amortizes it
    public static final Duration SEARCH_TIME_LIMIT = Duration.ofSeconds(30);
    private static final int TARGET_ACCURACY = 2;
    private static final float SEARCH_DEPTH_MULTIPLIER = 1.0F;

    private SquadPathSearch() {
    }

    /**
     * Whether the flag sits in the band where a squad search is worthwhile:
     * far enough that partial-path chaining produces detours, close enough
     * that a single search stays bounded.
     */
    public static boolean withinSearchRange(Mob mob, BlockPos flag) {
        double maxDistance = Config.RAIDER_SQUAD_PATHING_MAX_DISTANCE.get();
        double distSqr = mob.blockPosition().distSqr(flag);
        return distSqr > MIN_SEARCH_DISTANCE * MIN_SEARCH_DISTANCE
                && distSqr <= maxDistance * maxDistance;
    }

    /**
     * Starts a bounded A* from the mob to the flag, stepped across ticks by
     * {@link PathSearchScheduler} rather than run to completion inline. Null
     * when a guardrail rejects the search (out of range, unloaded chunks on
     * the route); a search that hits its time limit still yields a
     * best-approach partial path via {@link ResumablePathSearch#result}.
     */
    @Nullable
    public static ResumablePathSearch beginSearchToFlag(Mob mob, BlockPos flag) {
        if (!(mob.level() instanceof ServerLevel level)
                || !withinSearchRange(mob, flag)
                || !routeLoaded(level, mob.blockPosition(), flag)) {
            return null;
        }

        int distance = mob.blockPosition().distManhattan(flag);
        ResumablePathSearch search = new ResumablePathSearch(coarseEvaluator(mob), SEARCH_TIME_LIMIT);
        search.begin(regionAround(level, mob.blockPosition(), flag), mob, Set.of(flag),
                (float) distance * MAX_RANGE_MULTIPLIER, TARGET_ACCURACY, SEARCH_DEPTH_MULTIPLIER);
        return search;
    }

    /**
     * The shared evaluator setup for coarse searches (squad routes, raid spawn
     * anchors): door-capable, with tunneling and bounded aerial emissions.
     */
    public static RaiderNodeEvaluator coarseEvaluator(Mob mob) {
        RaiderNodeEvaluator evaluator = RaiderNodeEvaluator.coarse(mob);
        evaluator.setCanPassDoors(true);
        evaluator.setCanOpenDoors(true);
        return evaluator;
    }

    /**
     * Whether every chunk a search region spanning a..b would touch is already
     * loaded.
     */
    public static boolean routeLoaded(ServerLevel level, BlockPos a, BlockPos b) {
        int minChunkX = (Math.min(a.getX(), b.getX()) - REGION_PADDING) >> 4;
        int minChunkZ = (Math.min(a.getZ(), b.getZ()) - REGION_PADDING) >> 4;
        int maxChunkX = (Math.max(a.getX(), b.getX()) + REGION_PADDING) >> 4;
        int maxChunkZ = (Math.max(a.getZ(), b.getZ()) + REGION_PADDING) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The region a search between a and b may read: their bounding box plus
     * padding, full build height.
     */
    public static PathNavigationRegion regionAround(ServerLevel level, BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX()) - REGION_PADDING;
        int minZ = Math.min(a.getZ(), b.getZ()) - REGION_PADDING;
        int maxX = Math.max(a.getX(), b.getX()) + REGION_PADDING;
        int maxZ = Math.max(a.getZ(), b.getZ()) + REGION_PADDING;
        return new PathNavigationRegion(
                level,
                new BlockPos(minX, level.getMinBuildHeight(), minZ),
                new BlockPos(maxX, level.getMaxBuildHeight() - 1, maxZ));
    }

    /**
     * Every {@link #WAYPOINT_STRIDE}th node of a path plus its far end, in
     * path order unless {@code reverse} is set (for searches that ran
     * flag-to-squad rather than squad-to-flag). Sparse by design: followers
     * re-path each leg locally, so the list is a corridor, not a rail.
     */
    public static List<BlockPos> decimate(Path path, boolean reverse) {
        List<BlockPos> waypoints = new ArrayList<>();
        int count = path.getNodeCount();
        for (int i = 0; i < count; i += WAYPOINT_STRIDE) {
            waypoints.add(nodePos(path.getNode(i)));
        }
        if (count > 0) {
            BlockPos end = nodePos(path.getNode(count - 1));
            if (waypoints.isEmpty() || !waypoints.get(waypoints.size() - 1).equals(end)) {
                waypoints.add(end);
            }
        }
        if (reverse) {
            Collections.reverse(waypoints);
        }
        return waypoints;
    }

    /** The waypoint nearest pos -- where a follower joins the corridor. */
    public static int closestIndex(List<BlockPos> waypoints, BlockPos pos) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < waypoints.size(); i++) {
            double dist = waypoints.get(i).distSqr(pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    private static BlockPos nodePos(Node node) {
        return new BlockPos(node.x, node.y, node.z);
    }
}
