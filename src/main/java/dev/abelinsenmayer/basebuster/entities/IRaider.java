package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.world.FlagsSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

import javax.annotation.Nullable;
import java.util.Comparator;

/**
 * The raider capability: the state and flag queries shared by every mob that
 * participates in raids, regardless of its vanilla type. Implemented by the
 * concrete raider entity classes ({@link RaiderEntity}, and any future
 * illager raiders), which delegate storage to {@link RaiderComponent} -- the
 * interface plus delegate is what composition looks like under Java's single
 * inheritance: one interface contract, any {@link PathfinderMob} subclass.
 *
 * <p>Flag queries are interface defaults: they only read
 * {@link FlagsSavedData} and the host's own position/restriction, so every
 * raider gets them for free. Note this is unrelated to vanilla's
 * {@code net.minecraft.world.entity.raid.Raider} (the illager/patrol raid
 * base class) -- watch imports when both are on screen.
 */
public interface IRaider {
    /** The entity carrying this raider behavior. */
    PathfinderMob mob();

    /** Whether this raider type participates in squads; leaders never do. */
    boolean canJoinSquad();

    /** The boss this raider marches under, or null while unsquadded. */
    @Nullable
    IRaiderBoss getSquadLeader();

    void setSquadLeader(@Nullable IRaiderBoss squadLeader);

    /**
     * This raider's combat kit: equipment, stats, and attack style. Never
     * null -- entities that never went through finalizeSpawn fall back to a
     * deterministic UUID-derived kit.
     */
    RaiderKit getKit();

    /** Pins this raider to a specific kit; call before finalizeSpawn for the
     *  kit's equipment to be applied. */
    void setKit(RaiderKit kit);

    /**
     * The tier of the raid this raider spawned in. Kits read it during
     * {@link RaiderKit#equip} to pick their gear; bosses also use it to pick
     * the victory banner the raid drops.
     */
    int getRaidTier();

    /** Tags this raider with its raid's tier; set before finalizeSpawn so
     *  the kit equips the tier's loadout. */
    void setRaidTier(int raidTier);

    /**
     * Whether the raider is on an assault: a flag not owned by the raider
     * faction exists for it to march on. Raiders guarding an already-captured
     * flag are not assaulting, so they never join squads or flee.
     */
    default boolean isAttemptingCapture() {
        return this.nearestUncapturedFlag() != null;
    }

    /** The closest registered flag not owned by the raider faction. */
    @Nullable
    default BlockPos nearestUncapturedFlag() {
        if (!(this.mob().level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        FlagsSavedData flagsSavedData = FlagsSavedData.getFromLevel(serverLevel);
        BlockPos mobPos = this.mob().blockPosition();
        return flagsSavedData.getFlagLocations().stream()
                .filter(flagPos -> !flagsSavedData.getCapturedFlagLocations().contains(flagPos))
                .filter(this.mob()::isWithinRestriction)
                .min(Comparator.comparingDouble(flagPos -> flagPos.distSqr(mobPos)))
                .orElse(null);
    }

    /** The closest registered flag, regardless of ownership. */
    @Nullable
    default BlockPos nearestFlag() {
        if (!(this.mob().level() instanceof ServerLevel serverLevel)) {
            return null;
        }
        FlagsSavedData flagsSavedData = FlagsSavedData.getFromLevel(serverLevel);
        BlockPos mobPos = this.mob().blockPosition();
        return flagsSavedData.getFlagLocations().stream()
                .filter(this.mob()::isWithinRestriction)
                .min(Comparator.comparingDouble(flagPos -> flagPos.distSqr(mobPos)))
                .orElse(null);
    }

    /**
     * The traversal stats every raider shares: a full-block step height so
     * single-block ledges and slab/stair defenses don't stall the path, and
     * partial water efficiency so moats slow rather than stop the assault.
     * Called from the host's finalizeSpawn.
     */
    static void applyTraversalAttributes(PathfinderMob mob) {
        AttributeInstance stepHeight = mob.getAttribute(Attributes.STEP_HEIGHT);
        if (stepHeight != null) {
            stepHeight.setBaseValue(1.0);
        }
        AttributeInstance waterEfficiency = mob.getAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY);
        if (waterEfficiency != null) {
            waterEfficiency.setBaseValue(0.5);
        }
    }
}
