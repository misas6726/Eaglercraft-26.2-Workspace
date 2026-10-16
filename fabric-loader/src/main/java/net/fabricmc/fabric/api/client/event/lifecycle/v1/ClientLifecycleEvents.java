package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.Minecraft;

/** Fabric API shim (CLIENT_STARTED only). */
public final class ClientLifecycleEvents {
	private ClientLifecycleEvents() {
	}

	public static final Event<ClientStarted> CLIENT_STARTED = EventFactory.createArrayBacked(ClientStarted.class, callbacks -> client -> {
		for (ClientStarted callback : callbacks) {
			callback.onClientStarted(client);
		}
	});

	@FunctionalInterface
	public interface ClientStarted {
		void onClientStarted(Minecraft client);
	}
}
