package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.Config;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

// A beefier raider: scaled up to roughly 2.5 blocks tall. Skins come from the
// same RaiderSkin pool as normal raiders; goals and behavior are inherited.
public class RaiderBossEntity extends RaiderEntity {
    // Base zombie height is 1.95 blocks; 1.95 * SCALE ~= 2.5
    private static final double BOSS_SCALE = 2.5 / 1.95;

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
}
