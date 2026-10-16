package net.fabricmc.api;

/** Fabric client entrypoint. Runs once in the client isolate when the game loop starts. */
public interface ClientModInitializer {
	void onInitializeClient();
}
