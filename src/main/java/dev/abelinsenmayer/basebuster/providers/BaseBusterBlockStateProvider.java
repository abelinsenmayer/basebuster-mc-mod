package dev.abelinsenmayer.basebuster.providers;

import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

public class BaseBusterBlockStateProvider extends BlockStateProvider {
    public BaseBusterBlockStateProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, "basebuster", existingFileHelper);
    }

    @Override
    protected void registerStatesAndModels() {
        this.simpleBlock(BaseBuster.BASE_FLAG_BLOCK.get(), models().withExistingParent("base_flag_block", this.mcLoc("block/white_wool")));
    }
}
