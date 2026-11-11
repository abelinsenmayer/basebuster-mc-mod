package dev.abelinsenmayer.basebuster.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.abelinsenmayer.basebuster.entities.RaiderEntity;
import dev.abelinsenmayer.basebuster.entities.RaiderSkin;
import net.minecraft.client.model.EndermanModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PiglinModel;
import net.minecraft.client.model.SkeletonModel;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.ZombieVillagerModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.Map;

/**
 * Renders a raider as its assigned {@link RaiderSkin}. Renderer registration is
 * static per entity type but the skin is per-entity synced data, so this keeps
 * a baked model per skin and swaps {@code this.model} before delegating to the
 * standard humanoid render pipeline. All layers that consult
 * {@code getModel()}/{@code getParentModel()} (armor, held items, overlays)
 * therefore see the skin's model automatically.
 *
 * <p>The boss renderer is the same class parameterized on
 * {@code RaiderBossEntity}.
 */
public class RaiderRenderer<T extends RaiderEntity> extends HumanoidMobRenderer<T, HumanoidModel<T>> {
    private final Map<RaiderSkin, HumanoidModel<T>> models = new EnumMap<>(RaiderSkin.class);

    public RaiderRenderer(EntityRendererProvider.Context context) {
        super(context, new ZombieModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
        for (RaiderSkin skin : RaiderSkin.values()) {
            this.models.put(skin, this.createModel(skin, context));
        }
        // One armor layer serves all skins; on piglin-mesh skins armor renders
        // with the standard humanoid proportions rather than the snout-scaled
        // PiglinArmorModel, so helmets may clip the snout slightly
        this.addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE_OUTER_ARMOR)),
                context.getModelManager()));
        this.addLayer(new SkinOverlayLayer<>(this, context));
        this.addLayer(new EndermanEyesLayer<>(this));
    }

    private HumanoidModel<T> createModel(RaiderSkin skin, EntityRendererProvider.Context context) {
        ModelPart part = context.bakeLayer(switch (skin.model()) {
            case ZOMBIE -> ModelLayers.ZOMBIE;
            case SKELETON -> ModelLayers.SKELETON;
            case PIGLIN -> ModelLayers.PIGLIN;
            case ZOMBIE_VILLAGER -> ModelLayers.ZOMBIE_VILLAGER;
//            case ENDERMAN -> ModelLayers.ENDERMAN;
            case BOGGED -> ModelLayers.BOGGED;
        });
        return switch (skin.model()) {
            // ZombieModel gives the aggressive raised-arms attack for free
            // since RaiderEntity is a Zombie
            case ZOMBIE -> new ZombieModel<>(part);
            // SkeletonModel needs Mob & RangedAttackMob; RaiderEntity
            // implements it so bowmen get the bow-draw arm pose
            case SKELETON -> new SkeletonModel<>(part);
            case PIGLIN -> new PiglinModel<>(part);
            case ZOMBIE_VILLAGER -> new ZombieVillagerModel<>(part);
//            case ENDERMAN -> new EndermanModel<>(part);
            // BoggedModel is bound to Bogged; the BOGGED layer is
            // skeleton-family geometry so a HumanoidModel fits
            case BOGGED -> new HumanoidModel<>(part);
        };
    }

    @Override
    public void render(T entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        this.model = this.models.get(entity.getSkin());
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return entity.getSkin().texture();
    }

    /**
     * Renders a skin's clothing/wear overlay (stray rags, drowned clothes,
     * bogged mushrooms). The vanilla layers are typed to their entities, so
     * this recreates them: each skin's overlay is rendered with its own baked
     * {@code *_OUTER_LAYER} model, since some overlays have geometry the base
     * model lacks (e.g. the bogged's mushrooms).
     */
    private static class SkinOverlayLayer<T extends RaiderEntity> extends RenderLayer<T, HumanoidModel<T>> {
        private final Map<RaiderSkin, HumanoidModel<T>> overlayModels = new EnumMap<>(RaiderSkin.class);

        SkinOverlayLayer(RenderLayerParent<T, HumanoidModel<T>> parent, EntityRendererProvider.Context context) {
            super(parent);
            for (RaiderSkin skin : RaiderSkin.values()) {
                if (skin.overlay() != null) {
                    this.overlayModels.put(skin, new HumanoidModel<>(context.bakeLayer(overlayLayer(skin))));
                }
            }
        }

        private static ModelLayerLocation overlayLayer(RaiderSkin skin) {
            return switch (skin) {
                case STRAY -> ModelLayers.STRAY_OUTER_LAYER;
                case DROWNED -> ModelLayers.DROWNED_OUTER_LAYER;
                case BOGGED -> ModelLayers.BOGGED_OUTER_LAYER;
                default -> throw new IllegalStateException("Skin " + skin + " has an overlay but no overlay layer mapping");
            };
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                           float limbSwing, float limbSwingAmount, float partialTicks,
                           float ageInTicks, float netHeadYaw, float headPitch) {
            RaiderSkin skin = entity.getSkin();
            if (skin.overlay() != null && !entity.isInvisible()) {
                HumanoidModel<T> overlayModel = this.overlayModels.get(skin);
                // Copy young/attackTime/riding so the overlay isn't stuck in
                // EntityModel's default young=true (baby proportions) and
                // matches the parent's attack poses
                this.getParentModel().copyPropertiesTo(overlayModel);
                overlayModel.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
                renderColoredCutoutModel(overlayModel, skin.overlay(), poseStack, buffer, packedLight, entity, -1);
            }
        }
    }

    /**
     * Enderman eyes are a separate glow texture drawn with the eyes render
     * type over the whole model. The vanilla {@code EndermanEyesLayer} is
     * typed to {@code Enderman}.
     */
    private static class EndermanEyesLayer<T extends RaiderEntity> extends RenderLayer<T, HumanoidModel<T>> {
        private static final ResourceLocation ENDERMAN_EYES =
                ResourceLocation.withDefaultNamespace("textures/entity/enderman/enderman_eyes.png");

        EndermanEyesLayer(RenderLayerParent<T, HumanoidModel<T>> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, T entity,
                           float limbSwing, float limbSwingAmount, float partialTicks,
                           float ageInTicks, float netHeadYaw, float headPitch) {
//            if (entity.getSkin() == RaiderSkin.ENDERMAN && !entity.isInvisible()) {
//                VertexConsumer consumer = buffer.getBuffer(RenderType.eyes(ENDERMAN_EYES));
//                this.getParentModel().renderToBuffer(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY, -1);
//            }
        }
    }
}
