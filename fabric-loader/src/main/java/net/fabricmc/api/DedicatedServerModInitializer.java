package net.fabricmc.api;

/**
 * Fabric API shim. Eaglercraft only has an integrated (singleplayer) server, which Fabric
 * never gives dedicated-server initializers, so these are registered but never run.
 */
public interface DedicatedServerModInitializer {
	void onInitializeServer();
}
