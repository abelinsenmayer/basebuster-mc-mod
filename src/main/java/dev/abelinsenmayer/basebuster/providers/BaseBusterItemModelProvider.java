package dev.abelinsenmayer.basebuster.providers;

import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.registries.DeferredItem;

public class BaseBusterItemModelProvider extends ItemModelProvider {
    public BaseBusterItemModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, "basebuster", existingFileHelper);
    }

    @Override
    protected void registerModels() {
        withExistingParent(BaseBuster.BASE_FLAG_BLOCK_ITEM.getId().toString(), modLoc("block/base_flag_block"));
        handheldWithTexture(BaseBuster.STONE_REPAIR_HAMMER, "item/stone_shovel");
        handheldWithTexture(BaseBuster.IRON_REPAIR_HAMMER, "item/iron_shovel");
        handheldWithTexture(BaseBuster.DIAMOND_REPAIR_HAMMER, "item/diamond_shovel");
        withExistingParent(BaseBuster.RAIDER_SPAWN_EGG.getId().toString(), mcLoc("item/template_spawn_egg"));
    }

    private void handheldWithTexture(DeferredItem<?> item, String vanillaTexture) {
        withExistingParent(item.getId().toString(), mcLoc("item/handheld"))
                .texture("layer0", mcLoc(vanillaTexture));
    }
}
