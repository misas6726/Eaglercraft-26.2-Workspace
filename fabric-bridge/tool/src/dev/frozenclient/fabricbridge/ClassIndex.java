package dev.frozenclient.fabricbridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

/** Read access to the compiled game classes (plus mod classes) for lookups and frame computation. */
final class ClassIndex {
	private final Path dir;
	private final Map<String, byte[]> extra = new HashMap<>();
	private final Map<String, ClassNode> headers = new HashMap<>();

	ClassIndex(final Path dir) {
		this.dir = dir;
	}

	void addExtra(final String internal, final byte[] bytes) {
		this.extra.put(internal, bytes);
		this.headers.remove(internal);
	}

	byte[] bytes(final String internal) {
		byte[] e = this.extra.get(internal);
		if (e != null) {
			return e;
		}
		Path p = this.dir.resolve(internal + ".class");
		try {
			return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
		} catch (IOException ex) {
			throw new IllegalStateException("cannot read " + p, ex);
		}
	}

	boolean exists(final String internal) {
		return this.extra.containsKey(internal) || Files.isRegularFile(this.dir.resolve(internal + ".class"));
	}

	/** Full node with code, frames skipped (frames are always recomputed on write). */
	ClassNode load(final String internal) {
		byte[] b = bytes(internal);
		if (b == null) {
			return null;
		}
		ClassNode node = new ClassNode();
		new ClassReader(b).accept(node, ClassReader.SKIP_FRAMES);
		return node;
	}

	/** Cached node without code, for existence/hierarchy checks. */
	ClassNode header(final String internal) {
		if (this.headers.containsKey(internal)) {
			return this.headers.get(internal);
		}
		byte[] b = bytes(internal);
		ClassNode node = null;
		if (b != null) {
			node = new ClassNode();
			new ClassReader(b).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		}
		this.headers.put(internal, node);
		return node;
	}

	private boolean isInterface(final String name) {
		ClassNode h = header(name);
		if (h != null) {
			return (h.access & Opcodes.ACC_INTERFACE) != 0;
		}
		try {
			return Class.forName(name.replace('/', '.'), false, ClassLoader.getSystemClassLoader()).isInterface();
		} catch (Throwable t) {
			return false;
		}
	}

	private String superOf(final String name) {
		ClassNode h = header(name);
		if (h != null) {
			return h.superName;
		}
		try {
			Class<?> c = Class.forName(name.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
			return c.getSuperclass() == null ? null : c.getSuperclass().getName().replace('.', '/');
		} catch (Throwable t) {
			return "java/lang/Object";
		}
	}

	String commonSuper(final String a, final String b) {
		if (a.equals(b)) {
			return a;
		}
		if (isInterface(a) || isInterface(b)) {
			return "java/lang/Object";
		}
		List<String> chain = new ArrayList<>();
		for (String c = a; c != null; c = superOf(c)) {
			chain.add(c);
		}
		for (String c = b; c != null; c = superOf(c)) {
			if (chain.contains(c)) {
				return c;
			}
		}
		return "java/lang/Object";
	}

	ClassWriter writer(final int version) {
		return new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
			@Override
			protected String getCommonSuperClass(final String t1, final String t2) {
				return commonSuper(t1, t2);
			}
		};
	}

	/** Does owner (or an ancestor) declare the method? Unknown ancestry (JDK) counts as present. */
	boolean hasMethod(final String owner, final String name, final String desc) {
		return find(owner, name, desc, true, new java.util.HashSet<>());
	}

	boolean hasField(final String owner, final String name) {
		return find(owner, name, null, false, new java.util.HashSet<>());
	}

	private boolean find(final String owner, final String name, final String desc, final boolean method, final java.util.Set<String> seen) {
		if (owner == null || !seen.add(owner)) {
			return false;
		}
		if (owner.startsWith("[")) {
			return true;
		}
		ClassNode h = header(owner);
		if (h == null) {
			if (isKnownGameNamespace(owner)) {
				return false;
			}
			// Every game class ends its chain at Object, whose API is stable, so check it for real.
			// Other JDK / third-party ancestors can differ between the tool's JVM and the build's: assume present.
			return !owner.equals("java/lang/Object") || objectHas(name, desc, method);
		}
		if (method) {
			for (org.objectweb.asm.tree.MethodNode m : h.methods) {
				if (m.name.equals(name) && m.desc.equals(desc)) {
					return true;
				}
			}
		} else {
			for (org.objectweb.asm.tree.FieldNode f : h.fields) {
				if (f.name.equals(name)) {
					return true;
				}
			}
		}
		if (find(h.superName, name, desc, method, seen)) {
			return true;
		}
		for (String i : h.interfaces) {
			if (find(i, name, desc, method, seen)) {
				return true;
			}
		}
		return false;
	}

	private static boolean objectHas(final String name, final String desc, final boolean method) {
		if (method) {
			if (name.equals("<init>")) {
				return desc.equals("()V");
			}
			for (java.lang.reflect.Method m : Object.class.getDeclaredMethods()) {
				if (m.getName().equals(name) && Type.getMethodDescriptor(m).equals(desc)) {
					return true;
				}
			}
			return false;
		}
		return false;
	}

	static boolean isKnownGameNamespace(final String internal) {
		return internal.startsWith("net/minecraft/") || internal.startsWith("com/mojang/");
	}
}
