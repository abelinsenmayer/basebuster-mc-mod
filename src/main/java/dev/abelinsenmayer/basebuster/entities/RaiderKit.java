package dev.abelinsenmayer.basebuster.entities;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.UUID;

/**
 * A raider's combat loadout: equipment, attack style, and base stats. Unlike
 * {@link RaiderSkin} (purely visual), the kit determines how the raider fights.
 * Kit data lives server-side; equipment slots are synced by vanilla, so the
 * client needs nothing extra.
 *
 * <p>Gear scales with {@link RaiderEntity#getRaidTier()}: each kit reads the
 * raider's tier inside {@link #equip} and hands out a stronger loadout at
 * higher tiers (noted per kit). Raiders spawned outside a raid stay at tier 1.
 */
public enum RaiderKit {
    /**
     * Melee frontliner: sword and shield, vanilla zombie stats. Tier 2 adds
     * full leather armor; tier 3 upgrades to a diamond sword; tier 4 upgrades
     * to full iron armor and Sharpness I; tier 5 raises the sword to
     * Sharpness III.
     */
    SWORD_AND_BOARD(18.0, 0.35, false, 0.0) {
        @Override
        public void equip(RaiderEntity raider, RandomSource random) {
            int tier = raider.getRaidTier();
            ItemStack sword = new ItemStack(tier >= 3
                    ? Items.DIAMOND_SWORD
                    : random.nextBoolean() ? Items.STONE_SWORD : Items.IRON_SWORD);
            if (tier >= 5) {
                enchant(raider, sword, Enchantments.SHARPNESS, 3);
            } else if (tier >= 4) {
                enchant(raider, sword, Enchantments.SHARPNESS, 1);
            }
            raider.setItemSlot(EquipmentSlot.MAINHAND, sword);
            raider.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            if (tier >= 4) {
                equipArmor(raider, Items.IRON_HELMET, Items.IRON_CHESTPLATE,
                        Items.IRON_LEGGINGS, Items.IRON_BOOTS);
            } else if (tier >= 2) {
                equipArmor(raider, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE,
                        Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
            }
        }
    },
    /**
     * Ranged support: bow and a leather helmet, vanilla skeleton stats. Tier 2
     * upgrades the helmet to full leather armor; tiers 3-5 add Power I-III to
     * the bow.
     */
    BOWMAN(14.0, 0.32, true, 0.0) {
        @Override
        public void equip(RaiderEntity raider, RandomSource random) {
            int tier = raider.getRaidTier();
            ItemStack bow = new ItemStack(Items.BOW);
            if (tier >= 3) {
                enchant(raider, bow, Enchantments.POWER, Math.min(tier - 2, 3));
            }
            raider.setItemSlot(EquipmentSlot.MAINHAND, bow);
            if (tier >= 2) {
                equipArmor(raider, Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE,
                        Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS);
            } else {
                raider.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
            }
        }
    },
    /**
     * Heavy bruiser: mace and armor, extra health, hard to stagger. Tier 2
     * adds Knockback I to the mace; tier 3 upgrades the base iron armor to
     * diamond; tier 4 adds Sharpness II to the mace; tier 5 grants permanent
     * Regeneration I.
     */
    TANK(20.0, 0.30, false, 0.8) {
        @Override
        public void equip(RaiderEntity raider, RandomSource random) {
            int tier = raider.getRaidTier();
            ItemStack mace = new ItemStack(Items.MACE);
            if (tier >= 2) {
                enchant(raider, mace, Enchantments.KNOCKBACK, 1);
            }
            if (tier >= 4) {
                enchant(raider, mace, Enchantments.SHARPNESS, 2);
            }
            raider.setItemSlot(EquipmentSlot.MAINHAND, mace);
            if (tier >= 3) {
                equipArmor(raider, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
                        Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
            } else {
                equipArmor(raider, Items.IRON_HELMET, Items.IRON_CHESTPLATE,
                        Items.IRON_LEGGINGS, Items.IRON_BOOTS);
            }
            if (tier >= 5) {
                raider.addEffect(new MobEffectInstance(
                        MobEffects.REGENERATION, MobEffectInstance.INFINITE_DURATION, 0));
            }
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

    /** Hands the raider this kit's equipment, scaled to its raid tier. */
    public abstract void equip(RaiderEntity raider, RandomSource random);

    /**
     * Enchants {@code stack} in place. Enchantments are datapack registries in
     * 1.21, so the holder is resolved through the raider's registry access; a
     * missing entry silently skips the enchant rather than failing the spawn.
     */
    private static void enchant(RaiderEntity raider, ItemStack stack,
                                ResourceKey<Enchantment> enchantment, int level) {
        raider.registryAccess().lookup(Registries.ENCHANTMENT)
                .flatMap(registry -> registry.get(enchantment))
                .ifPresent(holder -> stack.enchant(holder, level));
    }

    /** Fills all four armor slots with the given set. */
    private static void equipArmor(RaiderEntity raider, Item helmet, Item chestplate,
                                   Item leggings, Item boots) {
        raider.setItemSlot(EquipmentSlot.HEAD, new ItemStack(helmet));
        raider.setItemSlot(EquipmentSlot.CHEST, new ItemStack(chestplate));
        raider.setItemSlot(EquipmentSlot.LEGS, new ItemStack(leggings));
        raider.setItemSlot(EquipmentSlot.FEET, new ItemStack(boots));
    }

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
