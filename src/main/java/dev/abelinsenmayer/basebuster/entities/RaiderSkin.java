package dev.abelinsenmayer.basebuster.entities;

import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.loot.LootTable;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/**
 * A visual reskin for raiders: swaps the baked model and texture at render time
 * while the entity stays a {@link RaiderEntity}. Only model kinds that can be
 * instantiated for a Zombie subclass are supported.
 *
 * <p>Skins are grouped into {@link RaiderSkinTheme}s; raids pick one theme and
 * draw all member skins from it. This enum must stay free of client-only
 * classes (models, layers) so it can load on a dedicated server; the client
 * renderer maps {@link ModelKind} to baked model layers.
 */
public enum RaiderSkin {
    ZOMBIE(RaiderSkinTheme.UNDEAD, ModelKind.ZOMBIE, "textures/entity/zombie/zombie.png"),
    HUSK(RaiderSkinTheme.UNDEAD, ModelKind.ZOMBIE, "textures/entity/zombie/husk.png"),
    DROWNED(RaiderSkinTheme.UNDEAD, ModelKind.ZOMBIE, "textures/entity/zombie/drowned.png",
            "textures/entity/zombie/drowned_outer_layer.png"),
    ZOMBIE_VILLAGER(RaiderSkinTheme.UNDEAD, ModelKind.ZOMBIE_VILLAGER,
            "textures/entity/zombie_villager/zombie_villager.png"),
    SKELETON(RaiderSkinTheme.UNDEAD, ModelKind.SKELETON, "textures/entity/skeleton/skeleton.png"),
    STRAY(RaiderSkinTheme.UNDEAD, ModelKind.SKELETON, "textures/entity/skeleton/stray.png",
            "textures/entity/skeleton/stray_overlay.png"),
    WITHER_SKELETON(RaiderSkinTheme.UNDEAD, ModelKind.SKELETON,
            "textures/entity/skeleton/wither_skeleton.png"),
    BOGGED(RaiderSkinTheme.UNDEAD, ModelKind.BOGGED, "textures/entity/skeleton/bogged.png",
            "textures/entity/skeleton/bogged_overlay.png"),
//    ENDERMAN(RaiderSkinTheme.ENDER, ModelKind.ENDERMAN, "textures/entity/enderman/enderman.png"),
    PIGLIN(RaiderSkinTheme.PIGLIN, ModelKind.PIGLIN, "textures/entity/piglin/piglin.png"),
    PIGLIN_BRUTE(RaiderSkinTheme.PIGLIN, ModelKind.PIGLIN, "textures/entity/piglin/piglin_brute.png"),
    ZOMBIFIED_PIGLIN(RaiderSkinTheme.PIGLIN, ModelKind.PIGLIN,
            "textures/entity/piglin/zombified_piglin.png");

    private final RaiderSkinTheme theme;
    private final ModelKind model;
    private final ResourceLocation texture;
    // Death loot table: data/basebuster/loot_table/entities/raider/<skin>.json.
    // Kit drops are shared via the nested basebuster:entities/raider/kit table.
    private final ResourceKey<LootTable> lootTable;
    // Extra clothing/wear rendered over the base model (e.g. stray rags,
    // drowned clothes, bogged mushrooms); null for most skins
    @Nullable
    private final ResourceLocation overlay;

    RaiderSkin(RaiderSkinTheme theme, ModelKind model, String texture) {
        this(theme, model, texture, null);
    }

    RaiderSkin(RaiderSkinTheme theme, ModelKind model, String texture, @Nullable String overlay) {
        this.theme = theme;
        this.model = model;
        this.texture = ResourceLocation.withDefaultNamespace(texture);
        this.overlay = overlay != null ? ResourceLocation.withDefaultNamespace(overlay) : null;
        this.lootTable = ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.fromNamespaceAndPath(
                BaseBuster.MODID, "entities/raider/" + this.name().toLowerCase(Locale.ROOT)));
    }

    public RaiderSkinTheme theme() {
        return this.theme;
    }

    public ModelKind model() {
        return this.model;
    }

    public ResourceLocation texture() {
        return this.texture;
    }

    /** The overlay texture layered over the base model, or null if the skin has none. */
    @Nullable
    public ResourceLocation overlay() {
        return this.overlay;
    }

    /** The death loot table raiders wearing this skin roll on. */
    public ResourceKey<LootTable> lootTable() {
        return this.lootTable;
    }

    /** The skin at the given ordinal, or null when out of range. */
    @Nullable
    public static RaiderSkin byId(int id) {
        RaiderSkin[] values = values();
        return id >= 0 && id < values.length ? values[id] : null;
    }

    /**
     * A deterministic pseudo-random skin derived from the entity's UUID. Used
     * as the fallback for entities spawned without an explicit skin (spawn
     * eggs, /summon); the UUID is identical on server and client, so both pick
     * the same skin.
     */
    public static RaiderSkin forUuid(UUID uuid) {
        RaiderSkin[] values = values();
        return values[Math.floorMod(uuid.hashCode(), values.length)];
    }

    /**
     * The humanoid model family this skin renders with. Deliberately a plain
     * enum rather than a model reference so this class stays server-safe;
     * the client renderer maps each kind to a baked ModelLayerLocation.
     */
    public enum ModelKind {
        ZOMBIE,
        SKELETON,
        PIGLIN,
        ZOMBIE_VILLAGER,
//        ENDERMAN,
        BOGGED
    }
}
