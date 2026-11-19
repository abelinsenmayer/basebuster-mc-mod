package dev.abelinsenmayer.basebuster.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Registers all faction-related payloads; wired to the mod event bus in BaseBuster. */
public final class FactionNetworking {
    private FactionNetworking() {
    }

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(CreateFactionPayload.TYPE, CreateFactionPayload.STREAM_CODEC,
                CreateFactionPayload::handle);
        registrar.playToServer(EditFactionPayload.TYPE, EditFactionPayload.STREAM_CODEC,
                EditFactionPayload::handle);
        registrar.playToClient(CreateFactionResultPayload.TYPE, CreateFactionResultPayload.STREAM_CODEC,
                CreateFactionResultPayload::handle);
    }
}
