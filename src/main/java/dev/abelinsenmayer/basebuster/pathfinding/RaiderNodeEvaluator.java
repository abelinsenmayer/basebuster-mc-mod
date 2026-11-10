package dev.abelinsenmayer.basebuster.pathfinding;

import dev.abelinsenmayer.basebuster.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.util.HashSet;
import java.util.Set;

/**
 * Extends the vanilla ground-walk evaluator with three kinds of synthetic nodes,
 * each priced with {@link Node#costMalus} so A* prefers real routes but treats
 * them as legal moves when nothing cheaper exists:
 *
 * <ul>
 *   <li><b>Tunnel</b> nodes: positions the mob cannot occupy but could clear by
 *   breaking blocks. Malus scales with the hardness of the obstructing column, so
 *   digging through a wall costs roughly thickness * (base + hardness * factor).
 *   <li><b>Bridge</b> nodes: open air positions with no floor below them. The mob
 *   can get across by placing scaffolding under its feet. Priced above walking
 *   but far below tunneling.
 *   <li><b>Pillar</b> nodes: positions directly above the mob's own column. The
 *   mob places scaffolding in its cell and climbs it like a ladder.
 * </ul>
 *
 * <p>Because PathFinder adds {@code costMalus} to the path's g-score for every
 * node in the route, costs accumulate naturally: a long bridge or a tall pillar
 * is priced like the detour it replaces.
 */
public class RaiderNodeEvaluator extends WalkNodeEvaluator {
    // Cost of tunneling through one node position: a base effort plus a
    // per-hardness surcharge so harder materials deter the pathfinder more
    private static final float TUNNEL_MALUS_BASE = 8.0F;
    private static final float TUNNEL_MALUS_PER_HARDNESS = 4.0F;
    // Traversing/alongside existing scaffolding is cheap -- just slower footing
    private static final float SCAFFOLD_MALUS = 1.0F;
    // A walkway node over a gap that needs a scaffold placed below it
    private static final float BRIDGE_MALUS = 2.0F;
    // One level of a scaffold pillar the mob has to build and climb
    private static final float PILLAR_MALUS = 3.0F;

    // Positions already proposed as synthetic nodes during this search, so a
    // cell is never emitted twice (each repeat costs a separate node expansion)
    private final Set<Long> extraEmissions = new HashSet<>();

    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        this.extraEmissions.clear();
    }

    @Override
    public int getNeighbors(Node[] outputArray, Node node) {
        int count = super.getNeighbors(outputArray, node);
        if (!Config.RAIDER_TUNNELING_ENABLED.get()) {
            return count;
        }

        count = this.emitVerticalNeighbors(outputArray, count, node);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            // PathFinder allocates a fixed 32-slot neighbor array
            if (count >= outputArray.length) {
                break;
            }
            count = this.emitHorizontalNeighbor(outputArray, count, node, direction);
        }

        return count;
    }

    /**
     * Scaffold-climbing moves: down into an existing scaffold below, and up into
     * a scaffold (climb) or open air (place-and-climb pillar) above.
     */
    private int emitVerticalNeighbors(Node[] outputArray, int count, Node node) {
        int x = node.x;
        int y = node.y;
        int z = node.z;

        if (this.isScaffolding(x, y - 1, z)) {
            count = this.emitNode(outputArray, count, x, y - 1, z, SCAFFOLD_MALUS);
        }

        // Pillars can't start mid-fall; only grounded or scaffold-held nodes
        if (node.type == PathType.OPEN) {
            return count;
        }

        if (this.isScaffolding(x, y + 1, z)) {
            count = this.emitNode(outputArray, count, x, y + 1, z, SCAFFOLD_MALUS);
        } else if (this.isReplaceableForScaffold(x, y + 1, z) && this.isReplaceableForScaffold(x, y + 2, z)) {
            count = this.emitNode(outputArray, count, x, y + 1, z, PILLAR_MALUS);
        }
        return count;
    }

    /**
     * Horizontal moves vanilla rejected: scaffold cells (climbable), open air
     * over a gap (bridgeable), and obstructed columns (tunnelable).
     */
    private int emitHorizontalNeighbor(Node[] outputArray, int count, Node node, Direction direction) {
        int x = node.x + direction.getStepX();
        int y = node.y;
        int z = node.z + direction.getStepZ();
        if (this.alreadyEmitted(outputArray, count, x, y, z)) {
            return count;
        }

        if (this.isScaffolding(x, y, z)) {
            return this.emitNode(outputArray, count, x, y, z, SCAFFOLD_MALUS);
        }

        if (this.isOpenColumn(x, y, z)) {
            // Vanilla skipped this cell for lack of footing; scaffolding under
            // it makes it crossable -- cheapest when the scaffold already exists
            if (this.isScaffolding(x, y - 1, z)) {
                return this.emitNode(outputArray, count, x, y, z, SCAFFOLD_MALUS);
            }
            if (this.isReplaceableForScaffold(x, y - 1, z)) {
                return this.emitNode(outputArray, count, x, y, z, BRIDGE_MALUS);
            }
            return count;
        }

        float tunnelMalus = this.tunnelMalus(x, y, z);
        if (tunnelMalus >= 0.0F) {
            return this.emitNode(outputArray, count, x, y, z, tunnelMalus);
        }
        return count;
    }

    /**
     * Appends a synthetic node at (x, y, z). Never re-emits a node object
     * PathFinder has already expanded: a closed node's cameFrom can be
     * overwritten without a cost check, which can close a cycle in the cameFrom
     * chain and hang reconstructPath forever. Closed positions get a fresh
     * {@link Node} instance instead, whose cameFrom is set at most once.
     */
    private int emitNode(Node[] outputArray, int count, int x, int y, int z, float malus) {
        if (count >= outputArray.length || !this.extraEmissions.add(BlockPos.asLong(x, y, z))) {
            return count;
        }

        Node node = this.getNode(x, y, z);
        if (node.inOpenSet()) {
            // Already a live candidate this search; leave vanilla's entry alone
            return count;
        }
        if (node.closed) {
            node = new Node(x, y, z);
        }
        node.type = PathType.WALKABLE;
        node.costMalus = malus;
        outputArray[count++] = node;
        return count;
    }

    private boolean isScaffolding(int x, int y, int z) {
        return this.currentContext.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof ScaffoldingBlock;
    }

    /**
     * Whether a scaffold could be placed at (x, y, z): empty space or a
     * replaceable block, but never inside a fluid.
     */
    private boolean isReplaceableForScaffold(int x, int y, int z) {
        BlockState state = this.currentContext.getBlockState(new BlockPos(x, y, z));
        return state.isAir() || (state.canBeReplaced() && state.getFluidState().isEmpty());
    }

    /**
     * Whether the mob could occupy the column at (x, y, z) if it had footing:
     * no collision and no scaffolding in any cell of the mob's height.
     */
    private boolean isOpenColumn(int x, int y, int z) {
        for (int dy = 0; dy < this.entityHeight; dy++) {
            BlockPos pos = new BlockPos(x, y + dy, z);
            BlockState state = this.currentContext.getBlockState(pos);
            if (state.getBlock() instanceof ScaffoldingBlock
                    || !state.getCollisionShape(this.currentContext.level(), pos).isEmpty()) {
                return false;
            }
        }
        return true;
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
