package dev.frozenclient.fabricbridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** One Fabric mod jar, fully read into memory. */
final class ModInfo {
	final Path jar;
	String id;
	String version = "?";
	final List<String> mainEntrypoints = new ArrayList<>();
	final List<String> clientEntrypoints = new ArrayList<>();
	/** Mixin config file names that apply to the client (server-only configs are dropped). */
	final List<String> mixinConfigs = new ArrayList<>();
	/** zip path -> bytes, for every non-directory entry. */
	final Map<String, byte[]> files = new LinkedHashMap<>();

	private ModInfo(final Path jar) {
		this.jar = jar;
	}

	static ModInfo read(final Path jar) throws IOException {
		ModInfo mod = new ModInfo(jar);
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			Enumeration<? extends ZipEntry> en = zip.entries();
			while (en.hasMoreElements()) {
				ZipEntry e = en.nextElement();
				if (e.isDirectory()) {
					continue;
				}
				try (InputStream in = zip.getInputStream(e)) {
					mod.files.put(e.getName(), in.readAllBytes());
				}
			}
		}
		byte[] meta = mod.files.get("fabric.mod.json");
		if (meta == null) {
			throw new IOException(jar.getFileName() + ": no fabric.mod.json (not a Fabric mod)");
		}
		Map<String, Object> root = Json.obj(Json.parse(new String(meta, StandardCharsets.UTF_8)));
		mod.id = Json.str(root.get("id"));
		if (mod.id == null) {
			throw new IOException(jar.getFileName() + ": fabric.mod.json has no id");
		}
		if (Json.str(root.get("version")) != null) {
			mod.version = Json.str(root.get("version"));
		}
		Map<String, Object> entry = Json.obj(root.get("entrypoints"));
		collect(entry.get("main"), mod.mainEntrypoints);
		collect(entry.get("client"), mod.clientEntrypoints);
		for (Object m : Json.arr(root.get("mixins"))) {
			if (m instanceof String) {
				mod.mixinConfigs.add((String) m);
			} else {
				Map<String, Object> o = Json.obj(m);
				String env = Json.str(o.get("environment"));
				if (Json.str(o.get("config")) != null && !"server".equals(env)) {
					mod.mixinConfigs.add(Json.str(o.get("config")));
				}
			}
		}
		return mod;
	}

	private static void collect(final Object list, final List<String> out) {
		for (Object o : Json.arr(list)) {
			String v = o instanceof String ? (String) o : Json.str(Json.obj(o).get("value"));
			if (v == null) {
				continue;
			}
			int sep = v.indexOf("::");
			if (sep >= 0) {
				System.err.println("warning: method-reference entrypoint '" + v + "' is not supported; skipped");
				continue;
			}
			out.add(v.replace('.', '/'));
		}
	}

	/** Mixin class internal names listed by this mod's client-applicable mixin configs. */
	List<String> mixinClassNames() {
		List<String> names = new ArrayList<>();
		for (String cfg : this.mixinConfigs) {
			byte[] data = this.files.get(cfg);
			if (data == null) {
				System.err.println("warning: " + this.id + ": mixin config " + cfg + " not found in jar");
				continue;
			}
			Map<String, Object> o = Json.obj(Json.parse(new String(data, StandardCharsets.UTF_8)));
			String pkg = Json.str(o.get("package"));
			pkg = pkg == null ? "" : pkg.replace('.', '/') + "/";
			for (String key : new String[] { "mixins", "client" }) {
				for (Object n : Json.arr(o.get(key))) {
					if (n instanceof String) {
						names.add(pkg + ((String) n).replace('.', '/'));
					}
				}
			}
		}
		return names;
	}
}
