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
        this.add("itemGroup.basebuster", "Base Buster");

        this.add("basebuster.configuration.title", "Base Buster Configs");
        this.add("basebuster.configuration.section.basebuster.common.toml", "Base Buster Configs");
        this.add("basebuster.configuration.section.basebuster.common.toml.title", "Base Buster Configs");

        this.addBlock(BaseBuster.BASE_FLAG_BLOCK, "Base Flag");
        this.addItem(BaseBuster.STONE_REPAIR_HAMMER, "Stone Repair Hammer");
        this.addItem(BaseBuster.IRON_REPAIR_HAMMER, "Iron Repair Hammer");
        this.addItem(BaseBuster.DIAMOND_REPAIR_HAMMER, "Diamond Repair Hammer");
        this.addEntityType(BaseBuster.RAIDER, "Raider");
        this.addItem(BaseBuster.RAIDER_SPAWN_EGG, "Raider Spawn Egg");
        this.addEntityType(BaseBuster.RAIDER_BOSS, "Raider Boss");
        this.addItem(BaseBuster.RAIDER_BOSS_SPAWN_EGG, "Raider Boss Spawn Egg");
        this.addEntityType(BaseBuster.RAIDER_VINDICATOR, "Vindicator Raider");
        this.addItem(BaseBuster.RAIDER_VINDICATOR_SPAWN_EGG, "Vindicator Raider Spawn Egg");
        this.addEntityType(BaseBuster.RAIDER_PILLAGER, "Pillager Raider");
        this.addItem(BaseBuster.RAIDER_PILLAGER_SPAWN_EGG, "Pillager Raider Spawn Egg");
        this.addEntityType(BaseBuster.RAIDER_EVOKER, "Evoker Raider");
        this.addItem(BaseBuster.RAIDER_EVOKER_SPAWN_EGG, "Evoker Raider Spawn Egg");
        this.addEntityType(BaseBuster.RAIDER_RAVAGER, "Ravager Raider");
        this.addItem(BaseBuster.RAIDER_RAVAGER_SPAWN_EGG, "Ravager Raider Spawn Egg");
        this.addEntityType(BaseBuster.RAIDER_RAVAGER_BOSS, "Ravager Raider Boss");
        this.addItem(BaseBuster.RAIDER_RAVAGER_BOSS_SPAWN_EGG, "Ravager Raider Boss Spawn Egg");
        this.addItem(BaseBuster.RAID_SPAWNER, "Raid Spawner");
        this.addItem(BaseBuster.FACTION_PLEDGE, "Faction Pledge");
        this.add("gui.basebuster.faction_pledge", "Faction Pledge");
        this.add("gui.basebuster.faction_pledge.name_hint", "[Enter your faction name]");
        this.add("gui.basebuster.faction_pledge.faction_color", "Faction color");
        this.add("gui.basebuster.faction_pledge.friendly_fire", "Allow friendly fire?");
        this.add("gui.basebuster.faction_pledge.hide_nametags", "Hide nametags from non-members?");
        this.add("gui.basebuster.faction_pledge.show_prefix", "Show faction name prefix?");
        this.add("gui.basebuster.faction_pledge.allow_uninvited", "Allow uninvited members to join at your flag?");
        this.add("gui.basebuster.faction_pledge.create", "Create Faction");
        this.add("gui.basebuster.faction_pledge.edit_title", "Faction Settings");
        this.add("gui.basebuster.faction_pledge.save", "Save");
        this.add("gui.basebuster.faction_pledge.updated", "Faction '%s' updated.");
        this.add("gui.basebuster.faction_pledge.updated_broadcast", "%s changed your faction's configuration.");
        this.add("gui.basebuster.faction_pledge.not_member", "Only the flag's owning team can edit its faction.");
        this.add("gui.basebuster.faction_pledge.name_taken", "That faction name is already taken.");
        this.add("gui.basebuster.faction_pledge.name_invalid", "Invalid faction name.");
        this.add("gui.basebuster.faction_pledge.created", "Faction '%s' created!");
        this.add("item.basebuster.faction_pledge.unbound_hint_bind", "Right-click: bind to your current faction");
        this.add("item.basebuster.faction_pledge.unbound_hint_create", "Sneak + right-click: create a new faction");
        this.add("item.basebuster.faction_pledge.bound_to", "Bound to faction: %s");
        this.add("item.basebuster.faction_pledge.bound_hint_adopt", "Right-click: join this faction");
        this.add("item.basebuster.faction_pledge.bound_hint_leave", "Throw into fire/lava: renounce your faction");
        this.add("item.basebuster.faction_pledge.bound_hint_create", "Sneak + right-click: create a new faction");
        this.add("item.basebuster.faction_pledge.no_such_faction", "The faction '%s' no longer exists.");
        this.add("item.basebuster.faction_pledge.joined", "You joined faction '%s'!");
        this.add("item.basebuster.faction_pledge.left", "By burning a faction pledge for your faction, you have renounced your loyalty to '%s'.");
        // Banner items delegate their description id to the standing block
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER1, "Victory Banner (Tier 1)");
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER2, "Victory Banner (Tier 2)");
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER3, "Victory Banner (Tier 3)");
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER4, "Victory Banner (Tier 4)");
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER5, "Victory Banner (Tier 5)");
    }
}
