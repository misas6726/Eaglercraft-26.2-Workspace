package net.fabricmc.fabric.api.client.event.lifecycle.v1;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.client.Minecraft;

/** Fabric API shim. Fired from hooks the mod processor injects into Minecraft#tick. */
public final class ClientTickEvents {
	private ClientTickEvents() {
	}

	public static final Event<StartTick> START_CLIENT_TICK = EventFactory.createArrayBacked(StartTick.class, callbacks -> client -> {
		for (StartTick callback : callbacks) {
			callback.onStartTick(client);
		}
	});

	public static final Event<EndTick> END_CLIENT_TICK = EventFactory.createArrayBacked(EndTick.class, callbacks -> client -> {
		for (EndTick callback : callbacks) {
			callback.onEndTick(client);
		}
	});

	@FunctionalInterface
	public interface StartTick {
		void onStartTick(Minecraft client);
	}

	@FunctionalInterface
	public interface EndTick {
		void onEndTick(Minecraft client);
	}
}
