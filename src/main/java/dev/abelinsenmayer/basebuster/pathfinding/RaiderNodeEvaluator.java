package dev.abelinsenmayer.basebuster.pathfinding;

import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.entities.RaiderEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
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
 *   Tunneling also works downward: a breakable floor cell underfoot emits a
 *   node the mob drops into after the break, so raiders can mine down to
 *   buried targets.
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
    private static final float TUNNEL_MALUS_BASE = 0.1F;
    private static final float TUNNEL_MALUS_PER_HARDNESS = 1.0F;
    // Traversing/alongside existing scaffolding is cheap -- just slower footing
    private static final float SCAFFOLD_MALUS = 1.0F;
    // One cell of an existing climbable (ladder, vines) -- vanilla maps these
    // to BLOCKED, so without this raiders dig through the ladder and its wall
    private static final float CLIMBABLE_MALUS = 1.0F;
    // A walkway node over a gap that needs a scaffold placed below it
    private static final float BRIDGE_MALUS = 12.0F;
    // One level of a scaffold pillar the mob has to build and climb
    private static final float PILLAR_MALUS = 12.0F;
    // Digging straight down is priced below horizontal tunneling: it makes
    // progress toward a buried target on every node, and it must stay under
    // PILLAR_MALUS for ordinary materials so the pathfinder keeps digging a
    // shaft it started instead of preferring to climb out and re-dig elsewhere
    private static final float DIG_DOWN_MALUS_SCALE = 0.7F;

    // Positions already proposed as synthetic nodes during this search, so a
    // cell is never emitted twice (each repeat costs a separate node expansion)
    private final Set<Long> extraEmissions = new HashSet<>();

    public RaiderNodeEvaluator(Mob mob) {
        // prepare() normally assigns this.mob; seeding it in the ctor keeps
        // canFloat() correct even before the first pathfind (jumpInFluid
        // consults it on every submerged jump)
        this.mob = mob;
    }

    /**
     * FloatGoal flips canFloat on for every raider at goal registration, but
     * sinking raiders must keep it off: it controls both the gentle-rise
     * impulse in Mob#jumpInFluid and whether water cells stay surface nodes
     * or drop to the first walkable floor below.
     */
    @Override
    public boolean canFloat() {
        return super.canFloat() && !(this.mob instanceof RaiderEntity raider && raider.sinksInWater());
    }

    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        this.extraEmissions.clear();
    }

    @Override
    public int getNeighbors(Node[] outputArray, Node node) {
        int count = super.getNeighbors(outputArray, node);

        // Scaffold, climb, and door-open moves are emitted unconditionally;
        // RAIDER_TUNNELING_ENABLED gates only the dig-through-blocks moves:
        // emitHorizontalNeighbor -> tunnelMalus and emitVerticalNeighbors ->
        // digDownMalus
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
     * Vertical moves vanilla can't make: descending an existing scaffold,
     * dropping through a floor hatch, digging through the floor underfoot,
     * climbing a scaffold above, and pillaring up through open air.
     */
    private int emitVerticalNeighbors(Node[] outputArray, int count, Node node) {
        int x = node.x;
        int y = node.y;
        int z = node.z;

        if (!this.alreadyEmitted(outputArray, count, x, y - 1, z)) {
            if (this.isScaffolding(x, y - 1, z) || this.isClimbable(x, y - 1, z)) {
                count = this.emitNode(outputArray, count, x, y - 1, z, CLIMBABLE_MALUS);
            } else if (this.isOpenableByHand(x, y - 1, z) && this.hasSafeLanding(x, y - 2, z)) {
                // Floor hatch (e.g. a trapdoor over a drop): opens on approach
                // like a ceiling hatch, then the mob falls through
                count = this.emitNode(outputArray, count, x, y - 1, z, CLIMBABLE_MALUS);
            } else {
                float digMalus = this.digDownMalus(x, y - 1, z);
                if (digMalus >= 0.0F) {
                    count = this.emitNode(outputArray, count, x, y - 1, z, digMalus);
                }
            }
        }

        // Pillars can't start mid-fall; only grounded or scaffold-held nodes
        if (node.type == PathType.OPEN) {
            return count;
        }

        if (this.isScaffolding(x, y + 1, z) || this.isClimbable(x, y + 1, z)) {
            count = this.emitNode(outputArray, count, x, y + 1, z, CLIMBABLE_MALUS);
        } else if (this.isOpenableByHand(x, y + 1, z)) {
            // Ceiling hatch (e.g. atop a ladder): opens on approach, then the
            // mob climbs through it
            count = this.emitNode(outputArray, count, x, y + 1, z, CLIMBABLE_MALUS);
        } else if (this.isReplaceableForScaffold(x, y + 1, z)
                && this.isReplaceableForScaffold(x, y + 2, z)) {
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

        if (this.isScaffolding(x, y, z) || this.isClimbable(x, y, z)) {
            return this.emitNode(outputArray, count, x, y, z, CLIMBABLE_MALUS);
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

        if (this.isOpenableByHand(x, y, z)) {
            // Door and trapdoor cells are handled by vanilla types plus the
            // goal's open-on-approach behavior -- never dig through them
            return count;
        }

        if (this.isPassableOrOpenable(x, y, z) && this.isPassableOrOpenable(x, y + 1, z)) {
            // Feet cell open but the head cell holds a closed trapdoor/door:
            // a cheap walk once opened on approach, never a dig
            return this.emitNode(outputArray, count, x, y, z, SCAFFOLD_MALUS);
        }

        float tunnelMalus = this.tunnelMalus(x, y, z);
        if (tunnelMalus >= 0.0F) {
            return this.emitNode(outputArray, count, x, y, z, tunnelMalus);
        }
        return count;
    }

    /**
     * Whether the cell would be occupiable either as-is (open air / no
     * collision) or after the block is opened by hand (closed door, closed
     * trapdoor). Used to price head-level hatches as a walk, not a dig.
     */
    private boolean isPassableOrOpenable(int x, int y, int z) {
        if (this.isOpenableByHand(x, y, z)) {
            return true;
        }
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = this.currentContext.getBlockState(pos);
        return state.getCollisionShape(this.currentContext.level(), pos).isEmpty();
    }

    /**
     * Whether the block at (x, y, z) is a door or trapdoor a raider can open by
     * hand. Mirrors vanilla's own check in getPathType: any door whose
     * BlockSetType is openable by hand (wood, copper) counts, as does any
     * trapdoor except the iron one.
     */
    private boolean isOpenableByHand(int x, int y, int z) {
        BlockState state = this.currentContext.getBlockState(new BlockPos(x, y, z));
        if (state.getBlock() instanceof DoorBlock door) {
            return door.type().canOpenByHand();
        }
        return state.getBlock() instanceof TrapDoorBlock && !state.is(Blocks.IRON_TRAPDOOR);
    }

    /**
     * Appends a synthetic node at (x, y, z). Never re-emits a node object
     * PathFinder has already expanded: a closed node's cameFrom can be
     * overwritten without a cost check, which can close a cycle in the cameFrom
     * chain and hang reconstructPath forever. Closed positions get a fresh
     * {@link Node} instance instead, whose cameFrom is set at most once.
     *
     * <p>Columns containing lava are rejected outright: liquids have no
     * collision shape, so the collision-based checks that gate tunneling and
     * bridging read lava cells as open, and vanilla's own LAVA = -1 rejection
     * never applies to cells it failed to classify. Lava-adjacent cells keep
     * their synthetic type but pay at least the mob's DANGER_FIRE malus,
     * mirroring {@code checkNeighbourBlocks}.
     */
    private int emitNode(Node[] outputArray, int count, int x, int y, int z, float malus) {
        if (count >= outputArray.length
                || this.columnContainsLava(x, y, z)
                || !this.extraEmissions.add(BlockPos.asLong(x, y, z))) {
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
        node.costMalus = Math.max(malus, this.lavaProximityMalus(x, y, z));
        outputArray[count++] = node;
        return count;
    }

    /**
     * Whether any cell of the mob-height column at (x, y, z) holds lava, source
     * or flowing. Occupying such a column means burning, so it is never a
     * synthetic move target. Water stays legal: wading and bottom-walking are
     * priced by vanilla's WATER malus instead.
     */
    private boolean columnContainsLava(int x, int y, int z) {
        for (int dy = 0; dy < this.entityHeight; dy++) {
            if (this.currentContext.getBlockState(new BlockPos(x, y + dy, z)).getFluidState().is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The malus floor for a synthetic node based on lava proximity: the mob's
     * DANGER_FIRE malus when lava touches the column horizontally (two blocks
     * up or down included, like {@code checkNeighbourBlocks}), else zero.
     * Vanilla applies DANGER_FIRE only to cells it classifies as WALKABLE; the
     * cells synthetic moves fill in -- wall interiors, gap air -- come out
     * BLOCKED, so a tunnel exit beside lava or a bridge over its lip would
     * otherwise carry no danger cost at all.
     */
    private float lavaProximityMalus(int x, int y, int z) {
        for (int dy = 0; dy < this.entityHeight+1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    for (int dj = -2; dj <= 2; dj++) {
                        if (this.currentContext.getBlockState(new BlockPos(x + dx, y + dy + dj, z + dz))
                                .getFluidState().is(FluidTags.LAVA)) {
                            return this.mob.getPathfindingMalus(PathType.DANGER_FIRE);
                        }
                    }
                }
            }
        }
        return 0.0F;
    }

    private boolean isScaffolding(int x, int y, int z) {
        return this.currentContext.getBlockState(new BlockPos(x, y, z)).getBlock() instanceof ScaffoldingBlock;
    }

    /**
     * Ladders, vines, and other #climbable blocks: vanilla maps these to
     * BLOCKED, but the mob can physically climb them just like scaffolding.
     */
    private boolean isClimbable(int x, int y, int z) {
        return this.currentContext.getBlockState(new BlockPos(x, y, z)).is(BlockTags.CLIMBABLE);
    }

    /**
     * Whether a scaffold could be placed at (x, y, z): empty space, a
     * replaceable block, or a water cell the scaffold can occupy waterlogged
     * (letting raiders pillar up through waterfalls and bridge across water).
     * Lava is never a scaffold target.
     */
    private boolean isReplaceableForScaffold(int x, int y, int z) {
        BlockState state = this.currentContext.getBlockState(new BlockPos(x, y, z));
        if (state.getFluidState().is(FluidTags.WATER)) {
            return true;
        }
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
     * Tunnel cost for the dig-down move into cell (x, y, z) -- the floor under
     * the mob's feet -- or -1 when the cell isn't a solid breakable block or
     * the drop below it lacks safe footing within the mob's safe fall
     * distance. The column's head cell is the space the mob already occupies,
     * so only the floor itself adds hardness.
     */
    private float digDownMalus(int x, int y, int z) {
        if (!this.hasSafeLanding(x, y - 1, z)) {
            return -1.0F;
        }
        float malus = this.tunnelMalus(x, y, z);
        return malus < 0.0F ? -1.0F : malus * DIG_DOWN_MALUS_SCALE;
    }

    /**
     * Whether a mob dropped through cell (x, y, z) comes to rest on solid
     * footing within its safe fall distance, without falling through lava.
     * Scans downward; the first cell with a collision shape is the landing
     * floor. Mirrors the drop limit vanilla applies via
     * {@link Attributes#SAFE_FALL_DISTANCE}.
     */
    private boolean hasSafeLanding(int x, int y, int z) {
        AttributeInstance safeFallDistance = this.mob.getAttribute(Attributes.SAFE_FALL_DISTANCE);
        int maxFall = safeFallDistance != null ? (int) safeFallDistance.getValue() : 3;
        for (int dy = 0; dy < maxFall; dy++) {
            BlockPos pos = new BlockPos(x, y - dy, z);
            BlockState state = this.currentContext.getBlockState(pos);
            if (state.getFluidState().is(FluidTags.LAVA)) {
                return false;
            }
            if (!state.getCollisionShape(this.currentContext.level(), pos).isEmpty()) {
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
        // Tunneling is a config toggle, and digging additionally requires mob
        // griefing since it destroys world blocks
        if (!Config.RAIDER_TUNNELING_ENABLED.get()
                || !this.mob.level().getGameRules().getBoolean(GameRules.RULE_MOBGRIEFING)) {
            return -1.0F;
        }
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
