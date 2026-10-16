package net.fabricmc.loader.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.fabricmc.api.EnvType;

/**
 * Minimal FabricLoader surface for the Eaglercraft bridge. The mod list is filled by the
 * generated FabricBridgeBootstrap before any entrypoint runs.
 */
public final class FabricLoader {
	private static final FabricLoader INSTANCE = new FabricLoader();
	private final Set<String> modIds = new HashSet<>();

	private FabricLoader() {
	}

	public static FabricLoader getInstance() {
		return INSTANCE;
	}

	/** Called by generated bootstrap code only. */
	public static void registerMod(final String id) {
		INSTANCE.modIds.add(id);
	}

	public boolean isModLoaded(final String id) {
		return this.modIds.contains(id);
	}

	public Collection<String> getLoadedModIds() {
		List<String> ids = new ArrayList<>(this.modIds);
		Collections.sort(ids);
		return Collections.unmodifiableList(ids);
	}

	public EnvType getEnvironmentType() {
		return EnvType.CLIENT;
	}

	public boolean isDevelopmentEnvironment() {
		return false;
	}
}
