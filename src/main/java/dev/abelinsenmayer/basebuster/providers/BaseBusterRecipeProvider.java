package dev.abelinsenmayer.basebuster.providers;

import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.InventoryChangeTrigger;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;

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

        repairHammer(recipeOutput, BaseBuster.STONE_REPAIR_HAMMER.get(),
                Ingredient.of(ItemTags.STONE_TOOL_MATERIALS),
                "has_stone_tool_material", has(ItemTags.STONE_TOOL_MATERIALS));
        repairHammer(recipeOutput, BaseBuster.IRON_REPAIR_HAMMER.get(),
                Ingredient.of(Items.IRON_INGOT),
                "has_iron_ingot", has(Items.IRON_INGOT));
        repairHammer(recipeOutput, BaseBuster.DIAMOND_REPAIR_HAMMER.get(),
                Ingredient.of(Items.DIAMOND),
                "has_diamond", has(Items.DIAMOND));
    }

    // Two head materials across the top and a single stick below -- a hoe
    // pattern minus its second stick, keeping hammers cheap early game
    private static void repairHammer(RecipeOutput recipeOutput, ItemLike result, Ingredient head,
                                     String unlockName, Criterion<InventoryChangeTrigger.TriggerInstance> unlock) {
        ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, result)
                .pattern("HH")
                .pattern(" #")
                .define('H', head)
                .define('#', Items.STICK)
                .unlockedBy(unlockName, unlock)
                .save(recipeOutput);
    }
}
