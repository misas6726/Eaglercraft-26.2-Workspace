package net.fabricmc.api;

/** Fabric main entrypoint. Runs in every isolate (client, singleplayer server, mesh worker) before registries freeze. */
public interface ModInitializer {
	void onInitialize();
}
