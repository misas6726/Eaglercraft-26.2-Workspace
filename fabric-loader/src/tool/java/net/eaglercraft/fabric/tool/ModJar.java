package net.eaglercraft.fabric.tool;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** The contents of one mod jar (or one jar nested inside a mod jar), parsed from fabric.mod.json. */
final class ModJar {
	/** An entrypoint declared in fabric.mod.json. */
	record Entrypoint(String key, String value, String adapter) {
	}

	final String label;
	final Map<String, byte[]> classes = new LinkedHashMap<>();
	final Map<String, byte[]> resources = new LinkedHashMap<>();
	final List<ModJar> nested = new ArrayList<>();

	Map<String, Object> metadata;
	String id;
	String name;
	String version;
	String description;
	final List<Entrypoint> entrypoints = new ArrayList<>();
	/** Mixin config resource names, with the environment each applies to ("*", "client" or "server"). */
	final Map<String, String> mixinConfigs = new LinkedHashMap<>();

	private ModJar(final String label) {
		this.label = label;
	}

	boolean isMod() {
		return this.id != null;
	}

	static ModJar read(final String label, final InputStream in) throws IOException {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(in)) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (!entry.isDirectory()) {
					entries.put(entry.getName(), zip.readAllBytes());
				}
			}
		}

		ModJar jar = new ModJar(label);
		for (Map.Entry<String, byte[]> e : entries.entrySet()) {
			String name = e.getKey();
			if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
				jar.classes.put(name.substring(0, name.length() - 6), e.getValue());
			} else if (!name.startsWith("META-INF/") && !name.endsWith(".jar")) {
				jar.resources.put(name, e.getValue());
			}
		}

		byte[] metaBytes = entries.get("fabric.mod.json");
		if (metaBytes != null) {
			jar.parseMetadata(new String(metaBytes, StandardCharsets.UTF_8));
			jar.resources.remove("fabric.mod.json");
			// Mixin configs are consumed by the tool and must not be shipped as resources.
			for (String config : jar.mixinConfigs.keySet()) {
				byte[] configBytes = jar.resources.get(config);
				if (configBytes != null) {
					jar.mixinConfigResources.put(config, new String(configBytes, StandardCharsets.UTF_8));
				}
			}
			Object nestedList = jar.metadata.get("jars");
			if (nestedList instanceof List<?> list) {
				for (Object item : list) {
					Map<String, Object> obj = Json.asObject(item);
					String file = obj == null ? null : Json.asString(obj.get("file"));
					byte[] nestedBytes = file == null ? null : entries.get(file);
					if (nestedBytes != null) {
						jar.nested.add(read(label + "!" + file, new ByteArrayInputStream(nestedBytes)));
					}
				}
			}
		}
		return jar;
	}

	/** Raw text of each mixin config declared by this mod, keyed by resource name. */
	final Map<String, String> mixinConfigResources = new LinkedHashMap<>();

	private void parseMetadata(final String json) {
		this.metadata = Json.asObject(Json.parse(json));
		if (this.metadata == null) {
			throw new IllegalArgumentException(this.label + ": fabric.mod.json is not an object");
		}
		this.id = Json.asString(this.metadata.get("id"));
		this.version = String.valueOf(this.metadata.getOrDefault("version", "0"));
		String declaredName = Json.asString(this.metadata.get("name"));
		this.name = declaredName != null ? declaredName : this.id;
		String declaredDescription = Json.asString(this.metadata.get("description"));
		this.description = declaredDescription != null ? declaredDescription : "";

		Map<String, Object> declared = Json.asObject(this.metadata.get("entrypoints"));
		if (declared != null) {
			for (Map.Entry<String, Object> e : declared.entrySet()) {
				List<Object> values = Json.asArray(e.getValue());
				if (values == null) {
					continue;
				}
				for (Object value : values) {
					if (value instanceof String s) {
						this.entrypoints.add(new Entrypoint(e.getKey(), s, "default"));
					} else if (Json.asObject(value) != null) {
						Map<String, Object> obj = Json.asObject(value);
						String adapter = Json.asString(obj.get("adapter"));
						this.entrypoints.add(new Entrypoint(e.getKey(), Json.asString(obj.get("value")), adapter == null ? "default" : adapter));
					}
				}
			}
		}

		List<Object> mixins = Json.asArray(this.metadata.get("mixins"));
		if (mixins != null) {
			for (Object item : mixins) {
				if (item instanceof String s) {
					this.mixinConfigs.put(s, "*");
				} else if (Json.asObject(item) != null) {
					Map<String, Object> obj = Json.asObject(item);
					String config = Json.asString(obj.get("config"));
					String environment = Json.asString(obj.get("environment"));
					if (config != null) {
						this.mixinConfigs.put(config, environment == null ? "*" : environment);
					}
				}
			}
		}
	}
}
