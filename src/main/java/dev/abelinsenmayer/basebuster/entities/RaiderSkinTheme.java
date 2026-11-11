package dev.abelinsenmayer.basebuster.entities;

import net.minecraft.util.RandomSource;

import java.util.Arrays;
import java.util.List;

/**
 * A group of visually related {@link RaiderSkin}s. Each raid picks one theme
 * and draws every member's skin from it, so a raid party looks cohesive.
 */
public enum RaiderSkinTheme {
    UNDEAD,
    PIGLIN;
//    ENDER;

    /** All skins belonging to this theme. */
    public List<RaiderSkin> skins() {
        return Arrays.stream(RaiderSkin.values())
                .filter(skin -> skin.theme() == this)
                .toList();
    }

    /** A uniformly random skin from this theme. */
    public RaiderSkin randomSkin(RandomSource random) {
        List<RaiderSkin> skins = this.skins();
        return skins.get(random.nextInt(skins.size()));
    }

    /** A uniformly random theme. */
    public static RaiderSkinTheme random(RandomSource random) {
        RaiderSkinTheme[] values = values();
        return values[random.nextInt(values.length)];
    }
}
