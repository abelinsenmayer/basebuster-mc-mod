package dev.abelinsenmayer.basebuster.raids;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.entities.IRaider;
import dev.abelinsenmayer.basebuster.entities.IRaiderBoss;
import dev.abelinsenmayer.basebuster.entities.RaiderBossEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderKit;
import dev.abelinsenmayer.basebuster.entities.RaiderSkin;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;

import javax.annotation.Nullable;

/**
 * A raider faction: which species makes up a raid party, chosen by
 * {@code raidSpawning.faction}. The faction owns the last few entity-specific
 * decisions in an otherwise generic spawn pipeline: it instantiates bosses and
 * members and applies any per-species setup (undead skins and kits; illagers
 * arrive with their own native look and equipment). Everything after creation
 * -- spots, routes, raid metadata, squad leaders -- is interface-typed and
 * faction-agnostic.
 *
 * <p>A faction is also a flag identity: when its raiders capture a base flag,
 * the flag records this enum as its owner (the name shown for the territory
 * comes from {@link #displayName}) and hoists the faction's standard --
 * {@link #baseFlagColor} plus {@link #baseFlagPatterns}. The victory banner a
 * defeated raid drops is that same standard crossed out with a red X (see
 * {@link #victoryBannerPatterns}).
 */
public enum Faction {
    /**
     * The original raiders: {@link RaiderBossEntity} leaders and
     * {@link RaiderEntity} members wearing the generic zombie skin, kitted
     * per member slot. Their captured flags fly a tattered gray standard
     * under a black skull.
     */
    UNDEAD {
        @Override
        public DyeColor baseFlagColor() {
            return DyeColor.GRAY;
        }

        @Override
        public BannerPatternLayers baseFlagPatterns(HolderLookup.Provider registries) {
            HolderGetter<BannerPattern> patterns = registries.lookupOrThrow(Registries.BANNER_PATTERN);
            return new BannerPatternLayers.Builder()
                    .add(patterns.getOrThrow(BannerPatterns.TRIANGLES_BOTTOM), DyeColor.LIGHT_GRAY)
                    .add(patterns.getOrThrow(BannerPatterns.TRIANGLES_TOP), DyeColor.LIGHT_GRAY)
                    .add(patterns.getOrThrow(BannerPatterns.BORDER), DyeColor.BLACK)
                    .add(patterns.getOrThrow(BannerPatterns.SKULL), DyeColor.BLACK)
                    .build();
        }

        @Override
        @Nullable
        public IRaiderBoss createBoss(ServerLevel level) {
            RaiderBossEntity boss = BaseBuster.RAIDER_BOSS.get().create(level);
            if (boss != null) {
                // Restore a per-raid RaiderSkinTheme draw when varied skins come back
                boss.setSkin(RaiderSkin.ZOMBIE);
            }
            return boss;
        }

        @Override
        @Nullable
        public IRaider createMember(ServerLevel level, int slot) {
            RaiderEntity raider = BaseBuster.RAIDER.get().create(level);
            if (raider != null) {
                // Kit assignment happens at create time so finalizeSpawn's
                // equip (run later by spawnMember) picks the slot's loadout
                raider.setKit(MEMBER_KITS[slot]);
                raider.setSkin(RaiderSkin.ZOMBIE);
            }
            return raider;
        }
    },
    /**
     * Vanilla illagers: ravager bosses leading vindicator, pillager, and
     * evoker members. No kits -- each species fights with its native style.
     * Their captured flags fly the ominous standard on red.
     */
    ILLAGER {
        @Override
        public DyeColor baseFlagColor() {
            return DyeColor.RED;
        }

        @Override
        public BannerPatternLayers baseFlagPatterns(HolderLookup.Provider registries) {
            // Pulled from the same stack raid captains wear, so captured
            // flags and captain banners always match
            return Raid.getLeaderBannerInstance(registries.lookupOrThrow(Registries.BANNER_PATTERN))
                    .getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        }

        @Override
        @Nullable
        public IRaiderBoss createBoss(ServerLevel level) {
            return BaseBuster.RAIDER_RAVAGER_BOSS.get().create(level);
        }

        @Override
        @Nullable
        public IRaider createMember(ServerLevel level, int slot) {
            // Member columns mirror the zombie kits: ranged first like
            // BOWMAN, melee frontline like SWORD_AND_BOARD, the heavy slot
            // like TANK -- which evokers fill as the faction's spellcaster
            return switch (slot) {
                case 0 -> BaseBuster.RAIDER_PILLAGER.get().create(level);
                case 1 -> BaseBuster.RAIDER_VINDICATOR.get().create(level);
                default -> BaseBuster.RAIDER_EVOKER.get().create(level);
            };
        }
    };

    // Kits assigned to the member columns of RaidSpawner's composition
    // table, in order (undead faction only)
    private static final RaiderKit[] MEMBER_KITS = {RaiderKit.BOWMAN, RaiderKit.SWORD_AND_BOARD, RaiderKit.TANK};

    /**
     * The faction whose persisted name is {@code name}, or null for an
     * unknown one. Flags captured before factions existed wrote the string
     * "RAIDERS", which maps to the illager faction that captured them.
     */
    @Nullable
    public static Faction byName(@Nullable String name) {
        if ("RAIDERS".equals(name)) {
            return ILLAGER;
        }
        if (name == null) {
            return null;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The name shown wherever this faction owns territory -- captured-flag
     * displays, raid-omen announcements, flag info. Also written to NBT on
     * captured flags, so name doubles as the persisted id.
     */
    public String displayName() {
        return name();
    }

    /** The base color of the flag this faction hoists over captured bases. */
    public abstract DyeColor baseFlagColor();

    /** The pattern layers of the flag this faction hoists over captured bases. */
    public abstract BannerPatternLayers baseFlagPatterns(HolderLookup.Provider registries);

    /**
     * The faction's standard crossed out in red: the victory banner's pattern
     * is the base flag's pattern with an X on top, as though the captured
     * flag was struck through.
     */
    public BannerPatternLayers victoryBannerPatterns(HolderLookup.Provider registries) {
        BannerPatternLayers.Builder builder = new BannerPatternLayers.Builder();
        for (BannerPatternLayers.Layer layer : baseFlagPatterns(registries).layers()) {
            builder.add(layer.pattern(), layer.color());
        }
        builder.add(
                registries.lookupOrThrow(Registries.BANNER_PATTERN).getOrThrow(BannerPatterns.CROSS),
                DyeColor.RED);
        return builder.build();
    }

    /** Creates a boss for this faction's raid, or null if creation failed. */
    @Nullable
    public abstract IRaiderBoss createBoss(ServerLevel level);

    /**
     * Creates a member for the given composition slot (0-based, matching the
     * member columns after the boss column), or null if creation failed.
     */
    @Nullable
    public abstract IRaider createMember(ServerLevel level, int slot);
}
