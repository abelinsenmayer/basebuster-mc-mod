package dev.abelinsenmayer.basebuster.network;

import dev.abelinsenmayer.basebuster.BaseBuster;
import dev.abelinsenmayer.basebuster.client.BaseFlagScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The server's answer to {@link CreateFactionPayload}. On success the client
 * closes the pledge screen; on failure it shows {@link #message} in red. The
 * client-only class references in {@link #handle} are resolved lazily, so this
 * record is safe to load on dedicated servers.
 */
public record CreateFactionResultPayload(boolean success, Component message)
        implements CustomPacketPayload {

    public static final Type<CreateFactionResultPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BaseBuster.MODID, "create_faction_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CreateFactionResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, CreateFactionResultPayload::success,
                    ComponentSerialization.STREAM_CODEC, CreateFactionResultPayload::message,
                    CreateFactionResultPayload::new);

    @Override
    public Type<CreateFactionResultPayload> type() {
        return TYPE;
    }

    /** Client-side handler: delivers the result to the open pledge screen. */
    public static void handle(CreateFactionResultPayload payload, IPayloadContext context) {
        if (Minecraft.getInstance().screen instanceof BaseFlagScreen screen) {
            screen.onCreateResult(payload);
        }
    }
}
