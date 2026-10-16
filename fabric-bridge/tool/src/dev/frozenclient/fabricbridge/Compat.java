package dev.frozenclient.fabricbridge;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Scans mod bytecode for things that cannot work in this client and for game members that do not exist. */
final class Compat {
	private static final Set<String> SHIMS = Set.of(
		"net/fabricmc/api/ModInitializer", "net/fabricmc/api/ClientModInitializer", "net/fabricmc/api/EnvType",
		"net/fabricmc/api/Environment", "net/fabricmc/loader/api/FabricLoader");
	private static final String[] BROWSER_UNFRIENDLY = {
		"java/io/File", "java/nio/file/", "java/net/Socket", "java/net/ServerSocket", "java/net/URLClassLoader",
		"java/net/HttpURLConnection", "java/lang/reflect/", "java/lang/ClassLoader", "java/lang/invoke/",
		"java/lang/ProcessBuilder", "sun/", "jdk/internal/" };

	private final ClassIndex index;
	private final Report report;

	Compat(final ClassIndex index, final Report report) {
		this.index = index;
		this.report = report;
	}

	void scan(final ModInfo mod, final Map<String, byte[]> classes, final Set<String> mixinNames) {
		Set<String> fabricApi = new TreeSet<>();
		Set<String> loader = new TreeSet<>();
		Set<String> unfriendly = new TreeSet<>();
		Set<String> missingClasses = new TreeSet<>();
		Set<String> missingMembers = new TreeSet<>();

		for (Map.Entry<String, byte[]> e : classes.entrySet()) {
			ClassNode cn = new ClassNode();
			new ClassReader(e.getValue()).accept(cn, ClassReader.SKIP_FRAMES);
			boolean mixin = mixinNames.contains(cn.name);
			for (FieldNode f : cn.fields) {
				type(Type.getType(f.desc), cn.name, fabricApi, loader, unfriendly, missingClasses);
			}
			for (MethodNode m : cn.methods) {
				for (Type t : Type.getArgumentTypes(m.desc)) {
					type(t, cn.name, fabricApi, loader, unfriendly, missingClasses);
				}
				type(Type.getReturnType(m.desc), cn.name, fabricApi, loader, unfriendly, missingClasses);
				for (AbstractInsnNode n : m.instructions) {
					if (n instanceof MethodInsnNode mi) {
						owner(mi.owner, cn.name, fabricApi, loader, unfriendly, missingClasses);
						if (!mi.owner.equals(cn.name) && !mixinNames.contains(mi.owner) && ClassIndex.isKnownGameNamespace(mi.owner)
							&& this.index.exists(mi.owner) && !this.index.hasMethod(mi.owner, mi.name, mi.desc)) {
							missingMembers.add(mi.owner + "." + mi.name + mi.desc);
						}
					} else if (n instanceof FieldInsnNode fi) {
						owner(fi.owner, cn.name, fabricApi, loader, unfriendly, missingClasses);
						if (!fi.owner.equals(cn.name) && !mixinNames.contains(fi.owner) && ClassIndex.isKnownGameNamespace(fi.owner)
							&& this.index.exists(fi.owner) && !this.index.hasField(fi.owner, fi.name)) {
							missingMembers.add(fi.owner + "." + fi.name);
						}
					} else if (n instanceof TypeInsnNode ti) {
						owner(ti.desc, cn.name, fabricApi, loader, unfriendly, missingClasses);
					} else if (n instanceof LdcInsnNode li && li.cst instanceof Type t) {
						type(t, cn.name, fabricApi, loader, unfriendly, missingClasses);
					}
				}
			}
			if (mixin) {
				continue;
			}
		}
		for (String s : fabricApi) {
			this.report.compat(mod.id + ": uses Fabric API class " + s + " (Fabric API is not provided; this mod will not link)");
		}
		for (String s : loader) {
			this.report.compat(mod.id + ": uses Fabric Loader class " + s + " (no shim; only FabricLoader basics exist)");
		}
		for (String s : unfriendly) {
			this.report.compat(mod.id + ": references " + s + " (unavailable or unreliable in the browser build)");
		}
		for (String s : missingClasses) {
			this.report.compat(mod.id + ": references game class " + s + " which does not exist in this client");
		}
		for (String s : missingMembers) {
			this.report.compat(mod.id + ": references game member " + s + " which does not exist in this client");
		}
	}

	private void type(final Type t, final String self, final Set<String> api, final Set<String> loader, final Set<String> bad,
			final Set<String> missing) {
		Type base = t.getSort() == Type.ARRAY ? t.getElementType() : t;
		if (base.getSort() == Type.OBJECT) {
			owner(base.getInternalName(), self, api, loader, bad, missing);
		}
	}

	private void owner(String o, final String self, final Set<String> api, final Set<String> loader, final Set<String> bad,
			final Set<String> missing) {
		if (o.startsWith("[")) {
			Type t = Type.getObjectType(o);
			if (t.getElementType().getSort() != Type.OBJECT) {
				return;
			}
			o = t.getElementType().getInternalName();
		}
		if (o.equals(self)) {
			return;
		}
		if (o.startsWith("net/fabricmc/fabric/")) {
			api.add(o);
		} else if (o.startsWith("net/fabricmc/") && !SHIMS.contains(o)) {
			loader.add(o);
		} else if (ClassIndex.isKnownGameNamespace(o)) {
			if (!this.index.exists(o)) {
				missing.add(o);
			}
		} else {
			for (String prefix : BROWSER_UNFRIENDLY) {
				if (o.startsWith(prefix)) {
					bad.add(o);
					break;
				}
			}
		}
	}
}
