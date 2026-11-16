package dev.abelinsenmayer.basebuster.providers;

import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;

import java.util.concurrent.CompletableFuture;

public class BaseBusterRecipeProvider extends RecipeProvider {
    public BaseBusterRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput recipeOutput) {
        // Wooden sword tip on two sticks down the middle column, any-color
        // wool filling the side columns of the top two rows
        ShapedRecipeBuilder.shaped(RecipeCategory.MISC, BaseBuster.BASE_FLAG_BLOCK_ITEM.get())
                .pattern("WSW")
                .pattern("W#W")
                .pattern(" # ")
                .define('S', Items.WOODEN_SWORD)
                .define('#', Items.STICK)
                .define('W', ItemTags.WOOL)
                .unlockedBy("has_wool", has(ItemTags.WOOL))
                .save(recipeOutput);
    }
}
