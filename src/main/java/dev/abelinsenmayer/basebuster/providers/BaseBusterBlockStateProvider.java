package dev.abelinsenmayer.basebuster.providers;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

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
    }
}
