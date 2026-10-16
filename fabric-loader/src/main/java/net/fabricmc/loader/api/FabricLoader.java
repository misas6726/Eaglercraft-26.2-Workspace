package net.fabricmc.loader.api;

import java.util.Collection;
import java.util.Optional;

import net.eaglercraft.fabric.runtime.FabricRuntimeRegistry;
import net.fabricmc.api.EnvType;

/**
 * Fabric Loader API shim (subset). This is an interface, like the real one, so bytecode compiled
 * against Fabric Loader (invokestatic on an interface, invokeinterface for the instance methods)
 * links unchanged.
 */
public interface FabricLoader {
	static FabricLoader getInstance() {
		return FabricRuntimeRegistry.loader();
	}

	boolean isModLoaded(String id);

	Optional<ModContainer> getModContainer(String id);

	Collection<ModContainer> getAllMods();

	EnvType getEnvironmentType();

	boolean isDevelopmentEnvironment();
}
