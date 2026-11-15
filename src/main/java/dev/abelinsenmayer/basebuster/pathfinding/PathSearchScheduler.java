package dev.abelinsenmayer.basebuster.pathfinding;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.Config;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Steps pending {@link ResumablePathSearch}es a bounded slice each server
 * tick, so a long-range search's total cost is amortized across ticks instead
 * of freezing one. Driven by {@code ServerTickEvent.Post} via
 * {@link BaseBusterEventBus}; all callbacks run on the server thread.
 *
 * <p>Each tick steps queued searches in FIFO order until the configured
 * millisecond budget is spent. A search that doesn't finish rejoins the tail,
 * so pending searches round-robin: each gets up to a full budget slice once
 * every queueSize ticks rather than starving behind a long-running head.
 *
 * <p>Everything is in-memory: {@link #onServerStopping} drops the queue, and a
 * search whose {@code stillValid} check fails (e.g. its boss was removed) is
 * cancelled without invoking its callback.
 *
 * <p>When {@code raidDebug.logSearchHeartbeat} is enabled, a once-a-second
 * line reports every in-flight search's label and progress, plus a line on
 * each completion.
 */
public final class PathSearchScheduler {
    // Heartbeat cadence: once a second while searches are in flight
    private static final int HEARTBEAT_TICKS = 20;

    private record Pending(ResumablePathSearch search, String label, BooleanSupplier stillValid, Consumer<Path> onComplete) {
    }

    private static final Queue<Pending> PENDING = new ArrayDeque<>();
    private static int heartbeatCountdown;

    private PathSearchScheduler() {
    }

    /**
     * Enqueues a search to be stepped on subsequent server ticks.
     *
     * @param label      identifies the search in heartbeat logs
     * @param stillValid polled before each slice; false cancels the search and
     *                   skips the callback (use it to bind a search to its
     *                   requester's lifetime)
     * @param onComplete invoked once the search finishes, with its result --
     *                   possibly null when the search found nothing
     */
    public static void schedule(ResumablePathSearch search, String label, BooleanSupplier stillValid, Consumer<Path> onComplete) {
        PENDING.offer(new Pending(search, label, stillValid, onComplete));
    }

    /** Steps each pending search once until the per-tick ms budget is spent. */
    public static void onServerTick() {
        if (PENDING.isEmpty()) {
            return;
        }
        long deadline = System.nanoTime() + Config.RAID_PATHING_MS_PER_TICK.get() * 1_000_000L;
        int queueSize = PENDING.size();
        for (int i = 0; i < queueSize; i++) {
            Pending pending = PENDING.poll();
            if (pending == null) {
                break;
            }
            if (!pending.stillValid().getAsBoolean()) {
                pending.search().cancel();
                continue;
            }
            if (pending.search().step(deadline - System.nanoTime())) {
                logCompletion(pending);
                pending.onComplete().accept(pending.search().result());
            } else {
                PENDING.offer(pending);
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        logHeartbeat();
    }

    /** Drops all pending searches; none of their callbacks run. */
    public static void onServerStopping() {
        Pending pending;
        while ((pending = PENDING.poll()) != null) {
            pending.search().cancel();
        }
    }

    /** One line per heartbeat interval while searches are in flight. */
    private static void logHeartbeat() {
        if (!Config.RAID_SEARCH_HEARTBEAT_LOGGING.get()) {
            return;
        }
        if (--heartbeatCountdown > 0) {
            return;
        }
        heartbeatCountdown = HEARTBEAT_TICKS;
        StringBuilder sb = new StringBuilder("Path searches in flight: ").append(PENDING.size());
        for (Pending pending : PENDING) {
            ResumablePathSearch search = pending.search();
            sb.append(" | ").append(pending.label())
                    .append(" visited=").append(search.visited())
                    .append(" elapsed=").append(search.elapsedMillis()).append('/').append(search.limitMillis()).append("ms")
                    .append(" open=").append(search.frontierSize());
        }
        BaseBuster.LOGGER.info(sb.toString());
    }

    private static void logCompletion(Pending pending) {
        if (!Config.RAID_SEARCH_HEARTBEAT_LOGGING.get()) {
            return;
        }
        ResumablePathSearch search = pending.search();
        Path result = search.result();
        BaseBuster.LOGGER.info("Path search '{}' finished after {} expansions: {}",
                pending.label(), search.visited(),
                result != null
                        ? result.getNodeCount() + " nodes, reached=" + result.canReach()
                        : "no path");
    }
}
