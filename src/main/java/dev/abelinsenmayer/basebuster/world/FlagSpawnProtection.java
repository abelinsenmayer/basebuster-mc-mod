package dev.abelinsenmayer.basebuster.world;

import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.Comparator;

/**
 * Respawn control for faction-owned flags: a flag held by a non-player faction
 * (e.g. {@link BaseFlagBlockEntity#RAIDER_FACTION}) bars players from keeping or
 * setting a respawn point inside its {@link BaseFlagBlock#FLAG_PROTECTION_RADIUS}.
 */
public final class FlagSpawnProtection {
    private FlagSpawnProtection() {
    }

    /**
     * The position of the nearest faction-owned flag whose protection radius
     * contains {@code pos} in {@code level}, or null if the spot is uncontrolled.
     *
     * <p>Flag locations are tracked dimension-agnostically, so the block entity
     * at each candidate is checked in {@code level}: a flag physically exists in
     * exactly one dimension and only controls that dimension's spawn points.
     */
    @Nullable
    public static BlockPos controllingFlag(ServerLevel level, BlockPos pos) {
        double radiusSqr = Math.pow(BaseFlagBlock.FLAG_PROTECTION_RADIUS, 2);
        return FlagsSavedData.getFromLevel(level).getFlagLocations().stream()
                .filter(flagPos -> flagPos.distSqr(pos) <= radiusSqr)
                .filter(flagPos -> {
                    BlockEntity blockEntity = level.getBlockEntity(flagPos);
                    return blockEntity instanceof BaseFlagBlockEntity flagEntity && flagEntity.isFactionOwned();
                })
                .min(Comparator.comparingDouble(flagPos -> flagPos.distSqr(pos)))
                .orElse(null);
    }

    /**
     * Clears {@code player}'s respawn point when it sits inside a faction flag's
     * protected area, notifying them in red. Covers spawns that became illegal
     * while the player was offline.
     *
     * @return whether the respawn point was removed
     */
    public static boolean clearSpawnIfControlled(ServerPlayer player) {
        BlockPos respawnPos = player.getRespawnPosition();
        if (respawnPos == null) {
            return false;
        }
        ServerLevel respawnLevel = player.server.getLevel(player.getRespawnDimension());
        if (respawnLevel == null || controllingFlag(respawnLevel, respawnPos) == null) {
            return false;
        }
        player.setRespawnPosition(Level.OVERWORLD, null, 0.0F, false, false);
        notifySpawnRemoved(player,
                "Your respawn point was removed: the area is controlled by a hostile faction.");
        return true;
    }

    /**
     * Removes every online player's respawn point inside {@code flagPos}'s
     * protected area in {@code flagLevel}. Called when the flag flips to a
     * non-player faction. Because respawn positions record the bed's block
     * position, this clears the spawns tied to every bed in the area (as well
     * as any other spawn source there, e.g. a /spawnpoint command).
     */
    public static void clearSpawnsInArea(ServerLevel flagLevel, BlockPos flagPos) {
        double radiusSqr = Math.pow(BaseFlagBlock.FLAG_PROTECTION_RADIUS, 2);
        for (ServerPlayer player : flagLevel.getServer().getPlayerList().getPlayers()) {
            BlockPos respawnPos = player.getRespawnPosition();
            if (respawnPos == null
                    || !player.getRespawnDimension().equals(flagLevel.dimension())
                    || respawnPos.distSqr(flagPos) > radiusSqr) {
                continue;
            }
            player.setRespawnPosition(Level.OVERWORLD, null, 0.0F, false, false);
            notifySpawnRemoved(player,
                    "Your respawn point was removed: the area is now controlled by a hostile faction.");
        }
    }

    private static void notifySpawnRemoved(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
    }
}
