package dev.abelinsenmayer.basebuster;

import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    public static final ModConfigSpec.BooleanValue RAIDER_TUNNELING_ENABLED = BUILDER
            .comment("Whether raiders may plan paths through breakable blocks and dig through them")
            .define("raiderTunneling.enabled", true);

    public static final ModConfigSpec.DoubleValue RAIDER_BOSS_BONUS_HEALTH = BUILDER
            .comment("Extra health a raider boss gets on top of its kit's max health")
            .defineInRange("raiderBoss.bonusHealth", 100.0, 0.0, 1024.0);

    public static final ModConfigSpec.DoubleValue RAIDER_SQUAD_SEARCH_RADIUS = BUILDER
            .comment("Distance a raider will search for a raider boss to elect as its squad leader")
            .defineInRange("raiderSquad.searchRadius", 48.0, 1.0, 256.0);

    public static final ModConfigSpec.DoubleValue RAIDER_FLEE_SPEED_MULTIPLIER = BUILDER
            .comment("Movement speed multiplier applied to a raider while it is fleeing")
            .defineInRange("raiderFlee.speedMultiplier", 1.3, 1.0, 10.0);

    public static final ModConfigSpec.IntValue FLAG_CAPTURE_SECONDS = BUILDER
            .comment("Seconds for a single raider to capture a base flag")
            .defineInRange("flagCapture.seconds", 30, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue FLAG_CAPTURE_SPEED_BONUS = BUILDER
            .comment("Capture speed multiplier added for each raider participating in the capture")
            .defineInRange("flagCapture.speedBonusPerRaider", 0.1, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue FLAG_CAPTURE_MAX_SPEED = BUILDER
            .comment("Maximum capture speed multiplier regardless of raider count")
            .defineInRange("flagCapture.maxSpeedMultiplier", 2.0, 1.0, 10.0);

    public static final ModConfigSpec.DoubleValue FLAG_CAPTURE_PLAYER_PROGRESS_PER_CLICK = BUILDER
            .comment("Fraction of capture progress a player gains per right-click on a flag they don't own")
            .defineInRange("flagCapture.playerProgressPerClick", 0.05, 0.001, 1.0);

    static final ModConfigSpec SPEC = BUILDER.build();

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }
}
