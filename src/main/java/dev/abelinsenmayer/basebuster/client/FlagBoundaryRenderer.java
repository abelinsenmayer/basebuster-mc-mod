package dev.abelinsenmayer.basebuster.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.blocks.BaseFlagBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Debug overlay drawing {@link BaseFlagBlock#FLAG_PROTECTION_RADIUS} as a rough
 * wireframe sphere around every flag listed in {@link FlagBoundaryDebugState}.
 * Toggled per flag by right-clicking the flag with a stick.
 */
@EventBusSubscriber(modid = BaseBuster.MODID, value = Dist.CLIENT)
public final class FlagBoundaryRenderer {
    // Line segments per great-circle ring; higher = rounder, costlier
    private static final int RING_SEGMENTS = 96;
    // Ring offsets for the two diagonal latitude rings: sin/cos(45 deg)
    private static final float LAT_OFFSET = 0.7071068F;

    // Translucent cyan, readable against most terrain
    private static final int R = 80;
    private static final int G = 220;
    private static final int B = 255;
    private static final int A = 170;

    private FlagBoundaryRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || FlagBoundaryDebugState.isEmpty()) {
            return;
        }

        Vec3 cameraPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // Draw through walls: the overlay is meant to reveal the protected
        // volume even when viewed from inside a closed base
        RenderSystem.disableDepthTest();
        for (BlockPos flagPos : FlagBoundaryDebugState.enabledFlags()) {
            drawBoundarySphere(poseStack, lines, flagPos, BaseFlagBlock.FLAG_PROTECTION_RADIUS);
        }
        buffers.endBatch(RenderType.lines());
        RenderSystem.enableDepthTest();

        poseStack.popPose();
    }

    // Drop stale positions when leaving a world
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        FlagBoundaryDebugState.clear();
    }

    private static void drawBoundarySphere(PoseStack poseStack, VertexConsumer lines, BlockPos flagPos, int radius) {
        // Sphere centered on the flag block's center; the protection check
        // compares block positions, so this is accurate to about half a block
        double cx = flagPos.getX() + 0.5;
        double cy = flagPos.getY() + 0.5;
        double cz = flagPos.getZ() + 0.5;
        float r = radius + 0.5F;

        PoseStack.Pose pose = poseStack.last();
        // Equator plus the two axial meridians
        drawRing(pose, lines, cx, cy, cz, r, RingPlane.XZ);
        drawRing(pose, lines, cx, cy, cz, r, RingPlane.XY);
        drawRing(pose, lines, cx, cy, cz, r, RingPlane.YZ);
        // Latitude rings at +/-45 deg give the wireframe visible volume
        float latRadius = r * LAT_OFFSET;
        float latHeight = r * LAT_OFFSET;
        drawRing(pose, lines, cx, cy + latHeight, cz, latRadius, RingPlane.XZ);
        drawRing(pose, lines, cx, cy - latHeight, cz, latRadius, RingPlane.XZ);
    }

    private enum RingPlane {
        XZ, XY, YZ
    }

    private static void drawRing(PoseStack.Pose pose, VertexConsumer lines, double cx, double cy, double cz, float radius, RingPlane plane) {
        double prevX = 0;
        double prevY = 0;
        double prevZ = 0;
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double angle = i * (Math.PI * 2.0 / RING_SEGMENTS);
            float u = (float) (radius * Math.cos(angle));
            float v = (float) (radius * Math.sin(angle));
            double x = plane == RingPlane.YZ ? cx : cx + u;
            double y = plane == RingPlane.XZ ? cy : cy + (plane == RingPlane.XY ? v : u);
            double z = plane == RingPlane.XY ? cz : cz + (plane == RingPlane.XZ ? v : u);

            if (i > 0) {
                emitLine(pose, lines, prevX, prevY, prevZ, x, y, z);
            }
            prevX = x;
            prevY = y;
            prevZ = z;
        }
    }

    private static void emitLine(PoseStack.Pose pose, VertexConsumer lines, double x1, double y1, double z1, double x2, double y2, double z2) {
        // The lines shader expects the segment direction as the vertex normal
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float nx = (float) (dx / len);
        float ny = (float) (dy / len);
        float nz = (float) (dz / len);
        lines.addVertex(pose, (float) x1, (float) y1, (float) z1)
                .setColor(R, G, B, A)
                .setNormal(pose, nx, ny, nz);
        lines.addVertex(pose, (float) x2, (float) y2, (float) z2)
                .setColor(R, G, B, A)
                .setNormal(pose, nx, ny, nz);
    }
}
