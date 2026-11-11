package dev.abelinsenmayer.basebuster.providers;

import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.common.data.LanguageProvider;

public class BaseBusterLanguageProvider extends LanguageProvider {

    public BaseBusterLanguageProvider(PackOutput output) {
        super(
                // Provided by the `GatherDataEvent.Client`.
                output,
                // Your mod id.
                "basebuster",
                // The locale to use. You may use multiple language providers for different locales.
                "en_us"
        );
    }

    @Override
    protected void addTranslations() {
        this.addBlock(BaseBuster.BASE_FLAG_BLOCK, "Base Flag");
        this.addItem(BaseBuster.STONE_REPAIR_HAMMER, "Stone Repair Hammer");
        this.addItem(BaseBuster.IRON_REPAIR_HAMMER, "Iron Repair Hammer");
        this.addItem(BaseBuster.DIAMOND_REPAIR_HAMMER, "Diamond Repair Hammer");
        this.addEntityType(BaseBuster.RAIDER, "Raider");
        this.addItem(BaseBuster.RAIDER_SPAWN_EGG, "Raider Spawn Egg");
        this.addEntityType(BaseBuster.RAIDER_BOSS, "Raider Boss");
        this.addItem(BaseBuster.RAIDER_BOSS_SPAWN_EGG, "Raider Boss Spawn Egg");
        // Banner items delegate their description id to the standing block
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER1, "Victory Banner (Tier 1)");
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER2, "Victory Banner (Tier 2)");
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER3, "Victory Banner (Tier 3)");
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER4, "Victory Banner (Tier 4)");
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER5, "Victory Banner (Tier 5)");
    }
}
