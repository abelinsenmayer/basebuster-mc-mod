package dev.abelinsenmayer.basebuster.client;

import net.minecraft.core.BlockPos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side set of flag positions whose protection-boundary overlay is on.
 * Maintained by {@link dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity}
 * as block entities are loaded, updated, and removed.
 *
 * <p>Contains no client-only types so common code can touch it safely on any side.
 */
public final class FlagBoundaryDebugState {
    private static final Set<BlockPos> ENABLED_FLAGS = ConcurrentHashMap.newKeySet();

    private FlagBoundaryDebugState() {
    }

    public static void add(BlockPos pos) {
        ENABLED_FLAGS.add(pos);
    }

    public static void remove(BlockPos pos) {
        ENABLED_FLAGS.remove(pos);
    }

    public static void clear() {
        ENABLED_FLAGS.clear();
    }

    public static boolean isEmpty() {
        return ENABLED_FLAGS.isEmpty();
    }

    public static Set<BlockPos> enabledFlags() {
        return ENABLED_FLAGS;
    }
}
