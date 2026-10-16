package net.fabricmc.api;

/** Fabric API shim: runs once, after vanilla registries are filled and before they freeze. */
public interface ModInitializer {
	void onInitialize();
}
