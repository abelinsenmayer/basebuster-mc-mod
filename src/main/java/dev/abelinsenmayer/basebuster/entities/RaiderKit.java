package dev.abelinsenmayer.basebuster.entities;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * A raider's combat loadout: equipment, attack style, and base stats. Unlike
 * {@link RaiderSkin} (purely visual), the kit determines how the raider fights.
 * Kit data lives server-side; equipment slots are synced by vanilla, so the
 * client needs nothing extra.
 */
public enum RaiderKit {
    /** Melee frontliner: sword and shield, vanilla zombie stats. */
    SWORD_AND_BOARD(20.0, 0.23, false) {
        @Override
        public void equip(RaiderEntity raider, RandomSource random) {
            raider.setItemSlot(EquipmentSlot.MAINHAND,
                    new ItemStack(random.nextBoolean() ? Items.STONE_SWORD : Items.IRON_SWORD));
            raider.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        }
    },
    /** Ranged support: bow and a leather helmet, vanilla skeleton stats. */
    BOWMAN(16.0, 0.25, true) {
        @Override
        public void equip(RaiderEntity raider, RandomSource random) {
            raider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            raider.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        }
    };

    private final double maxHealth;
    private final double movementSpeed;
    private final boolean ranged;

    RaiderKit(double maxHealth, double movementSpeed, boolean ranged) {
        this.maxHealth = maxHealth;
        this.movementSpeed = movementSpeed;
        this.ranged = ranged;
    }

    public double maxHealth() {
        return this.maxHealth;
    }

    public double movementSpeed() {
        return this.movementSpeed;
    }

    /** Whether this kit fights at range; gates which attack goal runs. */
    public boolean isRanged() {
        return this.ranged;
    }

    /** Hands the raider this kit's equipment. */
    public abstract void equip(RaiderEntity raider, RandomSource random);

    public static RaiderKit random(RandomSource random) {
        RaiderKit[] kits = values();
        return kits[random.nextInt(kits.length)];
    }

    /**
     * A deterministic pseudo-random kit derived from the entity's UUID, used
     * as the fallback for entities that never got an explicit kit (mirrors
     * {@link RaiderSkin#forUuid}).
     */
    public static RaiderKit forUuid(UUID uuid) {
        RaiderKit[] kits = values();
        return kits[Math.floorMod(uuid.hashCode(), kits.length)];
    }
}
