package dev.abelinsenmayer.basebuster.pathfinding;

import dev.abelinsenmayer.basebuster.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.util.HashSet;
import java.util.Set;

/**
 * Extends the vanilla ground-walk evaluator with "tunnel" nodes: positions the mob
 * cannot currently occupy but could clear by breaking blocks. Each tunnel node
 * carries a large {@link Node#costMalus} scaled by the hardness of the obstructing
 * column, so A* treats digging as a costly but legal move and prefers real detours
 * when they are cheaper.
 *
 * <p>Because PathFinder adds {@code costMalus} to the path's g-score for every node
 * in the route, a wall N blocks thick costs roughly N * (base + hardness * factor)
 * in path terms -- long underground tunnels are priced like long walks.
 */
public class TunnelingNodeEvaluator extends WalkNodeEvaluator {
    // Cost of tunneling through one node position: a base effort plus a
    // per-hardness surcharge so harder materials deter the pathfinder more
    private static final float TUNNEL_MALUS_BASE = 8.0F;
    private static final float TUNNEL_MALUS_PER_HARDNESS = 4.0F;

    // Positions already proposed as tunnel nodes during this search, so a cell is
    // never emitted twice (each repeat would cost a separate node expansion)
    private final Set<Long> tunnelEmissions = new HashSet<>();

    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        this.tunnelEmissions.clear();
    }

    @Override
    public int getNeighbors(Node[] outputArray, Node node) {
        int count = super.getNeighbors(outputArray, node);
        if (!Config.RAIDER_TUNNELING_ENABLED.get()) {
            return count;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            // PathFinder allocates a fixed 32-slot neighbor array
            if (count >= outputArray.length) {
                break;
            }

            int x = node.x + direction.getStepX();
            int y = node.y;
            int z = node.z + direction.getStepZ();
            if (this.alreadyEmitted(outputArray, count, x, y, z)
                    || !this.tunnelEmissions.add(BlockPos.asLong(x, y, z))) {
                continue;
            }

            float malus = this.tunnelMalus(x, y, z);
            if (malus < 0.0F) {
                continue;
            }

            Node tunnelNode = this.getNode(x, y, z);
            if (tunnelNode.closed) {
                // A closed node either failed vanilla's reachability checks or was
                // already expanded this search. Re-emitting the same object lets
                // PathFinder overwrite its cameFrom (it skips the cost check for
                // nodes outside the open set), which can close a cycle in the
                // cameFrom chain and hang reconstructPath forever. Emit a fresh
                // instance instead: its cameFrom is set at most once and nothing
                // else references it, so the chain stays acyclic.
                tunnelNode = new Node(x, y, z);
            }
            tunnelNode.type = PathType.WALKABLE;
            tunnelNode.costMalus = malus;
            outputArray[count++] = tunnelNode;
        }

        return count;
    }

    private boolean alreadyEmitted(Node[] nodes, int count, int x, int y, int z) {
        for (int i = 0; i < count; i++) {
            Node node = nodes[i];
            if (node != null && node.x == x && node.y == y && node.z == z) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the tunnel cost for the mob-height column at (x, y, z), or -1 if the
     * column is not obstructed or contains an unbreakable obstruction.
     */
    private float tunnelMalus(int x, int y, int z) {
        float hardness = 0.0F;
        for (int dy = 0; dy < this.entityHeight; dy++) {
            BlockPos pos = new BlockPos(x, y + dy, z);
            BlockState state = this.currentContext.getBlockState(pos);
            float destroySpeed = state.getDestroySpeed(this.currentContext.level(), pos);
            if (destroySpeed < 0.0F) {
                // Bedrock and friends: no tunnel through this cell
                return -1.0F;
            }
            if (!state.getCollisionShape(this.currentContext.level(), pos).isEmpty()) {
                hardness += destroySpeed;
            }
        }

        if (hardness <= 0.0F) {
            // Nothing solid in the way, so there is nothing to tunnel through
            return -1.0F;
        }

        return TUNNEL_MALUS_BASE + hardness * TUNNEL_MALUS_PER_HARDNESS;
    }
}
