package net.eaglercraft.fabric.tool;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Lookup of every class the final program can see: game classes, libraries, the runtime shim and
 * the mods themselves. Used to find mixin targets, to compute stack-map frames without loading
 * classes, and to report references from mods that nothing provides.
 */
final class ClassIndex {
	private interface Source {
		byte[] read() throws IOException;
	}

	private final Map<String, Source> sources = new HashMap<>();
	private final Map<String, ClassNode> nodes = new HashMap<>();
	private final List<ZipFile> openZips = new ArrayList<>();

	/** Adds a directory of .class files or a jar. Earlier entries win, matching classpath order. */
	void addPath(final Path path) throws IOException {
		if (Files.isDirectory(path)) {
			try (Stream<Path> walk = Files.walk(path)) {
				for (Path file : (Iterable<Path>) walk::iterator) {
					String rel = path.relativize(file).toString().replace('\\', '/');
					if (rel.endsWith(".class") && !rel.startsWith("META-INF/")) {
						this.sources.putIfAbsent(rel.substring(0, rel.length() - 6), () -> Files.readAllBytes(file));
					}
				}
			}
		} else if (Files.isRegularFile(path)) {
			ZipFile zip = new ZipFile(path.toFile());
			this.openZips.add(zip);
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
					this.sources.putIfAbsent(name.substring(0, name.length() - 6), () -> {
						try (InputStream in = zip.getInputStream(entry)) {
							return in.readAllBytes();
						}
					});
				}
			}
		}
	}

	/** Registers an in-memory class (a mod class or a generated one). Replaces any earlier entry. */
	void addBytes(final String internalName, final byte[] bytes) {
		this.sources.put(internalName, () -> bytes);
		this.nodes.remove(internalName);
	}

	boolean contains(final String internalName) {
		return this.sources.containsKey(internalName);
	}

	byte[] bytes(final String internalName) {
		Source source = this.sources.get(internalName);
		if (source == null) {
			return null;
		}
		try {
			return source.read();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Signature-only view (no code) of an indexed class, or null if it is not indexed. */
	ClassNode node(final String internalName) {
		ClassNode node = this.nodes.get(internalName);
		if (node == null) {
			byte[] bytes = bytes(internalName);
			if (bytes == null) {
				return null;
			}
			node = Classes.readSignatureOnly(bytes);
			this.nodes.put(internalName, node);
		}
		return node;
	}

	void close() {
		for (ZipFile zip : this.openZips) {
			try {
				zip.close();
			} catch (IOException ignored) {
			}
		}
	}

	/**
	 * Whether {@code owner} declares or inherits the method. Returns null when the answer cannot be
	 * known because the hierarchy leaves the index (for example into the JDK).
	 */
	Boolean hasMethod(final String owner, final String name, final String desc) {
		return hasMember(owner, name, desc, true, new HashSet<>());
	}

	Boolean hasField(final String owner, final String name) {
		return hasMember(owner, name, null, false, new HashSet<>());
	}

	private Boolean hasMember(final String owner, final String name, final String desc, final boolean method, final Set<String> seen) {
		if (!seen.add(owner)) {
			return Boolean.FALSE;
		}
		ClassNode node = node(owner);
		if (node == null) {
			return null;
		}
		if (method) {
			for (MethodNode m : node.methods) {
				if (m.name.equals(name) && m.desc.equals(desc)) {
					return Boolean.TRUE;
				}
			}
		} else {
			for (FieldNode f : node.fields) {
				if (f.name.equals(name)) {
					return Boolean.TRUE;
				}
			}
		}
		boolean unknown = false;
		List<String> parents = new ArrayList<>();
		if (node.superName != null) {
			parents.add(node.superName);
		}
		parents.addAll(node.interfaces);
		for (String parent : parents) {
			Boolean found = hasMember(parent, name, desc, method, seen);
			if (found == null) {
				unknown = true;
			} else if (found) {
				return Boolean.TRUE;
			}
		}
		return unknown ? null : Boolean.FALSE;
	}

	String superName(final String internalName) {
		ClassNode node = node(internalName);
		if (node != null) {
			return node.superName;
		}
		Class<?> reflected = jdkClass(internalName);
		return reflected != null && reflected.getSuperclass() != null ? reflected.getSuperclass().getName().replace('.', '/') : null;
	}

	boolean isInterface(final String internalName) {
		ClassNode node = node(internalName);
		if (node != null) {
			return (node.access & Opcodes.ACC_INTERFACE) != 0;
		}
		Class<?> reflected = jdkClass(internalName);
		return reflected != null && reflected.isInterface();
	}

	/** Common superclass for stack-map frame computation, without loading any game class. */
	String commonSuperClass(final String a, final String b) {
		if (a.equals(b)) {
			return a;
		}
		if (isInterface(a) || isInterface(b)) {
			return "java/lang/Object";
		}
		Set<String> ancestors = new HashSet<>();
		for (String c = a; c != null; c = superName(c)) {
			ancestors.add(c);
		}
		for (String c = b; c != null; c = superName(c)) {
			if (ancestors.contains(c)) {
				return c;
			}
		}
		return "java/lang/Object";
	}

	private static Class<?> jdkClass(final String internalName) {
		try {
			return Class.forName(internalName.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
		} catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
	}
}
