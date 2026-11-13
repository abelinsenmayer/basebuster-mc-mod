package dev.abelinsenmayer.basebuster.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.Config;
import dev.abelinsenmayer.basebuster.entities.RaiderBossEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
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
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // Draw through walls: the overlay exists to reveal the planned route
        // even when terrain or the base occludes it
        RenderSystem.disableDepthTest();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof RaiderBossEntity boss) {
                drawSquadPath(poseStack, lines, boss);
            }
        }
        buffers.endBatch(RenderType.lines());
        RenderSystem.enableDepthTest();

        poseStack.popPose();
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
