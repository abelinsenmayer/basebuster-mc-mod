# Composable Raider Behavior: Feasibility & Implementation Plan

Goal: extract raider/boss behavior out of `RaiderEntity extends Zombie` so any
mob subclass can gain raider features via composition. Case study: illagers
(evoker, vindicator, pillager, ravager, etc.).

## Verdict

Highly feasible. The codebase is already better positioned than it looks:

- The pathfinding layer is fully `Mob`-generic already.
- Two of the raider goals already take `PathfinderMob`.
- The event bus (`BaseBusterEventBus`) is entity-agnostic.

The real work is concentrated in ~4 goals, `RaidSpawner`, and the per-subclass
boilerplate Java's single inheritance forces on us.

## What is already generic (no work needed)

- **Pathfinding**: `RaiderNodeEvaluator(Mob)`, `RaiderNavigation(Mob)`,
  `RaiderMoveControl(Mob)`, `SquadPathSearch` / `ResumablePathSearch` all take
  `Mob`. The only `RaiderEntity` reference in `RaiderNodeEvaluator` is a
  commented-out line and an unused import.
- **`CaptureFlagGoal`**, **`WanderNearFlagGoal`**: already take `PathfinderMob`.
- **Event bus**: `handleBlockGriefing` and flag-protection handlers are
  entity-agnostic.

## Feature inventory: what "raider-ness" is

| Feature | Mechanism today | Portability |
|---|---|---|
| Flag AI (capture, march, wander, squad, flee) | `registerGoals` goals | Easy — retype 4 goals |
| Tunnel/bridge/scaffold pathfinding | `createNavigation` + evaluator | Easy — ~3-line override per class |
| `RaiderMoveControl` (scaffold hold-position) | constructor | Easy — `Mob`-generic |
| Kit (equip + stats) | `getKit`/`setKit` + `finalizeSpawn` | Medium — `RaiderKit.equip(RaiderEntity)` needs retype to `Mob`/`IRaider` |
| Squad membership / leadership | fields + accessors | Easy — delegate object |
| Skin + loot-table swap | synched data + `RaiderRenderer` | Optional per archetype (illagers look right already) |
| Friendly fire | `hurt()` `instanceof RaiderEntity` | Medium — move to `LivingIncomingDamageEvent` (fixes cross-species friendly fire too) |
| Glow config, XP/loot, boss `getTeamColor`/`SCALE` | method overrides | Small per-class shells — Java can't compose these away |
| Boss machinery (squad path, raidId/target, pending search, banner drop) | fields + NBT + tick | Medium — extract `RaiderBossComponent` |
| Zombie-isms (`isSunSensitive`, `convertsInWater`, sinking) | overrides | Drop for illagers — not applicable |

## Concrete coupling points

- `FleeGoal`, `JoinSquadGoal` — typed `RaiderEntity` fields (squad/flag APIs).
- `FollowSquadPathGoal`, `PlanSquadPathGoal`, `MoveToFlagGoal.nearestFlag()` —
  `instanceof RaiderEntity` / `RaiderBossEntity` casts.
- `JoinSquadGoal.findNearestBoss`, `RaiderBossEntity.isLastBossOfRaid`,
  `RaidSpawner.isRaidInProgress` — class scans. `EntityTypeTest.forClass` uses
  `isInstance` under the hood, so `forClass(IRaiderBoss.class)` works directly.
- `RaidSpawner` — hardcodes `RAIDER`/`RAIDER_BOSS` entity types and the
  `RaiderKit` column table; needs an archetype/composition abstraction.
- `RaiderKit.equip(RaiderEntity, RandomSource)` signature.
- `RaiderRenderer` (parameterized on `RaiderEntity`) and
  `SquadPathDebugRenderer` (`instanceof RaiderBossEntity`).

## Illager-specific notes

- **Name collision**: vanilla `net.minecraft.world.entity.raid.Raider` exists
  (base of `AbstractIllager`, `Ravager`, `Witch`). Name ours `IRaider` /
  `IRaiderBoss` and watch imports. Inheriting vanilla `Raider`'s raid fields is
  harmless — they're inert unless `setRaid` is called.
