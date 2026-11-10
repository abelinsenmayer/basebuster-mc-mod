package dev.abelinsenmayer.basebuster;

import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderBossEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderEntity;
import dev.abelinsenmayer.basebuster.events.BaseBusterEventBus;
import dev.abelinsenmayer.basebuster.items.RepairHammerItem;
import dev.abelinsenmayer.basebuster.providers.BaseBusterBlockStateProvider;
import dev.abelinsenmayer.basebuster.providers.BaseBusterItemModelProvider;
import dev.abelinsenmayer.basebuster.providers.BaseBusterLanguageProvider;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(BaseBuster.MODID)
public class BaseBuster {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "basebuster";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "basebuster" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "basebuster" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "basebuster" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, BaseBuster.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, BaseBuster.MODID);

    // Creates a new Block with the id "basebuster:example_block", combining the namespace and path
    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    // Creates a new BlockItem with the id "basebuster:example_block", combining the namespace and path
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);

    public static final DeferredBlock<BaseFlagBlock> BASE_FLAG_BLOCK = BLOCKS.register("base_flag_block", () -> new BaseFlagBlock(BlockBehaviour.Properties.of()));
    public static final DeferredItem<BlockItem> BASE_FLAG_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("base_flag_block", BASE_FLAG_BLOCK);

    // Repair hammers: durability and mining ability match pickaxes of the same tier
    public static final DeferredItem<RepairHammerItem> STONE_REPAIR_HAMMER = ITEMS.register("stone_repair_hammer", () -> new RepairHammerItem(Tiers.STONE, new Item.Properties()));
    public static final DeferredItem<RepairHammerItem> IRON_REPAIR_HAMMER = ITEMS.register("iron_repair_hammer", () -> new RepairHammerItem(Tiers.IRON, new Item.Properties()));
    public static final DeferredItem<RepairHammerItem> DIAMOND_REPAIR_HAMMER = ITEMS.register("diamond_repair_hammer", () -> new RepairHammerItem(Tiers.DIAMOND, new Item.Properties()));

    // The raider entity: behaves like a zombie but does not burn in sunlight.
    // Renders as one of its RaiderSkins (themed per raid, random for egg/summon spawns)
    public static final Supplier<EntityType<RaiderEntity>> RAIDER = ENTITY_TYPES.register("raider",
            () -> EntityType.Builder.of(RaiderEntity::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.74F)
                    .clientTrackingRange(8)
                    .build("raider"));
    public static final DeferredItem<DeferredSpawnEggItem> RAIDER_SPAWN_EGG = ITEMS.register("raider_spawn_egg",
            () -> new DeferredSpawnEggItem(RAIDER, 0x365A36, 0x8B5A2B, new Item.Properties()));

    // The raider boss: same skin pool as raiders, scaled to about 2.5 blocks tall, with far more HP.
    // Dimensions are the base zombie size; the entity applies the SCALE attribute itself on construction.
    public static final Supplier<EntityType<RaiderBossEntity>> RAIDER_BOSS = ENTITY_TYPES.register("raider_boss",
            () -> EntityType.Builder.of(RaiderBossEntity::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.74F)
                    .clientTrackingRange(8)
                    .build("raider_boss"));
    public static final DeferredItem<DeferredSpawnEggItem> RAIDER_BOSS_SPAWN_EGG = ITEMS.register("raider_boss_spawn_egg",
            () -> new DeferredSpawnEggItem(RAIDER_BOSS, 0x6B5637, 0x3E2F1E, new Item.Properties()));

    // Creates a new food item with the id "basebuster:example_id", nutrition 1 and saturation 2
    public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", new Item.Properties().food(new FoodProperties.Builder()
            .alwaysEdible().nutrition(1).saturationModifier(2f).build()));

    // Creates a creative tab with the id "basebuster:example_tab" for the example item, that is placed after the combat tab
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.basebuster")) //The language key for the title of your CreativeModeTab
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> BASE_FLAG_BLOCK_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(BASE_FLAG_BLOCK_ITEM.get());
                output.accept(STONE_REPAIR_HAMMER.get());
                output.accept(IRON_REPAIR_HAMMER.get());
                output.accept(DIAMOND_REPAIR_HAMMER.get());
                output.accept(RAIDER_SPAWN_EGG.get());
                output.accept(RAIDER_BOSS_SPAWN_EGG.get());
            }).build());

    public static final Supplier<BlockEntityType<BaseFlagBlockEntity>> BASE_FLAG_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
            "base_flag_block_entity",
            () -> BlockEntityType.Builder.of(BaseFlagBlockEntity::new, BASE_FLAG_BLOCK.get()).build(null)
    );

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public BaseBuster(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so block entity types get registered
        BLOCK_ENTITY_TYPES.register(modEventBus);
        // Register the Deferred Register to the mod event bus so entity types get registered
        ENTITY_TYPES.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (BaseBuster) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::onGatherData);
        modEventBus.addListener(this::onRegisterEntityAttributes);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.register(new BaseBusterEventBus());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        if (Config.LOG_DIRT_BLOCK.getAsBoolean()) {
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));
        }

        LOGGER.info("{}{}", Config.MAGIC_NUMBER_INTRODUCTION.get(), Config.MAGIC_NUMBER.getAsInt());

        Config.ITEM_STRINGS.get().forEach((item) -> LOGGER.info("ITEM >> {}", item));
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS) {
            event.accept(EXAMPLE_BLOCK_ITEM);
        }
    }

    // Give raiders the same attributes as a zombie; configured max health is applied at spawn time
    private void onRegisterEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(RAIDER.get(), Zombie.createAttributes().build());
        event.put(RAIDER_BOSS.get(), Zombie.createAttributes().build());
    }

    public void onGatherData(GatherDataEvent event) {
        // Call event.createDatapackRegistryObjects(...) first if adding datapack objects

        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        ExistingFileHelper existingFileHelper = event.getExistingFileHelper();

        event.createProvider(BaseBusterLanguageProvider::new);
        generator.addProvider(event.includeClient(), new BaseBusterBlockStateProvider(output, existingFileHelper));
        generator.addProvider(event.includeClient(), new BaseBusterItemModelProvider(output, existingFileHelper));
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
