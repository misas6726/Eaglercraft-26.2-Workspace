package dev.frozenclient.fabricbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * Fabric -> Eaglercraft mod bundler.
 *
 * <pre>
 * java -cp tool.jar:asm... dev.frozenclient.fabricbridge.Main
 *     --mods &lt;dir with mod jars&gt; --classes &lt;compiled game classes&gt; --out &lt;output dir&gt;
 *     [--hook-init net/minecraft/core/registries/BuiltInRegistries.freeze()V]
 *     [--hook-client net/minecraft/client/Minecraft.run()V] [--strict]
 * </pre>
 */
public final class Main {
	private Main() {
	}

	public static void main(final String[] args) throws IOException {
		Path modsDir = null;
		Path classesDir = null;
		Path out = null;
		String hookInit = "net/minecraft/core/registries/BuiltInRegistries.freeze()V";
		String hookClient = "net/minecraft/client/Minecraft.run()V";
		boolean strict = false;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--mods" -> modsDir = Paths.get(args[++i]);
				case "--classes" -> classesDir = Paths.get(args[++i]);
				case "--out" -> out = Paths.get(args[++i]);
				case "--hook-init" -> hookInit = args[++i];
				case "--hook-client" -> hookClient = args[++i];
				case "--strict" -> strict = true;
				default -> usage("unknown argument " + args[i]);
			}
		}
		if (modsDir == null || classesDir == null || out == null) {
			usage("--mods, --classes and --out are required");
		}
		System.exit(run(modsDir, classesDir, out, hookInit, hookClient, strict));
	}

	private static void usage(final String msg) {
		System.err.println(msg);
		System.err.println("usage: --mods <dir> --classes <dir> --out <dir> [--hook-init Cls.m()V] [--hook-client Cls.m()V] [--strict]");
		System.exit(2);
	}

	static int run(final Path modsDir, final Path classesDir, final Path out, final String hookInit, final String hookClient,
			final boolean strict) throws IOException {
		Report report = new Report();
		List<Path> jars = new ArrayList<>();
		try (Stream<Path> s = Files.list(modsDir)) {
			s.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(jars::add);
		}
		List<ModInfo> mods = new ArrayList<>();
		for (Path jar : jars) {
			try {
				mods.add(ModInfo.read(jar));
			} catch (IOException | RuntimeException e) {
				report.skipped(jar.getFileName() + ": " + e.getMessage());
			}
		}

		ClassIndex index = new ClassIndex(classesDir);
		// Mod classes participate in hierarchy lookups (a mod class may extend a game class).
		Map<ModInfo, Set<String>> mixinNamesByMod = new LinkedHashMap<>();
		for (ModInfo mod : mods) {
			for (Map.Entry<String, byte[]> e : mod.files.entrySet()) {
				if (e.getKey().endsWith(".class")) {
					index.addExtra(e.getKey().substring(0, e.getKey().length() - 6), e.getValue());
				}
			}
			mixinNamesByMod.put(mod, new HashSet<>(mod.mixinClassNames()));
		}

		MixinApplier applier = new MixinApplier(index, report);
		Compat compat = new Compat(index, report);
		Map<String, byte[]> outClasses = new LinkedHashMap<>();
		Map<String, byte[]> outResources = new LinkedHashMap<>();
		Set<String> removedMixins = new HashSet<>();

		for (ModInfo mod : mods) {
			report.heading("mod " + mod.id + " " + mod.version + " (" + mod.jar.getFileName() + ")");
			Set<String> mixinNames = mixinNamesByMod.get(mod);
			Map<String, byte[]> modClasses = new LinkedHashMap<>();
			for (Map.Entry<String, byte[]> e : mod.files.entrySet()) {
				String path = e.getKey();
				if (path.endsWith(".class")) {
					modClasses.put(path.substring(0, path.length() - 6), e.getValue());
				} else if (path.startsWith("assets/") || path.startsWith("data/")) {
					outResources.put(path, e.getValue());
				}
			}
			compat.scan(mod, modClasses, mixinNames);

			for (String mixinName : mixinNames) {
				byte[] bytes = modClasses.get(mixinName);
				if (bytes == null) {
					report.skipped(mod.id + ": mixin class " + mixinName + " listed in config but missing from jar");
					continue;
				}
				ClassNode cn = new ClassNode();
				new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
				if (!MixinApplier.isMixin(cn)) {
					report.skipped(mod.id + ": " + mixinName + " has no @Mixin annotation");
					continue;
				}
				applier.apply(mod, cn);
				removedMixins.add(mixinName);
			}
			for (Map.Entry<String, byte[]> e : modClasses.entrySet()) {
				if (!removedMixins.contains(e.getKey())) {
					if (index.exists(e.getKey()) && Files.isRegularFile(classesDir.resolve(e.getKey() + ".class"))) {
						report.skipped(mod.id + ": class " + e.getKey() + " would shadow a game class; not copied");
						continue;
					}
					outClasses.put(e.getKey(), e.getValue());
				}
			}
		}

		// Accessor interfaces replace the raw copies (annotations stripped).
		for (Map.Entry<String, ClassNode> e : applier.keptInterfaces.entrySet()) {
			outClasses.put(e.getKey(), write(index, e.getValue()));
		}
		// Mixin classes themselves never ship.
		for (String name : removedMixins) {
			if (!applier.keptInterfaces.containsKey(name)) {
				outClasses.remove(name);
			}
		}

		// Hooks. A hook target may already be a mixin target; reuse the modified node.
		boolean anyEntrypoint = mods.stream().anyMatch(m -> !m.mainEntrypoints.isEmpty() || !m.clientEntrypoints.isEmpty());
		if (!mods.isEmpty()) {
			report.heading("hooks");
			hookMethod(index, applier, report, hookInit, "init");
			hookMethod(index, applier, report, hookClient, "initClient");
			outClasses.put(Bootstrap.NAME, Bootstrap.generate(index, mods));
			if (!anyEntrypoint) {
				report.info("no entrypoints declared; bootstrap only registers mod ids");
			}
		}

		for (Map.Entry<String, ClassNode> e : applier.targets.entrySet()) {
			outClasses.put(e.getKey(), write(index, e.getValue()));
		}

		Path classOut = out.resolve("classes");
		Path resOut = out.resolve("resources");
		deleteTree(classOut);
		deleteTree(resOut);
		for (Map.Entry<String, byte[]> e : outClasses.entrySet()) {
			write(classOut.resolve(e.getKey() + ".class"), e.getValue());
		}
		for (Map.Entry<String, byte[]> e : outResources.entrySet()) {
			write(resOut.resolve(e.getKey()), e.getValue());
		}
		StringBuilder sb = new StringBuilder();
		sb.append("Fabric bridge report\n");
		sb.append("mods: ").append(mods.size()).append(", applied: ").append(report.applied).append(", skipped: ")
			.append(report.skipped).append(", compat warnings: ").append(report.compatWarnings).append('\n');
		for (String l : report.lines) {
			sb.append(l).append('\n');
		}
		Files.writeString(out.resolve("report.txt"), sb.toString(), StandardCharsets.UTF_8);
		System.out.print(sb);
		System.out.println("classes: " + classOut + "  (" + outClasses.size() + " files; put FIRST on the TeaVM program classpath)");
		System.out.println("resources: " + resOut + "  (" + outResources.size() + " files)");
		return strict && (report.skipped > 0 || report.compatWarnings > 0) ? 1 : 0;
	}

	private static void hookMethod(final ClassIndex index, final MixinApplier applier, final Report report, final String spec,
			final String callee) {
		int dot = spec.indexOf('.');
		if (dot < 0) {
			report.skipped("hook '" + spec + "' must look like some/Class.method()V");
			return;
		}
		String cls = spec.substring(0, dot).replace('.', '/');
		ClassNode node = applier.targets.get(cls);
		if (node == null) {
			node = index.load(cls);
			if (node == null) {
				report.skipped("hook class " + cls + " does not exist in this client");
				return;
			}
			applier.targets.put(cls, node);
		}
		if (Bootstrap.hook(node, spec.substring(dot + 1), callee)) {
			report.applied("hook " + cls + "." + spec.substring(dot + 1) + " -> FabricBridgeBootstrap." + callee + "()");
		} else {
			report.skipped("hook method " + spec + " not found");
		}
	}

	private static byte[] write(final ClassIndex index, final ClassNode node) {
		ClassWriter cw = index.writer(node.version);
		node.accept(cw);
		return cw.toByteArray();
	}

	private static void write(final Path p, final byte[] data) throws IOException {
		Files.createDirectories(p.getParent());
		Files.write(p, data);
	}

	private static void deleteTree(final Path dir) throws IOException {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) {
				if (!p.equals(dir)) {
					Files.delete(p);
				}
			}
		}
	}
}
