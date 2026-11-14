package dev.abelinsenmayer.basebuster.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.entities.RaiderBossEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * Debug overlay drawing each living {@link RaiderBossEntity}'s squad path --
 * the decimated rough route assigned at raid spawn or re-planned by
 * {@code PlanSquadPathGoal} -- as red line segments between its waypoints.
 * A leader segment from the boss to the first waypoint attributes each route
 * to its squad. Toggled by {@code raiderDebug.renderSquadPath}.
 */
@EventBusSubscriber(modid = BaseBuster.MODID, value = Dist.CLIENT)
public final class SquadPathDebugRenderer {
    // Bright red, readable against terrain and through walls
    private static final int R = 255;
    private static final int G = 60;
    private static final int B = 60;
    private static final int A = 220;

    private SquadPathDebugRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
                || !Config.RAIDER_DEBUG_RENDER_SQUAD_PATH.get()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        Vec3 cameraPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        // BufferBuilder instead of a RenderType buffer: RenderType.lines()'
        // composite state forces depth testing back on during endBatch, which
        // would defeat the through-wall draw in drawThroughWorld
        BufferBuilder lines = Tesselator.getInstance()
                .begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof RaiderBossEntity boss) {
                drawSquadPath(poseStack, lines, boss);
            }
        }

        poseStack.popPose();

        MeshData mesh = lines.build();
        if (mesh != null) {
            drawThroughWorld(mesh);
        }
    }

    // Submits the line mesh straight to the lines shader so no RenderType
    // composite state can override the GL state set here. Depth test stays
    // off (visible through terrain and the water surface) and fog is
    // neutralized for the draw -- the rendertype_lines fragment shader runs
    // linear_fog, and underwater fog would otherwise erase the overlay a few
    // dozen blocks out.
    private static void drawThroughWorld(MeshData mesh) {
        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();

        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(1.0E30F);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);

        // The model-view already holds the camera rotation for this stage and
        // the PoseStack only baked the -cameraPos translation into vertices,
        // so no matrix setup is needed -- touching the stack would strip the
        // rotation and unanchor the lines from the world
        BufferUploader.drawWithShader(mesh);

        RenderSystem.setShaderFogStart(fogStart);
        RenderSystem.setShaderFogEnd(fogEnd);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void drawSquadPath(PoseStack poseStack, VertexConsumer lines, RaiderBossEntity boss) {
        List<BlockPos> waypoints = boss.getSyncedSquadPath();
        if (waypoints.isEmpty()) {
            return;
        }

        PoseStack.Pose pose = poseStack.last();
        // Leader segment: boss eye position to the corridor's first waypoint
        Vec3 eye = boss.getEyePosition();
        BlockPos first = waypoints.get(0);
        emitLine(pose, lines, eye.x, eye.y, eye.z,
                first.getX() + 0.5, first.getY() + 0.5, first.getZ() + 0.5);

        for (int i = 1; i < waypoints.size(); i++) {
            BlockPos a = waypoints.get(i - 1);
            BlockPos b = waypoints.get(i);
            emitLine(pose, lines,
                    a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5,
                    b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        }
    }

    private static void emitLine(PoseStack.Pose pose, VertexConsumer lines, double x1, double y1, double z1, double x2, double y2, double z2) {
        // The lines shader expects the segment direction as the vertex normal
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-5) {
            return;
        }
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
