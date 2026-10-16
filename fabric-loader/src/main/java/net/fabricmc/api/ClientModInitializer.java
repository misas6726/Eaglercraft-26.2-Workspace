package net.fabricmc.api;

/** Fabric API shim: runs at the end of the Minecraft client constructor. */
public interface ClientModInitializer {
	void onInitializeClient();
}
