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
        this.addItem(BaseBuster.FACTION_FOUNDING_DOCUMENT, "Faction Founding Document");
        this.add("item.basebuster.faction_founding_document.hint_create", "Right-click: found a new faction");
        this.addItem(BaseBuster.FACTION_PLEDGE, "Faction Pledge");
        this.add("gui.basebuster.faction_pledge", "Faction Founding Document");
        this.add("gui.basebuster.faction_pledge.name_hint", "[Enter your faction name]");
        this.add("gui.basebuster.faction_pledge.faction_color", "Faction color");
        this.add("gui.basebuster.faction_pledge.friendly_fire", "Allow friendly fire?");
        this.add("gui.basebuster.faction_pledge.hide_nametags", "Hide nametags from non-members?");
        this.add("gui.basebuster.faction_pledge.show_prefix", "Show faction name prefix?");
        this.add("gui.basebuster.faction_pledge.create", "Create Faction");
        this.add("gui.basebuster.faction_pledge.edit_title", "Faction Settings");
        this.add("gui.basebuster.faction_pledge.save", "Save");
        this.add("gui.basebuster.faction_pledge.updated", "Faction '%s' updated.");
        this.add("gui.basebuster.faction_pledge.updated_broadcast", "%s changed your faction's configuration.");
        this.add("gui.basebuster.faction_pledge.not_member", "Only the flag's owning team can edit its faction.");
        this.add("gui.basebuster.faction_pledge.name_taken", "That faction name is already taken.");
        this.add("gui.basebuster.faction_pledge.name_invalid", "Invalid faction name.");
        this.add("gui.basebuster.faction_pledge.no_document", "You need a Faction Founding Document in hand to create a faction.");
        this.add("gui.basebuster.faction_pledge.created", "Faction '%s' created!");
        this.add("gui.basebuster.base_management.title", "Base Settings");
        this.add("gui.basebuster.base_management.name_hint", "[Enter base name]");
        this.add("gui.basebuster.base_management.show_border", "Show base border?");
        this.add("gui.basebuster.base_management.save", "Save");
        this.add("gui.basebuster.base_management.level", "Base Level: %s");
        this.add("gui.basebuster.base_management.trade_discount", "Trade Discount: %s%%");
        this.add("gui.basebuster.base_management.saved", "Base settings saved.");
        this.add("gui.basebuster.base_management.not_member", "Only the flag's owning team can edit its base.");
        this.add("gui.basebuster.base_management.name_taken", "Your team already has a base named '%s'.");
        this.add("gui.basebuster.base_management.name_invalid", "Invalid base name.");
        this.add("gui.basebuster.base_management.banish", "Banish");
        this.add("gui.basebuster.base_management.citizens", "Citizens: %s/%s");
        this.add("gui.basebuster.repairs.title", "Repairs");
        this.add("gui.basebuster.repairs.column_block", "Block");
        this.add("gui.basebuster.repairs.column_location", "Location");
        this.add("gui.basebuster.repairs.column_obstruction", "Obstruction");
        this.add("gui.basebuster.repairs.repair_all", "Repair all");
        this.add("gui.basebuster.repairs.abandon", "Abandon");
        this.add("gui.basebuster.repairs.abandon_all", "Abandon all");
        this.add("gui.basebuster.repairs.repair", "Repair");
        this.add("item.basebuster.faction_pledge.unbound_hint_bind", "Right-click: bind to your current faction");
        this.add("item.basebuster.faction_pledge.hint_villager", "Right-click a villager: issue a citizenship certificate");
        this.add("item.basebuster.faction_pledge.bound_to", "Bound to faction: %s");
        this.add("item.basebuster.faction_pledge.bound_hint_adopt", "Right-click: join this faction");
        this.add("item.basebuster.faction_pledge.bound_hint_leave", "Throw into fire/lava: renounce your faction");
        this.add("item.basebuster.faction_pledge.bound_hint_clear", "Sneak + right-click: clear the bound faction");
        this.add("item.basebuster.faction_pledge.no_such_faction", "The faction '%s' no longer exists.");
        this.add("item.basebuster.faction_pledge.joined", "You joined faction '%s'!");
        this.add("item.basebuster.faction_pledge.left", "By burning a faction pledge for your faction, you have renounced your loyalty to '%s'.");
        this.addItem(BaseBuster.CITIZENSHIP_CERTIFICATE, "Citizenship Certificate");
        this.add("item.basebuster.citizenship_certificate.bound_to", "Bound villager: %s");
        this.add("item.basebuster.citizenship_certificate.profession", "Profession: %s");
        this.add("item.basebuster.citizenship_certificate.hint_place", "Right-click the ground inside a team's base to release");
        this.add("item.basebuster.citizenship_certificate.no_base", "The villager can only be released inside a team-owned flag's protection area.");
        this.add("item.basebuster.citizenship_certificate.base_full", "This base's level can't support any more citizens.");
        this.add("item.basebuster.citizenship_certificate.placed", "%s has joined '%s' as a citizen!");
        // Banner items delegate their description id to the standing block
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER1, "Victory Banner (Tier 1)");
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER2, "Victory Banner (Tier 2)");
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER3, "Victory Banner (Tier 3)");
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER4, "Victory Banner (Tier 4)");
        this.addBlock(BaseBuster.VICTORY_BANNER_TIER5, "Victory Banner (Tier 5)");
    }
}
