package net.eaglercraft.fabric.runtime;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Static entry points the mod processor injects calls to. Keeping the injected code to a single
 * static call keeps the patched game methods small and the hooks easy to audit.
 */
public final class FabricHooks {
	private FabricHooks() {
	}

	/** Injected before the registry freeze in BuiltInRegistries.bootStrap(). */
	public static void onRegistriesBootstrapped() {
		FabricRuntimeRegistry.runMain();
	}

	/** Injected at every return of the Minecraft constructor. */
	public static void onClientConstructed(final Minecraft client) {
		FabricRuntimeRegistry.runClient();
		ClientLifecycleEvents.CLIENT_STARTED.invoker().onClientStarted(client);
	}

	public static void onClientTickStart(final Minecraft client) {
		ClientTickEvents.START_CLIENT_TICK.invoker().onStartTick(client);
	}

	public static void onClientTickEnd(final Minecraft client) {
		ClientTickEvents.END_CLIENT_TICK.invoker().onEndTick(client);
	}
}
