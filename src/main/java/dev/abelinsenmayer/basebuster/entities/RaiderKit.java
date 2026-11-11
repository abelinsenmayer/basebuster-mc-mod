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
    SWORD_AND_BOARD(20.0, 0.23, false, 0.0) {
        @Override
        public void equip(RaiderEntity raider, RandomSource random) {
            raider.setItemSlot(EquipmentSlot.MAINHAND,
                    new ItemStack(random.nextBoolean() ? Items.STONE_SWORD : Items.IRON_SWORD));
            raider.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        }
    },
    /** Ranged support: bow and a leather helmet, vanilla skeleton stats. */
    BOWMAN(16.0, 0.25, true, 0.0) {
        @Override
        public void equip(RaiderEntity raider, RandomSource random) {
            raider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
            raider.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        }
    },
    /** Heavy bruiser: mace and full iron armor, extra health, hard to stagger. */
    TANK(26.0, 0.20, false, 0.8) {
        @Override
        public void equip(RaiderEntity raider, RandomSource random) {
            raider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.MACE));
            raider.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            raider.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            raider.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
            raider.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        }
    };

    private final double maxHealth;
    private final double movementSpeed;
    private final boolean ranged;
    private final double knockbackResistance;

    RaiderKit(double maxHealth, double movementSpeed, boolean ranged, double knockbackResistance) {
        this.maxHealth = maxHealth;
        this.movementSpeed = movementSpeed;
        this.ranged = ranged;
        this.knockbackResistance = knockbackResistance;
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

    /** Fraction of incoming knockback this kit ignores (0 = none, 1 = immune). */
    public double knockbackResistance() {
        return this.knockbackResistance;
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
