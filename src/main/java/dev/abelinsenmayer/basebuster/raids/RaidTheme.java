package dev.abelinsenmayer.basebuster.raids;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.entities.IRaider;
import dev.abelinsenmayer.basebuster.entities.IRaiderBoss;
import dev.abelinsenmayer.basebuster.entities.RaiderBossEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderKit;
import dev.abelinsenmayer.basebuster.entities.RaiderSkin;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * Which species makes up a raid party, chosen by {@code raidSpawning.theme}.
 * The theme owns the last few entity-specific decisions in an otherwise
 * generic spawn pipeline: it instantiates bosses and members and applies any
 * per-species setup (zombie skins and kits; illagers arrive with their own
 * native look and equipment). Everything after creation -- spots, routes,
 * raid metadata, squad leaders -- is interface-typed and theme-agnostic.
 */
public enum RaidTheme {
    /**
     * The original raiders: {@link RaiderBossEntity} leaders and
     * {@link RaiderEntity} members wearing the generic zombie skin, kitted
     * per member slot.
     */
    ZOMBIE {
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
     */
    ILLAGER {
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
            // like TANK -- which evokers fill as the theme's spellcaster
            return switch (slot) {
                case 0 -> BaseBuster.RAIDER_PILLAGER.get().create(level);
                case 1 -> BaseBuster.RAIDER_VINDICATOR.get().create(level);
                default -> BaseBuster.RAIDER_EVOKER.get().create(level);
            };
        }
    };

    // Kits assigned to the member columns of RaidSpawner's composition
    // table, in order (zombie theme only)
    private static final RaiderKit[] MEMBER_KITS = {RaiderKit.BOWMAN, RaiderKit.SWORD_AND_BOARD, RaiderKit.TANK};

    /** Creates a boss for this theme's raid, or null if creation failed. */
    @Nullable
    public abstract IRaiderBoss createBoss(ServerLevel level);

    /**
     * Creates a member for the given composition slot (0-based, matching the
     * member columns after the boss column), or null if creation failed.
     */
    @Nullable
    public abstract IRaider createMember(ServerLevel level, int slot);
}
