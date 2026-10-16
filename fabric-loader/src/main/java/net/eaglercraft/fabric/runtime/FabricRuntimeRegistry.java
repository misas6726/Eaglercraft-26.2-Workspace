package net.eaglercraft.fabric.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.ModMetadata;
import net.fabricmc.loader.api.Version;

/**
 * Runtime side of the Fabric loader. Eaglercraft is compiled ahead of time to WebAssembly, so
 * nothing is discovered at runtime: the mod processor generates a class that calls
 * {@link #addMod} and {@link #addEntrypoint} with already-constructed objects, and the hooks it
 * injects into the game call the {@code run*} methods below.
 */
public final class FabricRuntimeRegistry {
	private static final List<ModContainer> MODS = new ArrayList<>();
	private static final List<Entry> ENTRYPOINTS = new ArrayList<>();
	private static final FabricLoader LOADER = new Loader();

	private FabricRuntimeRegistry() {
	}

	public static FabricLoader loader() {
		return LOADER;
	}

	public static void addMod(final String id, final String name, final String version, final String description) {
		MODS.add(new Container(new Metadata(id, name, version, description)));
	}

	public static void addEntrypoint(final String modId, final String key, final Object instance) {
		ENTRYPOINTS.add(new Entry(modId, key, instance));
	}

	/** Entrypoint objects registered under a key, in mod load order. */
	public static List<Object> entrypoints(final String key) {
		List<Object> result = new ArrayList<>();
		for (Entry entry : ENTRYPOINTS) {
			if (entry.key.equals(key)) {
				result.add(entry.instance);
			}
		}
		return result;
	}

	public static void runMain() {
		for (Object entrypoint : entrypoints("main")) {
			((ModInitializer) entrypoint).onInitialize();
		}
	}

	public static void runClient() {
		for (Object entrypoint : entrypoints("client")) {
			((ClientModInitializer) entrypoint).onInitializeClient();
		}
	}

	private record Entry(String modId, String key, Object instance) {
	}

	private record Metadata(String id, String name, String versionString, String description) implements ModMetadata {
		@Override
		public String getId() {
			return this.id;
		}

		@Override
		public String getName() {
			return this.name;
		}

		@Override
		public String getDescription() {
			return this.description;
		}

		@Override
		public Version getVersion() {
			return new SimpleVersion(this.versionString);
		}
	}

	private record SimpleVersion(String value) implements Version {
		@Override
		public String getFriendlyString() {
			return this.value;
		}

		@Override
		public int compareTo(final Version other) {
			return this.value.compareTo(other.getFriendlyString());
		}
	}

	private record Container(ModMetadata metadata) implements ModContainer {
		@Override
		public ModMetadata getMetadata() {
			return this.metadata;
		}
	}

	private static final class Loader implements FabricLoader {
		@Override
		public boolean isModLoaded(final String id) {
			return this.getModContainer(id).isPresent();
		}

		@Override
		public Optional<ModContainer> getModContainer(final String id) {
			for (ModContainer mod : MODS) {
				if (mod.getMetadata().getId().equals(id)) {
					return Optional.of(mod);
				}
			}
			return Optional.empty();
		}

		@Override
		public Collection<ModContainer> getAllMods() {
			return Collections.unmodifiableList(MODS);
		}

		@Override
		public EnvType getEnvironmentType() {
			return EnvType.CLIENT;
		}

		@Override
		public boolean isDevelopmentEnvironment() {
			return false;
		}
	}
}
