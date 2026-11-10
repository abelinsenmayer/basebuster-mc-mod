# Minecraft Pathfinding Notes (NeoForge 21.1.265 / MC 1.21.1)

Source of truth: `build/moddev/artifacts/neoforge-21.1.265-sources.jar`.
Relevant package: `net.minecraft.world.level.pathfinder` + `net.minecraft.world.entity.ai.navigation`.

## `PathFinder.findPath` — the A* core

- **Open set**: `BinaryHeap openSet`, ordered by `Node.f` (f = g + h).
- **Visited/g-score table**: `Int2ObjectMap<Node> nodes`, keyed by `BlockPos.asLong()`. `node.closed` marks the closed set.
- **Start**: `nodeEvaluator.getStart()` — a `Node` at the mob's feet.
- **Goal test**: `NodeEvaluator.getTarget(...)` produces `Target` nodes; reached when `node.distanceManhattan(target) < accuracy`.
- **Expansion**: `nodeEvaluator.getNeighbors(node)` generates successors on demand.
  Tentative g-score: `node1.g + node1.distanceTo(node2) + node2.costMalus`.
  If better than `node2.g` → update parent/g/h/f and push to heap.
- **Budget**: `maxVisitedNodes` caps the search (`PathNavigation` derives it from
  `Attributes.FOLLOW_RANGE * 16 * maxVisitedNodesMultiplier`). Long paths come back
  partial — goals re-path periodically to keep approaching.

**Key insight**: `PathFinder` knows nothing about blocks. All world knowledge lives
in `NodeEvaluator`. To change what is pathable, touch the evaluator/malus — never `findPath`.

## What nodes are

- A `Node` is a **foot position** (integer block coords, semantically "where the mob's
  feet can be"), not "a block". One column can yield nodes at several y-levels.
- Carries: `type` (`PathType`), `costMalus`, `g`, `h`, `f`, `cameFrom`, `closed`, `walkedDistance`.
- The graph is implicit — `NodeEvaluator.getNeighbors` defines edges, which are
  *capabilities*, not just adjacency: diagonals, step-ups (`Attributes.STEP_HEIGHT`),
  drops (`Attributes.SAFE_FALL_DISTANCE`), door logic, floating.
- Evaluators = different graphs: `WalkNodeEvaluator` (zombies), `FlyNodeEvaluator`,
  `SwimNodeEvaluator`, `AmphibiousNodeEvaluator`, `ClimbWallNodeEvaluator` (spiders).

## Controlling valid paths — three levels

1. **`PathType` classification** — `WalkNodeEvaluator.getPathType` classifies each
   foot position (`WALKABLE`, `OPEN`, `BLOCKED`, `DOOR_WOOD_CLOSED`, `DANGER_FIRE`,
   `WATER`, `LAVA`, ...). NeoForge hooks: `IBlockExtension#getPathType` /
   `getAdjacentBlockPathType` (per-block, mob-aware).
2. **`Mob#getPathfindingMalus(PathType)`** — mob-specific penalty per type.
   **`malus < 0` ⇒ node dropped entirely**; ≥ 0 is merely expensive. This is the
   cheap knob (raise water/door malus = avoidance; lower = tolerance).
3. **`NodeEvaluator#getNeighbors`** — decides which nodes exist at all. The hard-power
   knob: a custom evaluator can emit nodes *inside* walls (tunneling) or over gaps
   (bridging) with inflated `costMalus`, so A* weighs digging vs. going around.

Injection point: `PathNavigation#createPathFinder` / `createNodeEvaluator`, or build
`new PathFinder(evaluator, maxVisitedNodes)` yourself.

## Tunneling / bridging feasibility

- **Graph/cost side (moderate)**: extend `WalkNodeEvaluator`; emit extra neighbors in
  diggable solid blocks / over gaps with high malus; override `getPathfindingMalus`
  so custom types return positive values, not -1. Euclidean `h` stays admissible.
- **Execution side (significant)**: `MoveControl` assumes vanilla movement. When the
  next path node is inside solid blocks → run break-block behavior, then resume.
  For bridge nodes → place a block. Precedent: `BreakDoorGoal` — nav treats doors as
  costly-but-passable nodes; a separate goal does the breaking while "stuck".
- Watch: `PathNavigation.canCutCorner`/`shouldTargetNextNodeInDirection` must treat
  your custom types as traversable or the mob hugs walls; raise `maxVisitedNodesMultiplier`.

## Vanilla precedent (zombie)

- Zombie chasing a villager → `GroundPathNavigation` + `WalkNodeEvaluator`.
- Closed wooden door = `DOOR_WOOD_CLOSED` node, costly not banned; `BreakDoorGoal`
  (priority 1, gated by `canBreakDoors`) smashes it while path is stalled.
- `setCanBreakDoors` also flips `GroundPathNavigation.setCanOpenDoors` — vanilla's
  own "retune the graph + add execution goal" pattern. Mirror this for digging.

## Reading order

`Node`, `Path`, `Target`, `BinaryHeap` → `NodeEvaluator` → `WalkNodeEvaluator`
(getNeighbors/getPathType/getStart/getTarget) → `PathNavigation` + `GroundPathNavigation`
→ `MoveToBlockGoal`, `BreakDoorGoal`, `Zombie`.
