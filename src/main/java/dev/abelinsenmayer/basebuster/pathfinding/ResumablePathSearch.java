package dev.abelinsenmayer.basebuster.pathfinding;

import com.google.common.collect.Lists;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.Target;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Vanilla {@code PathFinder}'s A* run as a resumable state machine: the same
 * expansion loop, but spread across many {@link #step} calls so a very large
 * search's total cost no longer has to be spent inside one tick. The search
 * as a whole is bounded by a wall-clock {@link #timeLimit} -- measured across
 * all {@link #step} slices -- rather than vanilla's expansion cap. {@code PathFinder}
 * itself can't be paused (its counters and reached-target set are locals), so
 * this mirrors the loop over the same public state:
 *
 * <ul>
 *   <li>{@link #openSet} -- the frontier, a plain {@link BinaryHeap}.
 *   <li>{@code NodeEvaluator.nodes} -- the g-score/closed table, which lives on
 *   the evaluator between {@code prepare()} and {@code done()}. {@code done()}
 *   is deferred until the search finishes or is cancelled, since it nulls the
 *   context {@link NodeEvaluator#getNeighbors} reads.
 *   <li>{@code Target.bestNode} -- the best-approach tracking used for partial
 *   paths, accumulated inside the {@link Target} objects themselves.
 *   <li>The {@link PathNavigationRegion} passed to {@link #begin}, which
 *   snapshots its chunk array at construction -- reads stay valid for the
 *   search's whole lifetime even across chunk unloads.
 * </ul>
 *
 * <p>State is in-memory only; a search abandoned across a world reload is
 * dropped. Requesters that need persistence save their intent and resubmit
 * on load -- RaiderBossEntity does this for squad-path searches via its
 * saved pending-search target.
 */
public final class ResumablePathSearch {
    // Vanilla weights the heuristic by 1.5 (greedy A*); keeps the frontier
    // tractable on long searches -- copied verbatim from PathFinder
    private static final float FUDGING = 1.5F;

    private final NodeEvaluator nodeEvaluator;
    private final Duration timeLimit;
    private final BinaryHeap openSet = new BinaryHeap();
    private final Node[] neighbors = new Node[32];

    // Search state initialized by begin()
    private Node startNode;
    private Map<Target, BlockPos> targetMap = Map.of();
    private Set<Target> reachedTargets = Set.of();
    private float maxRange;
    private int accuracy;
    private long startedAtNanos;
    private long deadlineNanos;
    private int visited;
    private boolean finished;
    @Nullable
    private Path result;

    public ResumablePathSearch(NodeEvaluator nodeEvaluator, Duration timeLimit) {
        this.nodeEvaluator = nodeEvaluator;
        this.timeLimit = timeLimit;
    }

    /**
     * Initializes the search the way {@code PathFinder.findPath} does:
     * prepares the evaluator, builds the target set, and seeds the open set
     * with the start node. A null start node finishes the search immediately
     * with no result.
     */
    public void begin(PathNavigationRegion region, Mob mob, Set<BlockPos> targetPositions,
                      float maxRange, int accuracy, float searchDepthMultiplier) {
        this.nodeEvaluator.prepare(region, mob);
        this.startNode = this.nodeEvaluator.getStart();
        if (this.startNode == null) {
            this.nodeEvaluator.done();
            this.finished = true;
            return;
        }
        this.targetMap = targetPositions.stream()
                .collect(Collectors.toMap(
                        pos -> this.nodeEvaluator.getTarget(pos.getX(), pos.getY(), pos.getZ()),
                        Function.identity()));
        this.maxRange = maxRange;
        this.accuracy = accuracy;
        this.startedAtNanos = System.nanoTime();
        this.deadlineNanos = this.startedAtNanos + (long) (this.timeLimit.toNanos() * searchDepthMultiplier);
        this.reachedTargets = new HashSet<>(this.targetMap.size());
        this.startNode.g = 0.0F;
        this.startNode.h = this.getBestH(this.startNode);
        this.startNode.f = this.startNode.h;
        this.openSet.insert(this.startNode);
    }

    /**
     * Expands nodes until {@code nanosBudget} is spent (always at least one
     * expansion, so progress can't stall on an exhausted budget), or until the
     * search finishes.
     *
     * @return whether the search has finished -- {@link #result()} then holds
     * the found (or best-approach partial) path, or null
     */
    public boolean step(long nanosBudget) {
        if (this.finished) {
            return true;
        }
        long deadline = System.nanoTime() + nanosBudget;
        do {
            if (this.openSet.isEmpty() || System.nanoTime() >= this.deadlineNanos) {
                this.finish();
                return true;
            }
            this.visited++;

            Node node = this.openSet.pop();
            node.closed = true;

            for (Target target : this.targetMap.keySet()) {
                if (node.distanceManhattan(target) <= (float) this.accuracy) {
                    target.setReached();
                    this.reachedTargets.add(target);
                }
            }
            if (!this.reachedTargets.isEmpty()) {
                this.finish();
                return true;
            }

            if (!(node.distanceTo(this.startNode) >= this.maxRange)) {
                int count = this.nodeEvaluator.getNeighbors(this.neighbors, node);
                for (int i = 0; i < count; i++) {
                    Node neighbor = this.neighbors[i];
                    float stepCost = node.distanceTo(neighbor);
                    neighbor.walkedDistance = node.walkedDistance + stepCost;
                    float g = node.g + stepCost + neighbor.costMalus;
                    if (neighbor.walkedDistance < this.maxRange && (!neighbor.inOpenSet() || g < neighbor.g)) {
                        neighbor.cameFrom = node;
                        neighbor.g = g;
                        neighbor.h = this.getBestH(neighbor) * FUDGING;
                        if (neighbor.inOpenSet()) {
                            this.openSet.changeCost(neighbor, neighbor.g + neighbor.h);
                        } else {
                            neighbor.f = neighbor.g + neighbor.h;
                            this.openSet.insert(neighbor);
                        }
                    }
                }
            }
        } while (System.nanoTime() < deadline);
        return false;
    }

    /** Whether the search finished (or was cancelled); see {@link #result()}. */
    public boolean isFinished() {
        return this.finished;
    }

    /** Nodes expanded so far -- grows across {@link #step} calls. */
    public int visited() {
        return this.visited;
    }

    /** Wall-clock milliseconds since {@link #begin}, across all {@link #step} slices. */
    public long elapsedMillis() {
        return (System.nanoTime() - this.startedAtNanos) / 1_000_000L;
    }

    /** The total wall-clock cap (time limit x search-depth multiplier). */
    public long limitMillis() {
        return (this.deadlineNanos - this.startedAtNanos) / 1_000_000L;
    }

    /** Nodes still on the frontier. */
    public int frontierSize() {
        return this.openSet.size();
    }

    /**
     * The path once {@link #isFinished}, or null when the search found nothing
     * (empty open set, unreachable targets) or was cancelled.
     */
    @Nullable
    public Path result() {
        return this.result;
    }

    /**
     * Abandons the search: releases the evaluator and discards the result.
     * The retained nodes/heap die with this object.
     */
    public void cancel() {
        if (!this.finished) {
            this.nodeEvaluator.done();
            this.finished = true;
            this.result = null;
        }
    }

    /**
     * Vanilla's termination path: prefer a reached target's path, else the
     * best-approach partial path across all targets.
     */
    private void finish() {
        Optional<Path> optional = !this.reachedTargets.isEmpty()
                ? this.reachedTargets.stream()
                    .map(target -> this.reconstructPath(target.getBestNode(), this.targetMap.get(target), true))
                    .min(Comparator.comparingInt(Path::getNodeCount))
                : this.targetMap.keySet().stream()
                    .map(target -> this.reconstructPath(target.getBestNode(), this.targetMap.get(target), false))
                    .min(Comparator.comparingDouble(Path::getDistToTarget).thenComparingInt(Path::getNodeCount));
        this.result = optional.orElse(null);
        this.nodeEvaluator.done();
        this.finished = true;
    }

    /**
     * Vanilla's per-node heuristic: distance to the closest target, updating
     * each target's best-approach node along the way.
     */
    private float getBestH(Node node) {
        float best = Float.MAX_VALUE;
        for (Target target : this.targetMap.keySet()) {
            float h = node.distanceTo(target);
            target.updateBest(h, node);
            best = Math.min(h, best);
        }
        return best;
    }

    private Path reconstructPath(Node point, BlockPos targetPos, boolean reachesTarget) {
        List<Node> nodes = Lists.newArrayList();
        Node node = point;
        nodes.add(0, point);
        while (node.cameFrom != null) {
            node = node.cameFrom;
            nodes.add(0, node);
        }
        return new Path(nodes, targetPos, reachesTarget);
    }
}
