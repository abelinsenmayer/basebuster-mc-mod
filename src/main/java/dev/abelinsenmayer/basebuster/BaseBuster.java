package dev.abelinsenmayer.basebuster;

import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import dev.abelinsenmayer.basebuster.blocks.RaiderScaffoldingBlock;
import dev.abelinsenmayer.basebuster.blocks.RaiderScaffoldingBlockEntity;
import dev.abelinsenmayer.basebuster.blocks.VictoryBannerBlock;
import dev.abelinsenmayer.basebuster.blocks.VictoryWallBannerBlock;
import dev.abelinsenmayer.basebuster.entities.RaiderBossEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderEvokerEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderPillagerEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderRavagerBossEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderRavagerEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderVindicatorEntity;
import dev.abelinsenmayer.basebuster.events.BaseBusterEventBus;
import dev.abelinsenmayer.basebuster.items.CitizenshipCertificateItem;
import dev.abelinsenmayer.basebuster.items.FactionFoundingDocumentItem;
import dev.abelinsenmayer.basebuster.items.FactionPledgeItem;
import dev.abelinsenmayer.basebuster.items.RaidSpawnerItem;
import dev.abelinsenmayer.basebuster.items.RepairHammerItem;
import dev.abelinsenmayer.basebuster.network.FactionNetworking;
import dev.abelinsenmayer.basebuster.providers.BaseBusterBlockStateProvider;
import dev.abelinsenmayer.basebuster.providers.BaseBusterItemModelProvider;
import dev.abelinsenmayer.basebuster.providers.BaseBusterLanguageProvider;
import dev.abelinsenmayer.basebuster.providers.BaseBusterRecipeProvider;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableSet;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.monster.Evoker;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    public static final DeferredRegister<PoiType> POI_TYPES = DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, MODID);
    public static final DeferredRegister<VillagerProfession> VILLAGER_PROFESSIONS = DeferredRegister.create(Registries.VILLAGER_PROFESSION, MODID);

    public static final DeferredBlock<BaseFlagBlock> BASE_FLAG_BLOCK = BLOCKS.register("base_flag_block", () -> new BaseFlagBlock(BlockBehaviour.Properties.of()));
    public static final DeferredItem<BlockItem> BASE_FLAG_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("base_flag_block", BASE_FLAG_BLOCK);

    // Temporary scaffolding placed by raiders: identical to vanilla scaffolding
    // but drops nothing and removes itself after a minute. No item form -- it
    // is never legitimately obtainable by players.
    public static final DeferredBlock<RaiderScaffoldingBlock> RAIDER_SCAFFOLDING = BLOCKS.register("raider_scaffolding",
            () -> new RaiderScaffoldingBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.SCAFFOLDING).noLootTable()));

    // Repair hammers: durability and mining ability match pickaxes of the same tier
    public static final DeferredItem<RepairHammerItem> STONE_REPAIR_HAMMER = ITEMS.register("stone_repair_hammer", () -> new RepairHammerItem(Tiers.STONE, new Item.Properties()));
    public static final DeferredItem<RepairHammerItem> IRON_REPAIR_HAMMER = ITEMS.register("iron_repair_hammer", () -> new RepairHammerItem(Tiers.IRON, new Item.Properties()));
    public static final DeferredItem<RepairHammerItem> DIAMOND_REPAIR_HAMMER = ITEMS.register("diamond_repair_hammer", () -> new RepairHammerItem(Tiers.DIAMOND, new Item.Properties()));

    // Test tool disguised as rotten flesh: right-clicking a block raids the
    // nearest player-owned flag, spawning the party at the clicked spot
    public static final DeferredItem<RaidSpawnerItem> RAID_SPAWNER = ITEMS.register("raid_spawner", () -> new RaidSpawnerItem(new Item.Properties()));

    // The charter that founds a faction: right-clicking opens the faction
    // creation screen, and completing it consumes the document and seats
    // the founder on the new team
    public static final DeferredItem<FactionFoundingDocumentItem> FACTION_FOUNDING_DOCUMENT = ITEMS.register("faction_founding_document", () -> new FactionFoundingDocumentItem(new Item.Properties()));

    // A paper certificate for an existing faction: unbound, right-clicking
    // binds it to the player's current faction; bound, it joins that faction
    public static final DeferredItem<FactionPledgeItem> FACTION_PLEDGE = ITEMS.register("faction_pledge", () -> new FactionPledgeItem(new Item.Properties()));

    // A villager bound to paper by an unbound faction pledge: right-clicking
    // the ground inside a team-owned base's protection sphere releases the
    // villager as a citizen of that base
    public static final DeferredItem<CitizenshipCertificateItem> CITIZENSHIP_CERTIFICATE = ITEMS.register("citizenship_certificate", () -> new CitizenshipCertificateItem(new Item.Properties().stacksTo(16)));

    // Stored on a bound faction pledge: the name of the scoreboard team the
    // pledge joins when right-clicked. Absent = unbound pledge
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> FACTION =
            DATA_COMPONENT_TYPES.register("faction", () -> DataComponentType.<String>builder()
                    .persistent(Codec.STRING)
                    .build());

    // Victory banners: each tier is a standing block plus its wall-mounted
    // counterpart, sharing a single BannerItem that places whichever applies
    public static final DeferredBlock<VictoryBannerBlock> VICTORY_BANNER_TIER1 = BLOCKS.register("victory_banner_tier1", () -> new VictoryBannerBlock(1, bannerProperties()));
    public static final DeferredBlock<VictoryWallBannerBlock> VICTORY_WALL_BANNER_TIER1 = BLOCKS.register("victory_wall_banner_tier1", () -> new VictoryWallBannerBlock(1, bannerProperties().dropsLike(VICTORY_BANNER_TIER1.get())));
    public static final DeferredItem<BannerItem> VICTORY_BANNER_TIER1_ITEM = ITEMS.register("victory_banner_tier1", () -> new BannerItem(VICTORY_BANNER_TIER1.get(), VICTORY_WALL_BANNER_TIER1.get(), new Item.Properties().stacksTo(16)));
    public static final DeferredBlock<VictoryBannerBlock> VICTORY_BANNER_TIER2 = BLOCKS.register("victory_banner_tier2", () -> new VictoryBannerBlock(2, bannerProperties()));
    public static final DeferredBlock<VictoryWallBannerBlock> VICTORY_WALL_BANNER_TIER2 = BLOCKS.register("victory_wall_banner_tier2", () -> new VictoryWallBannerBlock(2, bannerProperties().dropsLike(VICTORY_BANNER_TIER2.get())));
    public static final DeferredItem<BannerItem> VICTORY_BANNER_TIER2_ITEM = ITEMS.register("victory_banner_tier2", () -> new BannerItem(VICTORY_BANNER_TIER2.get(), VICTORY_WALL_BANNER_TIER2.get(), new Item.Properties().stacksTo(16)));
    public static final DeferredBlock<VictoryBannerBlock> VICTORY_BANNER_TIER3 = BLOCKS.register("victory_banner_tier3", () -> new VictoryBannerBlock(3, bannerProperties()));
    public static final DeferredBlock<VictoryWallBannerBlock> VICTORY_WALL_BANNER_TIER3 = BLOCKS.register("victory_wall_banner_tier3", () -> new VictoryWallBannerBlock(3, bannerProperties().dropsLike(VICTORY_BANNER_TIER3.get())));
    public static final DeferredItem<BannerItem> VICTORY_BANNER_TIER3_ITEM = ITEMS.register("victory_banner_tier3", () -> new BannerItem(VICTORY_BANNER_TIER3.get(), VICTORY_WALL_BANNER_TIER3.get(), new Item.Properties().stacksTo(16)));
    public static final DeferredBlock<VictoryBannerBlock> VICTORY_BANNER_TIER4 = BLOCKS.register("victory_banner_tier4", () -> new VictoryBannerBlock(4, bannerProperties()));
    public static final DeferredBlock<VictoryWallBannerBlock> VICTORY_WALL_BANNER_TIER4 = BLOCKS.register("victory_wall_banner_tier4", () -> new VictoryWallBannerBlock(4, bannerProperties().dropsLike(VICTORY_BANNER_TIER4.get())));
    public static final DeferredItem<BannerItem> VICTORY_BANNER_TIER4_ITEM = ITEMS.register("victory_banner_tier4", () -> new BannerItem(VICTORY_BANNER_TIER4.get(), VICTORY_WALL_BANNER_TIER4.get(), new Item.Properties().stacksTo(16)));
    public static final DeferredBlock<VictoryBannerBlock> VICTORY_BANNER_TIER5 = BLOCKS.register("victory_banner_tier5", () -> new VictoryBannerBlock(5, bannerProperties()));
    public static final DeferredBlock<VictoryWallBannerBlock> VICTORY_WALL_BANNER_TIER5 = BLOCKS.register("victory_wall_banner_tier5", () -> new VictoryWallBannerBlock(5, bannerProperties().dropsLike(VICTORY_BANNER_TIER5.get())));
    public static final DeferredItem<BannerItem> VICTORY_BANNER_TIER5_ITEM = ITEMS.register("victory_banner_tier5", () -> new BannerItem(VICTORY_BANNER_TIER5.get(), VICTORY_WALL_BANNER_TIER5.get(), new Item.Properties().stacksTo(16)));
    // Banner items in tier order (index = tier - 1), for raid rewards
    public static final List<DeferredItem<BannerItem>> VICTORY_BANNER_ITEMS = List.of(
            VICTORY_BANNER_TIER1_ITEM, VICTORY_BANNER_TIER2_ITEM, VICTORY_BANNER_TIER3_ITEM,
            VICTORY_BANNER_TIER4_ITEM, VICTORY_BANNER_TIER5_ITEM);

    // Same physical properties as a vanilla banner, plus PushReaction.BLOCK so
    // pistons can't push or pop them off and flowing liquids can't wash them
    // away -- trophies stay put, raid or no raid
    private static BlockBehaviour.Properties bannerProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .forceSolidOn()
                .instrument(NoteBlockInstrument.BASS)
                .noCollission()
                .strength(1.0F)
                .ignitedByLava()
                .pushReaction(PushReaction.BLOCK);
    }

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

    // Illager raiders: vanilla illager hosts carrying the shared raider layer
    // (squad + flag goals). Egg colors echo each species' vanilla spawn egg.
    public static final Supplier<EntityType<RaiderVindicatorEntity>> RAIDER_VINDICATOR = ENTITY_TYPES.register("raider_vindicator",
            () -> EntityType.Builder.of(RaiderVindicatorEntity::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.74F)
                    .clientTrackingRange(8)
                    .build("raider_vindicator"));
    public static final DeferredItem<DeferredSpawnEggItem> RAIDER_VINDICATOR_SPAWN_EGG = ITEMS.register("raider_vindicator_spawn_egg",
            () -> new DeferredSpawnEggItem(RAIDER_VINDICATOR, 0x957959, 0x275B51, new Item.Properties()));

    public static final Supplier<EntityType<RaiderPillagerEntity>> RAIDER_PILLAGER = ENTITY_TYPES.register("raider_pillager",
            () -> EntityType.Builder.of(RaiderPillagerEntity::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.74F)
                    .clientTrackingRange(8)
                    .build("raider_pillager"));
    public static final DeferredItem<DeferredSpawnEggItem> RAIDER_PILLAGER_SPAWN_EGG = ITEMS.register("raider_pillager_spawn_egg",
            () -> new DeferredSpawnEggItem(RAIDER_PILLAGER, 0x532F36, 0x8B3E2C, new Item.Properties()));

    public static final Supplier<EntityType<RaiderEvokerEntity>> RAIDER_EVOKER = ENTITY_TYPES.register("raider_evoker",
            () -> EntityType.Builder.of(RaiderEvokerEntity::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F)
                    .eyeHeight(1.74F)
                    .clientTrackingRange(8)
                    .build("raider_evoker"));
    public static final DeferredItem<DeferredSpawnEggItem> RAIDER_EVOKER_SPAWN_EGG = ITEMS.register("raider_evoker_spawn_egg",
            () -> new DeferredSpawnEggItem(RAIDER_EVOKER, 0x1E1B1A, 0x4A4945, new Item.Properties()));

    public static final Supplier<EntityType<RaiderRavagerEntity>> RAIDER_RAVAGER = ENTITY_TYPES.register("raider_ravager",
            () -> EntityType.Builder.of(RaiderRavagerEntity::new, MobCategory.MONSTER)
                    .sized(1.95F, 2.2F)
                    .eyeHeight(2.08F)
                    .clientTrackingRange(8)
                    .build("raider_ravager"));
    public static final DeferredItem<DeferredSpawnEggItem> RAIDER_RAVAGER_SPAWN_EGG = ITEMS.register("raider_ravager_spawn_egg",
            () -> new DeferredSpawnEggItem(RAIDER_RAVAGER, 0x757470, 0x5B5049, new Item.Properties()));

    // The illager raid leader: a ravager scaled up like the zombie boss, with
    // bonus health on top of the ravager's native 100. Dimensions are the
    // base ravager size; the entity applies the SCALE attribute itself.
    public static final Supplier<EntityType<RaiderRavagerBossEntity>> RAIDER_RAVAGER_BOSS = ENTITY_TYPES.register("raider_ravager_boss",
            () -> EntityType.Builder.of(RaiderRavagerBossEntity::new, MobCategory.MONSTER)
                    .sized(1.95F, 2.2F)
                    .eyeHeight(2.08F)
                    .clientTrackingRange(8)
                    .build("raider_ravager_boss"));
    public static final DeferredItem<DeferredSpawnEggItem> RAIDER_RAVAGER_BOSS_SPAWN_EGG = ITEMS.register("raider_ravager_boss_spawn_egg",
            () -> new DeferredSpawnEggItem(RAIDER_RAVAGER_BOSS, 0x5B5049, 0x3A3430, new Item.Properties()));

    // The Arcanist villager profession: sells Bottles o' Enchanting. Its job
    // site is a block claimable through POI detection -- POI types match block
    // states, so an end crystal (an entity) can't be one; the enchanting
    // table stands in. Change ARCANIST_WORKSTATION to any unclaimed block to
    // re-theme it. maxTickets 1 means one table supports one arcanist, like
    // vanilla workstations. The acquirable_job_site POI tag (data/minecraft/
    // tags/point_of_interest_type) lets unemployed villagers claim it.
    public static final Block ARCANIST_WORKSTATION = Blocks.ENCHANTING_TABLE;
    public static final DeferredHolder<PoiType, PoiType> ARCANIST_POI = POI_TYPES.register("arcanist",
            () -> new PoiType(Set.copyOf(ARCANIST_WORKSTATION.getStateDefinition().getPossibleStates()), 1, 1));
    public static final DeferredHolder<VillagerProfession, VillagerProfession> ARCANIST = VILLAGER_PROFESSIONS.register("arcanist",
            () -> new VillagerProfession(
                    "arcanist",
                    poiType -> poiType.is(ARCANIST_POI.getKey()),
                    poiType -> poiType.is(ARCANIST_POI.getKey()),
                    ImmutableSet.of(Items.LAPIS_LAZULI),
                    ImmutableSet.of(),
                    SoundEvents.ENCHANTMENT_TABLE_USE));

    // The Golem Smith villager profession: sells Iron Golem spawn eggs. Its
    // job site is the anvil family -- POI types match block states, so every
    // damage stage claims the site. Change GOLEM_SMITH_WORKSTATIONS to any
    // unclaimed block set to re-theme it. maxTickets 1 means one anvil
    // supports one golem smith, like vanilla workstations. The
    // acquirable_job_site POI tag (data/minecraft/tags/point_of_interest_type)
    // lets unemployed villagers claim it.
    public static final Set<Block> GOLEM_SMITH_WORKSTATIONS = Set.of(
            Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL);
    public static final DeferredHolder<PoiType, PoiType> GOLEM_SMITH_POI = POI_TYPES.register("golem_smith",
            () -> new PoiType(GOLEM_SMITH_WORKSTATIONS.stream()
                    .flatMap(block -> block.getStateDefinition().getPossibleStates().stream())
                    .collect(Collectors.toSet()), 1, 1));
    public static final DeferredHolder<VillagerProfession, VillagerProfession> GOLEM_SMITH = VILLAGER_PROFESSIONS.register("golem_smith",
            () -> new VillagerProfession(
                    "golem_smith",
                    poiType -> poiType.is(GOLEM_SMITH_POI.getKey()),
                    poiType -> poiType.is(GOLEM_SMITH_POI.getKey()),
                    ImmutableSet.of(Items.IRON_INGOT),
                    ImmutableSet.of(),
                    SoundEvents.ANVIL_USE));

    // The mod's creative tab, placed after the combat tab
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BASEBUSTER_TAB = CREATIVE_MODE_TABS.register("basebuster_tab", () -> CreativeModeTab.builder()
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
                output.accept(RAIDER_VINDICATOR_SPAWN_EGG.get());
                output.accept(RAIDER_PILLAGER_SPAWN_EGG.get());
                output.accept(RAIDER_EVOKER_SPAWN_EGG.get());
                output.accept(RAIDER_RAVAGER_SPAWN_EGG.get());
                output.accept(RAIDER_RAVAGER_BOSS_SPAWN_EGG.get());
                output.accept(RAID_SPAWNER.get());
                output.accept(FACTION_FOUNDING_DOCUMENT.get());
                output.accept(FACTION_PLEDGE.get());
                output.accept(CITIZENSHIP_CERTIFICATE.get());
                output.accept(VICTORY_BANNER_TIER1_ITEM.get());
                output.accept(VICTORY_BANNER_TIER2_ITEM.get());
                output.accept(VICTORY_BANNER_TIER3_ITEM.get());
                output.accept(VICTORY_BANNER_TIER4_ITEM.get());
                output.accept(VICTORY_BANNER_TIER5_ITEM.get());
            }).build());

    public static final Supplier<BlockEntityType<BaseFlagBlockEntity>> BASE_FLAG_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
            "base_flag_block_entity",
            () -> BlockEntityType.Builder.of(BaseFlagBlockEntity::new, BASE_FLAG_BLOCK.get()).build(null)
    );

    public static final Supplier<BlockEntityType<RaiderScaffoldingBlockEntity>> RAIDER_SCAFFOLDING_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
            "raider_scaffolding_block_entity",
            () -> BlockEntityType.Builder.of(RaiderScaffoldingBlockEntity::new, RAIDER_SCAFFOLDING.get()).build(null)
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
        DATA_COMPONENT_TYPES.register(modEventBus);
        // Register the Deferred Register to the mod event bus so villager
        // profession and job-site POI types get registered
        POI_TYPES.register(modEventBus);
        VILLAGER_PROFESSIONS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (BaseBuster) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        modEventBus.addListener(this::onGatherData);
        modEventBus.addListener(FactionNetworking::onRegisterPayloads);
        modEventBus.addListener(this::onRegisterEntityAttributes);
        modEventBus.addListener(this::onAddBannerBlocks);
        modEventBus.addListener(this::onModConfigReloaded);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        NeoForge.EVENT_BUS.register(new BaseBusterEventBus());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");
        event.enqueueWork(BaseBuster::registerArcanistTrades);
        event.enqueueWork(BaseBuster::registerGolemSmithTrades);
    }

    // The Arcanist's whole trade table: one emeralds -> Bottles o' Enchanting
    // offer per profession level, the deal improving as they level. The base
    // counts live in these arrays because interact-time scaling
    // (BaseBusterEventBus#scaleArcanistOffers) rewrites offer results from
    // the same numbers. Each level must produce exactly one offer -- the
    // scaler maps offer index to tier.
    public static final int[] ARCANIST_TIER_EMERALDS = {3, 4, 6, 8, 10};
    public static final int[] ARCANIST_TIER_BOTTLES = {1, 2, 3, 4, 6};
    private static final int[] ARCANIST_TIER_XP = {1, 5, 10, 15, 20};
    private static final int ARCANIST_TRADE_USES = 12;

    // TRADES is a mutable map keyed by profession, so adding the table is a
    // plain map put.
    private static void registerArcanistTrades() {
        Int2ObjectMap<VillagerTrades.ItemListing[]> trades = new Int2ObjectOpenHashMap<>();
        for (int i = 0; i < ARCANIST_TIER_BOTTLES.length; i++) {
            trades.put(i + 1, new VillagerTrades.ItemListing[]{
                    new VillagerTrades.ItemsForEmeralds(Items.EXPERIENCE_BOTTLE,
                            ARCANIST_TIER_EMERALDS[i], ARCANIST_TIER_BOTTLES[i],
                            ARCANIST_TRADE_USES, ARCANIST_TIER_XP[i])});
        }
        VillagerTrades.TRADES.put(ARCANIST.get(), trades);
    }

    // The Golem Smith's whole trade table: one emeralds -> Iron Golem spawn
    // egg offer per profession level, the deal improving as they level. The
    // base counts live in these arrays because interact-time scaling
    // (BaseBusterEventBus#scaleGolemSmithOffers) rewrites offer results from
    // the same numbers. Each level must produce exactly one offer -- the
    // scaler maps offer index to tier.
    public static final int[] GOLEM_SMITH_TIER_EMERALDS = {8, 16, 24, 32, 48};
    public static final int[] GOLEM_SMITH_TIER_EGGS = {1, 2, 3, 4, 6};
    private static final int[] GOLEM_SMITH_TIER_XP = {1, 5, 10, 15, 20};
    private static final int GOLEM_SMITH_TRADE_USES = 1;

    private static void registerGolemSmithTrades() {
        Int2ObjectMap<VillagerTrades.ItemListing[]> trades = new Int2ObjectOpenHashMap<>();
        for (int i = 0; i < GOLEM_SMITH_TIER_EGGS.length; i++) {
            trades.put(i + 1, new VillagerTrades.ItemListing[]{
                    new VillagerTrades.ItemsForEmeralds(Items.IRON_GOLEM_SPAWN_EGG,
                            GOLEM_SMITH_TIER_EMERALDS[i], GOLEM_SMITH_TIER_EGGS[i],
                            GOLEM_SMITH_TRADE_USES, GOLEM_SMITH_TIER_XP[i])});
        }
        VillagerTrades.TRADES.put(GOLEM_SMITH.get(), trades);
    }

    // The config file watcher fires this off-thread; hop to the server thread
    // before touching the game rule, which also broadcasts the change to clients
    private void onModConfigReloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == Config.SPEC) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                server.execute(() -> BaseBusterEventBus.syncDisableRaidsGameRule(server));
            }
        }
    }

    // Victory banners reuse the vanilla banner block entity; make its type
    // accept our blocks so they actually get one when placed
    private void onAddBannerBlocks(BlockEntityTypeAddBlocksEvent event) {
        event.modify(BlockEntityType.BANNER,
                VICTORY_BANNER_TIER1.get(), VICTORY_WALL_BANNER_TIER1.get(),
                VICTORY_BANNER_TIER2.get(), VICTORY_WALL_BANNER_TIER2.get(),
                VICTORY_BANNER_TIER3.get(), VICTORY_WALL_BANNER_TIER3.get(),
                VICTORY_BANNER_TIER4.get(), VICTORY_WALL_BANNER_TIER4.get(),
                VICTORY_BANNER_TIER5.get(), VICTORY_WALL_BANNER_TIER5.get());
    }

    // Give raiders the same attributes as a zombie; configured max health is applied at spawn time.
    // Illager raiders reuse their species' own attribute factories.
    private void onRegisterEntityAttributes(EntityAttributeCreationEvent event) {
        event.put(RAIDER.get(), Zombie.createAttributes().build());
        event.put(RAIDER_BOSS.get(), Zombie.createAttributes().build());
        event.put(RAIDER_VINDICATOR.get(), Vindicator.createAttributes().build());
        event.put(RAIDER_PILLAGER.get(), Pillager.createAttributes().build());
        event.put(RAIDER_EVOKER.get(), Evoker.createAttributes().build());
        event.put(RAIDER_RAVAGER.get(), Ravager.createAttributes().build());
        event.put(RAIDER_RAVAGER_BOSS.get(), Ravager.createAttributes().build());
    }

    public void onGatherData(GatherDataEvent event) {
        // Call event.createDatapackRegistryObjects(...) first if adding datapack objects

        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        ExistingFileHelper existingFileHelper = event.getExistingFileHelper();

        event.createProvider(BaseBusterLanguageProvider::new);
        generator.addProvider(event.includeClient(), new BaseBusterBlockStateProvider(output, existingFileHelper));
        generator.addProvider(event.includeClient(), new BaseBusterItemModelProvider(output, existingFileHelper));
        generator.addProvider(event.includeServer(), new BaseBusterRecipeProvider(output, event.getLookupProvider()));
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
