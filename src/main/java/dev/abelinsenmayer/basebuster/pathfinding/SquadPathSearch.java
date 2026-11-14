package dev.abelinsenmayer.basebuster.pathfinding;

import dev.abelinsenmayer.basebuster.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;

import javax.annotation.Nullable;
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
 *   <li><b>Scaled node budget</b>: expansions grow with distance and clamp at
 *   {@link Config#RAIDER_SQUAD_PATHING_MAX_NODES}, bounding worst-case cost.
 *   <li><b>Coarse evaluator</b>: {@link RaiderNodeEvaluator#coarse} bounds the
 *   search space itself -- tunnel hardness and aerial distance above support
 *   -- so thick fortifications and open sky can't flood the frontier. A
 *   mostly-walk corridor is fine since followers re-path each leg with the
 *   full evaluator anyway.
 * </ul>
 *
 * <p>A budget-exhausted search still returns the best-approach path, which
 * decimates into a useful partial corridor -- graceful degradation rather
 * than failure.
 */
public final class SquadPathSearch {
    // Padding around the start/target bounding box for the search region
    private static final int REGION_PADDING = 16;
    // Cap on path length and distance from the start, generous for detours
    private static final float MAX_RANGE_MULTIPLIER = 4.0F;
    // Every Nth path node becomes a waypoint handed to squad members
    private static final int WAYPOINT_STRIDE = 10;
    // Below this distance normal navigation reaches the flag on its own
    private static final double MIN_SEARCH_DISTANCE = 32.0;
    // Node expansions budgeted per block of manhattan distance to the flag
    private static final int NODES_PER_BLOCK = 128;
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
     * Runs one bounded A* from the mob to the flag, or null when a guardrail
     * rejects the search (out of range, unloaded chunks on the route) or the
     * budget leaves no path at all.
     */
    @Nullable
    public static Path searchToFlag(Mob mob, BlockPos flag) {
        if (!(mob.level() instanceof ServerLevel level)
                || !withinSearchRange(mob, flag)
                || !routeLoaded(level, mob.blockPosition(), flag)) {
            return null;
        }

        int distance = mob.blockPosition().distManhattan(flag);
        RaiderNodeEvaluator evaluator = RaiderNodeEvaluator.coarse(mob);
        evaluator.setCanPassDoors(true);
        evaluator.setCanOpenDoors(true);
        PathFinder finder = new PathFinder(evaluator, nodeBudget(distance));
        PathNavigationRegion region = regionAround(level, mob.blockPosition(), flag);
        return finder.findPath(region, mob, Set.of(flag),
                (float) distance * MAX_RANGE_MULTIPLIER, TARGET_ACCURACY, SEARCH_DEPTH_MULTIPLIER);
    }

    /**
     * The node-expansion budget for one bounded long-range search over
     * {@code distance} blocks: expansions scale with distance and clamp at
     * {@link Config#RAIDER_SQUAD_PATHING_MAX_NODES}. Shared by squad route
     * planning and the raid spawn searches so every coarse search buys the
     * same reach per block.
     */
    public static int nodeBudget(int distance) {
        return (int) Math.min(
                (long) distance * NODES_PER_BLOCK,
                Config.RAIDER_SQUAD_PATHING_MAX_NODES.get());
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
