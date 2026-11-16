package dev.abelinsenmayer.basebuster.entities;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The squad-leader capability on top of {@link IRaider}: a boss owns a squad
 * route, plans the expensive long-range path searches followers consume, and
 * carries the raid identity that break protection and banner drops key on.
 * Implemented by {@link RaiderBossEntity} (and any future boss-typed raiders),
 * which delegate storage to {@link RaiderBossComponent}.
 */
public interface IRaiderBoss extends IRaider {
    /**
     * The shared rough route this boss's squad marches along. Followers take
     * the immutable list itself, so updating it never mutates a path another
     * raider is following.
     */
    List<BlockPos> getSquadPath();

    /** The flag {@link #getSquadPath} leads to; null when no path is stored. */
    @Nullable
    BlockPos getSquadPathTarget();

    void setSquadPath(List<BlockPos> waypoints, BlockPos flagPos);

    void clearSquadPath();

    /**
     * The squad path as synced to clients. Unlike {@link #getSquadPath}, which
     * is populated only on the server, this reads entity data so it resolves
     * on both sides -- the debug overlay's source of truth.
     */
    List<BlockPos> getSyncedSquadPath();

    /**
     * Whether a squad-path search is in flight or was at last save. Pending
     * state lives on the boss (not on a goal) so it can ride NBT across
     * world reloads.
     */
    boolean isSquadPathSearchPending();

    /**
     * Queues a bounded long-range search from this boss to {@code flag},
     * stepped across ticks by the path-search scheduler; the decimated
     * result replaces the squad route.
     */
    void planSquadPathTo(BlockPos flag);

    /** Which raid this boss belongs to; null for egg/summon spawns. */
    @Nullable
    UUID getRaidId();

    /** Tags this boss as a member of a raid; the spawner assigns one id per raid. */
    void setRaidId(UUID raidId);

    /** The flag this boss's raid is attacking; null for non-raid bosses. */
    @Nullable
    BlockPos getRaidTarget();

    /** Marks the flag this boss's raid is attacking; set alongside the raid id. */
    void setRaidTarget(BlockPos raidTarget);

    /**
     * All raider bosses inside {@code box}. Bosses are always mobs, so the
     * scan rides {@code getEntitiesOfClass(Mob)} -- vanilla's entity-type
     * test machinery can't key on an interface that isn't an Entity subclass.
     */
    static List<IRaiderBoss> nearby(Level level, AABB box) {
        List<IRaiderBoss> bosses = new ArrayList<>();
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, e -> e instanceof IRaiderBoss)) {
            bosses.add((IRaiderBoss) mob);
        }
        return bosses;
    }
}
