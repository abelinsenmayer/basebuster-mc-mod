package dev.abelinsenmayer.basebuster.providers;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.registries.DeferredBlock;

public class BaseBusterBlockStateProvider extends BlockStateProvider {
    public BaseBusterBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, "basebuster", existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        // The flag borrows vanilla wool textures: white while owned, red once captured
        var uncaptured = models().withExistingParent("base_flag_block", this.mcLoc("block/white_wool"));
        var captured = models().withExistingParent("base_flag_block_captured", this.mcLoc("block/red_wool"));
        this.getVariantBuilder(BaseBuster.BASE_FLAG_BLOCK.get())
                .forAllStates(state -> ConfiguredModel.builder()
                        .modelFile(state.getValue(BaseFlagBlock.CAPTURED) ? captured : uncaptured)
                        .build());

        // Victory banners render through the banner block entity renderer, so
        // every variant points at the vanilla block/banner model like vanilla's
        // own banner blockstates
        bannerStates(BaseBuster.VICTORY_BANNER_TIER1, BaseBuster.VICTORY_WALL_BANNER_TIER1);
        bannerStates(BaseBuster.VICTORY_BANNER_TIER2, BaseBuster.VICTORY_WALL_BANNER_TIER2);
        bannerStates(BaseBuster.VICTORY_BANNER_TIER3, BaseBuster.VICTORY_WALL_BANNER_TIER3);
        bannerStates(BaseBuster.VICTORY_BANNER_TIER4, BaseBuster.VICTORY_WALL_BANNER_TIER4);
        bannerStates(BaseBuster.VICTORY_BANNER_TIER5, BaseBuster.VICTORY_WALL_BANNER_TIER5);
    }

    private void bannerStates(DeferredBlock<?>... banners) {
        ModelFile bannerModel = new ModelFile.UncheckedModelFile(mcLoc("block/banner"));
        for (DeferredBlock<?> banner : banners) {
            this.getVariantBuilder(banner.get())
                    .forAllStates(state -> ConfiguredModel.builder()
                            .modelFile(bannerModel)
                            .build());
        }
    }
}
