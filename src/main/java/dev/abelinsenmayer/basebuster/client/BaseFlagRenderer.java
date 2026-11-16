package dev.abelinsenmayer.basebuster.client;

import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BannerRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * Renders a base flag exactly like a standing banner. Vanilla's
 * {@link BannerRenderer} is typed to {@code BannerBlockEntity}, so it can't be
 * registered directly for {@link BaseFlagBlockEntity}; this thin subclass
 * delegates every call. Base color comes from
 * {@link BaseFlagBlockEntity#getBaseColor} (black, or red once captured).
 */
public class BaseFlagRenderer implements BlockEntityRenderer<BaseFlagBlockEntity> {
    private final BannerRenderer delegate;

    public BaseFlagRenderer(BlockEntityRendererProvider.Context context) {
        this.delegate = new BannerRenderer(context);
    }

    @Override
    public void render(BaseFlagBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        this.delegate.render(blockEntity, partialTick, poseStack, bufferSource, packedLight, packedOverlay);
    }
}
