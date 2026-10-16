package net.fabricmc.loader.api;

/** Fabric Loader API shim (subset). Versions are kept as plain strings. */
public interface Version extends Comparable<Version> {
	String getFriendlyString();
}
