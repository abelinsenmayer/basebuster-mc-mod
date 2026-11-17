package dev.abelinsenmayer.basebuster.providers;

import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.registries.DeferredItem;

public class BaseBusterItemModelProvider extends ItemModelProvider {
    public BaseBusterItemModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, "basebuster", existingFileHelper);
    }

    @Override
    protected void registerModels() {
        // The flag is a banner block: its item renders through
        // BlockEntityWithoutLevelRenderer like the victory banners
        bannerItem(BaseBuster.BASE_FLAG_BLOCK_ITEM);
        handheldItem(BaseBuster.STONE_REPAIR_HAMMER);
        handheldItem(BaseBuster.IRON_REPAIR_HAMMER);
        handheldItem(BaseBuster.DIAMOND_REPAIR_HAMMER);
        withExistingParent(BaseBuster.RAIDER_SPAWN_EGG.getId().toString(), mcLoc("item/template_spawn_egg"));
        withExistingParent(BaseBuster.RAIDER_BOSS_SPAWN_EGG.getId().toString(), mcLoc("item/template_spawn_egg"));
        withExistingParent(BaseBuster.RAIDER_VINDICATOR_SPAWN_EGG.getId().toString(), mcLoc("item/template_spawn_egg"));
        withExistingParent(BaseBuster.RAIDER_PILLAGER_SPAWN_EGG.getId().toString(), mcLoc("item/template_spawn_egg"));
        withExistingParent(BaseBuster.RAIDER_EVOKER_SPAWN_EGG.getId().toString(), mcLoc("item/template_spawn_egg"));
        withExistingParent(BaseBuster.RAIDER_RAVAGER_SPAWN_EGG.getId().toString(), mcLoc("item/template_spawn_egg"));
        withExistingParent(BaseBuster.RAIDER_RAVAGER_BOSS_SPAWN_EGG.getId().toString(), mcLoc("item/template_spawn_egg"));
        // Test tool disguised as rotten flesh
        withExistingParent(BaseBuster.RAID_SPAWNER.getId().toString(), mcLoc("item/rotten_flesh"));
        // Banner items render through BlockEntityWithoutLevelRenderer; vanilla's
        // template_banner wraps builtin/entity with the display transforms that
        // keep the model inside inventory slots and hands
        bannerItem(BaseBuster.VICTORY_BANNER_TIER1_ITEM);
        bannerItem(BaseBuster.VICTORY_BANNER_TIER2_ITEM);
        bannerItem(BaseBuster.VICTORY_BANNER_TIER3_ITEM);
        bannerItem(BaseBuster.VICTORY_BANNER_TIER4_ITEM);
        bannerItem(BaseBuster.VICTORY_BANNER_TIER5_ITEM);
    }

    private void bannerItem(DeferredItem<?> item) {
        getBuilder(item.getId().toString())
                .parent(new ModelFile.UncheckedModelFile(mcLoc("item/template_banner")));
    }

    private void handheldItem(DeferredItem<?> item) {
        withExistingParent(item.getId().toString(), mcLoc("item/handheld"))
                .texture("layer0", modLoc("item/" + item.getId().getPath()));
    }
}
