package net.fabricmc.loader.api;

/** Fabric Loader API shim (subset). */
public interface ModMetadata {
	String getId();

	String getName();

	String getDescription();

	Version getVersion();
}
