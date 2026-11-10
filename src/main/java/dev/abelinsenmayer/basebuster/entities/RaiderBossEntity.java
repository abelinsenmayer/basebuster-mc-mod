package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.Config;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.Level;

// A beefier raider: rendered as a Husk and scaled up to roughly 2.5 blocks tall.
// Goals and behavior are inherited from RaiderEntity.
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
        return Config.RAIDER_BOSS_MAX_HEALTH.get();
    }
}
