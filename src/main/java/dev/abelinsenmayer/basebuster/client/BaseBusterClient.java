package dev.abelinsenmayer.basebuster.client;

import dev.abelinsenmayer.basebuster.BaseBuster;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = BaseBuster.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = BaseBuster.MODID, value = Dist.CLIENT)
public class BaseBusterClient {
    public BaseBusterClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        BaseBuster.LOGGER.info("HELLO FROM CLIENT SETUP");
        BaseBuster.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());

        // Vanilla scaffolding rides the cutout layer, which is what makes the
        // weave's holes and the open top render transparent. Our block reuses
        // the vanilla models, so it needs the same layer or it renders solid
        event.enqueueWork(
                () -> ItemBlockRenderTypes.setRenderLayer(BaseBuster.RAIDER_SCAFFOLDING.get(), RenderType.cutout()));
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Each raider renders as its assigned RaiderSkin; raids assign one skin
        // theme per raid, egg/summon spawns get a random skin per entity
        event.registerEntityRenderer(BaseBuster.RAIDER.get(), RaiderRenderer::new);
        event.registerEntityRenderer(BaseBuster.RAIDER_BOSS.get(), RaiderRenderer::new);
    }
}