- **Combat**: keep *native* attack goals — Pillager crossbow, Vindicator axe
  melee, Evoker spells are better than porting kits. Kits become optional per
  archetype (an Evoker probably shouldn't get `SWORD_AND_BOARD`).
- **Goal priority conflicts**: `SpellcasterIllager` spell goals hold the `MOVE`
  flag — they preempt or yield to `CaptureFlagGoal` by priority. Ordering spells
  above capture gives flavorful "pause capture to cast" behavior; pick per
  archetype.
- **Ravager**: 1.95×2.2 bounding box — evaluator and scaffold logic already
  scale by `getBbHeight`. Its native `RavagerNavigation` (leaf-breaking) is
  replaced by `RaiderNavigation`; acceptable loss, merge later if wanted.
  Thematically the natural **boss** for an illager raid.
- **Vex**: flying `MoveControl`, not a `PathfinderMob` walker — skip for now.
- **Rendering**: free — `VindicatorRenderer` etc. accept subclasses, so illager
  raiders reuse vanilla renderers. `RaiderSkin` is simply not used.
- **Synched data**: `SynchedEntityData.defineId` requires the declaring class
  literal, so each subclass keeps ~4 lines of accessor boilerplate; component
  methods take the accessor as a parameter.

## Recommended design: interface + delegate

Each mob family gets its own subclass (single inheritance is unavoidable), but
they become thin shells (~60–100 lines of mechanical forwarding) over shared
components:

```
IRaider                    — capability: kit, squad leader, raid tier, flag queries
RaiderComponent            — state + logic delegate (kit, tier, squad leader,
                             goal-transition logging)
IRaiderBoss extends IRaider
RaiderBossComponent        — squad path, raidId/target, pending search, banner drop
RaiderArchetype (profile)  — per-family spec: native vs kit combat, melee/ranged
                             goal factories, FloatGoal policy, target specs,
                             canBreakDoors
RaiderGoals.install(mob, raider, archetype) — shared registerGoals body
```

```java
public class RaiderVindicatorEntity extends Vindicator implements IRaider {
    private final RaiderComponent raider = new RaiderComponent(this);
    // thin forwarding + ~4 unavoidable overrides
}
```

## Alternatives considered

- **NeoForge attachments + events on unmodified vanilla mobs** — true runtime
  composition with zero subclasses. Rejected: cannot add `SynchedEntityData` to
  vanilla classes (skin/squad-path sync would need custom packets), cannot
  override `createNavigation`/`isCurrentlyGlowing`/loot tables without per-type
  mixins, and it generally fights the framework. Only worthwhile if
  *naturally-spawned* vanilla illagers needed raider-ing — they don't;
  `RaidSpawner` controls all spawning.
- **Abstract mid-tier (`AbstractRaiderEntity`)** — does not help: `Vindicator`
  and `Zombie` share no subclassable ancestor below `Monster`.

## Phased plan

1. **Interfaces, zero behavior change** — define `IRaider`/`IRaiderBoss`;
   `RaiderEntity implements IRaider` with today's methods as the implementation.
   Move `nearestUncapturedFlag`/`nearestFlag`/`isAttemptingCapture` to a
   `RaiderFlags` static util or interface defaults.
2. **Decouple consumers** — retype the 5 goals and the 3 boss scans to the
   interfaces. Move friendly-fire to a `LivingIncomingDamageEvent` handler in
   `BaseBusterEventBus` checking `attacker/victim instanceof IRaider` (fixes
   cross-species friendly fire for free).
3. **Extract components** — `RaiderComponent` + `RaiderBossComponent`; the
   existing entities become shells over them. Validates the split before any new
   mob exists.
4. **Archetype + shared goal installer** — `RaiderGoals.install` +
   `RaiderArchetype` profile; the zombie archetype reproduces today's exact goal
   table.
5. **Illager subclasses** — `RaiderVindicatorEntity`, `RaiderPillagerEntity`,
   `RaiderEvokerEntity`, `RaiderRavagerEntity` (+ a boss variant, likely Ravager
   or Evoker). Registration, `createAttributes()`, spawn eggs, vanilla
   renderers, loot tables, lang keys.
6. **Generalize `RaidSpawner`** — `RaidTheme`/composition table mapping slots to
   entity factories per archetype; `spawnMember(Mob)`; interface-typed boss
   wiring.
7. **Debug/renderer touch-ups** — `SquadPathDebugRenderer` →
   `instanceof IRaiderBoss`.

## Gotchas

- `Mob` calls `registerGoals()` in its constructor, **before** subclass field
  initializers run — use a lazy `raider()` getter (same pattern as `getKit`'s
  UUID fallback) rather than direct field access inside goal lambdas.
- Kit-gated `canUse()`/`canContinueToUse()` checks are archetype-aware:
  `usesRangedAttack()` doesn't exist on `Vindicator` — the profile supplies the
  attack-style predicate.
- `HurtByTargetGoal.setAlertOthers(...)` should alert across raider species,
  not same-class — a profile knob.
- Boss `SCALE` is applied in the constructor so `/summon` sizes correctly; works
  for any `LivingEntity`.
- `EntityTypeTest.forClass(IRaiderBoss.class)` is legal (`isInstance`-based) —
  no need for marker superclasses.
