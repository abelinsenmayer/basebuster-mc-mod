package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.blocks.VictoryBannerBlock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

// A beefier raider: scaled up to roughly 2.5 blocks tall. Skins come from the
// same RaiderSkin pool as normal raiders; goals and behavior are inherited.
public class RaiderBossEntity extends RaiderEntity {
    // Base zombie height is 1.95 blocks; 1.95 * SCALE ~= 2.5
    private static final double BOSS_SCALE = 2.5 / 1.95;
    private static final String RAID_TAG = "RaidId";
    private static final String RAID_TIER_TAG = "RaidTier";
    // A dying boss scans this far for surviving bosses from the same raid
    // before awarding the victory banner
    private static final double RAID_MATE_SEARCH_RADIUS = 128.0;

    // Which raid this boss belongs to; null for egg/summon spawns, which are
    // not part of a raid and never drop a banner
    @Nullable
    private UUID raidId;
    // The banner tier the raid's last boss drops; only meaningful with a raidId
    private int raidTier = VictoryBannerBlock.MIN_TIER;

    public RaiderBossEntity(EntityType<? extends Zombie> entityType, Level level) {
        super(entityType, level);
        // Set in the constructor (not finalizeSpawn) so the size is correct
        // regardless of spawn path, including /summon.
        AttributeInstance scale = this.getAttribute(Attributes.SCALE);
        if (scale != null) {
            scale.setBaseValue(BOSS_SCALE);
        }
    }

    @Override
    protected double getConfiguredMaxHealth() {
        // Bosses get a full kit like normal raiders, plus a health bonus
        return this.getKit().maxHealth() + Config.RAIDER_BOSS_BONUS_HEALTH.get();
    }

    @Override
    public boolean canJoinSquad() {
        // Bosses are leaders; they don't join squads and never flee
        return false;
    }

    @Nullable
    public UUID getRaidId() {
        return this.raidId;
    }

    /** Tags this boss as a member of a raid; RaidSpawner assigns one id per raid. */
    public void setRaidId(UUID raidId) {
        this.raidId = raidId;
    }

    /** Sets the victory banner tier this raid's last boss drops. */
    public void setRaidTier(int raidTier) {
        this.raidTier = raidTier;
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        if (this.isLastBossOfRaid(level)) {
            int index = Math.clamp(this.raidTier, VictoryBannerBlock.MIN_TIER, VictoryBannerBlock.MAX_TIER) - 1;
            this.spawnAtLocation(new ItemStack(BaseBuster.VICTORY_BANNER_ITEMS.get(index).get()));
        }
    }

    /**
     * Whether no other boss from this boss's raid is still fighting. Bosses
     * without a raid id aren't part of a raid and never qualify. A boss
     * mid-death animation (isDeadOrDying) doesn't count as surviving, which
     * keeps simultaneous kills from suppressing the drop.
     */
    private boolean isLastBossOfRaid(ServerLevel level) {
        if (this.raidId == null) {
            return false;
        }
        List<RaiderBossEntity> survivingRaidMates = level.getEntities(
                EntityTypeTest.forClass(RaiderBossEntity.class),
                this.getBoundingBox().inflate(RAID_MATE_SEARCH_RADIUS),
                boss -> boss != this && this.raidId.equals(boss.raidId) && !boss.isDeadOrDying());
        return survivingRaidMates.isEmpty();
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.raidId != null) {
            tag.putUUID(RAID_TAG, this.raidId);
        }
        tag.putInt(RAID_TIER_TAG, this.raidTier);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(RAID_TAG)) {
            this.raidId = tag.getUUID(RAID_TAG);
        }
        if (tag.contains(RAID_TIER_TAG)) {
            this.raidTier = tag.getInt(RAID_TIER_TAG);
        }
    }
}
