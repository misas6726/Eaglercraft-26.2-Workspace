package dev.frozenclient.fabricbridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Build-time application of a practical Mixin subset onto compiled game classes.
 *
 * <p>Supported: {@code @Mixin}, {@code @Shadow} (fields and methods, no prefix), {@code @Unique} and
 * unannotated members (merged), {@code @Overwrite}, {@code @Inject} at HEAD / RETURN / TAIL
 * (cancellable, {@code CallbackInfo} and {@code CallbackInfoReturnable}), and interface
 * {@code @Accessor} / {@code @Invoker} for instance members. Everything else is reported as skipped,
 * never silently dropped.</p>
 */
final class MixinApplier {
	static final String CI = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
	static final String CIR = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
	private static final String P = "Lorg/spongepowered/asm/mixin/";
	private static final String A_MIXIN = P + "Mixin;";
	private static final String A_SHADOW = P + "Shadow;";
	private static final String A_UNIQUE = P + "Unique;";
	private static final String A_OVERWRITE = P + "Overwrite;";
	private static final String A_INJECT = P + "injection/Inject;";
	private static final String A_ACCESSOR = P + "gen/Accessor;";
	private static final String A_INVOKER = P + "gen/Invoker;";

	private final ClassIndex index;
	private final Report report;
	/** Modified target classes, by internal name. Later mixins see earlier mixins' changes. */
	final Map<String, ClassNode> targets = new LinkedHashMap<>();
	/** Accessor interfaces that must be shipped (annotation-stripped). */
	final Map<String, ClassNode> keptInterfaces = new LinkedHashMap<>();

	MixinApplier(final ClassIndex index, final Report report) {
		this.index = index;
		this.report = report;
	}

	// ---------------------------------------------------------------- annotation helpers

	private static AnnotationNode find(final List<AnnotationNode> a, final List<AnnotationNode> b, final String desc) {
		for (List<AnnotationNode> list : java.util.Arrays.asList(a, b)) {
			if (list != null) {
				for (AnnotationNode n : list) {
					if (desc.equals(n.desc)) {
						return n;
					}
				}
			}
		}
		return null;
	}

	static AnnotationNode anno(final ClassNode c, final String desc) {
		return find(c.visibleAnnotations, c.invisibleAnnotations, desc);
	}

	private static AnnotationNode anno(final MethodNode m, final String desc) {
		return find(m.visibleAnnotations, m.invisibleAnnotations, desc);
	}

	private static AnnotationNode anno(final FieldNode f, final String desc) {
		return find(f.visibleAnnotations, f.invisibleAnnotations, desc);
	}

	private static Object val(final AnnotationNode a, final String key) {
		if (a == null || a.values == null) {
			return null;
		}
		for (int i = 0; i + 1 < a.values.size(); i += 2) {
			if (key.equals(a.values.get(i))) {
				return a.values.get(i + 1);
			}
		}
		return null;
	}

	private static List<Object> list(final Object v) {
		List<Object> out = new ArrayList<>();
		if (v instanceof List) {
			out.addAll((List<?>) v);
		} else if (v != null) {
			out.add(v);
		}
		return out;
	}

	static boolean isMixin(final ClassNode c) {
		return anno(c, A_MIXIN) != null;
	}

	private static String simple(final String internal) {
		return internal.substring(internal.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9_]", "_");
	}

	private static void strip(final MethodNode m) {
		m.visibleAnnotations = null;
		m.invisibleAnnotations = null;
		m.visibleParameterAnnotations = null;
		m.invisibleParameterAnnotations = null;
		m.visibleTypeAnnotations = null;
		m.invisibleTypeAnnotations = null;
	}

	private static void strip(final FieldNode f) {
		f.visibleAnnotations = null;
		f.invisibleAnnotations = null;
		f.visibleTypeAnnotations = null;
		f.invisibleTypeAnnotations = null;
	}

	// ---------------------------------------------------------------- entry point

	void apply(final ModInfo mod, final ClassNode mixin) {
		AnnotationNode m = anno(mixin, A_MIXIN);
		List<String> names = new ArrayList<>();
		for (Object o : list(val(m, "value"))) {
			names.add(((Type) o).getInternalName());
		}
		for (Object o : list(val(m, "targets"))) {
			names.add(((String) o).replace('.', '/'));
		}
		String label = mod.id + ": " + mixin.name;
		if (names.isEmpty()) {
			this.report.skipped(label + " has no resolvable target");
			return;
		}
		boolean isInterface = (mixin.access & Opcodes.ACC_INTERFACE) != 0;
		for (String targetName : names) {
			ClassNode target = this.targets.get(targetName);
			if (target == null) {
				target = this.index.load(targetName);
				if (target == null) {
					this.report.skipped(label + " -> " + targetName + ": target class does not exist in this client");
					continue;
				}
				this.targets.put(targetName, target);
			}
			if ((target.access & Opcodes.ACC_INTERFACE) != 0) {
				this.report.skipped(label + " -> " + targetName + ": interface targets are not supported");
				continue;
			}
			if (isInterface) {
				applyAccessor(mod, mixin, target);
			} else {
				applyClass(mod, mixin, target);
			}
		}
		if (isInterface) {
			ClassNode keep = new ClassNode();
			mixin.accept(keep);
			keep.visibleAnnotations = null;
			keep.invisibleAnnotations = null;
			for (MethodNode mn : keep.methods) {
				strip(mn);
			}
			this.keptInterfaces.put(mixin.name, keep);
		}
	}

	// ---------------------------------------------------------------- class mixins

	private static MethodNode method(final ClassNode c, final String name, final String desc) {
		for (MethodNode m : c.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) {
				return m;
			}
		}
		return null;
	}

	private static FieldNode field(final ClassNode c, final String name) {
		for (FieldNode f : c.fields) {
			if (f.name.equals(name)) {
				return f;
			}
		}
		return null;
	}

	private void applyClass(final ModInfo mod, final ClassNode mixin, final ClassNode target) {
		String label = mod.id + ": " + mixin.name + " -> " + target.name;
		String prefix = "fb$" + mod.id.replaceAll("[^A-Za-z0-9_]", "_") + "$" + simple(mixin.name) + "$";

		// Names that must not collide with the target: every inject handler, and any plain/unique
		// member whose signature the target already has.
		Map<String, String> renames = new HashMap<>();
		for (MethodNode mn : mixin.methods) {
			boolean special = anno(mn, A_SHADOW) != null || anno(mn, A_OVERWRITE) != null;
			boolean init = mn.name.startsWith("<");
			if (anno(mn, A_INJECT) != null || (!special && !init && method(target, mn.name, mn.desc) != null)) {
				renames.put(mn.name + mn.desc, prefix + mn.name);
			}
		}

		ClassNode body = new ClassNode();
		mixin.accept(new ClassRemapper(body, new Remapper() {
			@Override
			public String map(final String internalName) {
				return internalName.equals(mixin.name) ? target.name : internalName;
			}

			@Override
			public String mapMethodName(final String owner, final String name, final String desc) {
				return owner.equals(mixin.name) ? renames.getOrDefault(name + desc, name) : name;
			}
		}));

		for (FieldNode f : body.fields) {
			if (anno(f, A_SHADOW) != null) {
				continue;
			}
			if (field(target, f.name) != null) {
				this.report.skipped(label + ": field '" + f.name + "' already exists in target (mark it @Shadow or rename it)");
				continue;
			}
			strip(f);
			target.fields.add(f);
		}

		List<Object[]> injections = new ArrayList<>();
		for (MethodNode mn : body.methods) {
			if (mn.name.equals("<init>") || mn.name.equals("<clinit>")) {
				continue;
			}
			if (anno(mn, A_SHADOW) != null) {
				continue;
			}
			AnnotationNode inject = anno(mn, A_INJECT);
			AnnotationNode overwrite = anno(mn, A_OVERWRITE);
			if ((mn.access & Opcodes.ACC_ABSTRACT) != 0) {
				this.report.skipped(label + ": abstract method '" + mn.name + "' ignored");
				continue;
			}
			if (overwrite != null) {
				MethodNode old = method(target, mn.name, mn.desc);
				if (old == null) {
					this.report.skipped(label + ": @Overwrite " + mn.name + mn.desc + " matches no target method");
					continue;
				}
				if (((old.access ^ mn.access) & Opcodes.ACC_STATIC) != 0) {
					this.report.skipped(label + ": @Overwrite " + mn.name + " static-ness differs from target");
					continue;
				}
				target.methods.remove(old);
				strip(mn);
				target.methods.add(mn);
				this.report.applied(label + ": @Overwrite " + mn.name + mn.desc);
				continue;
			}
			strip(mn);
			target.methods.add(mn);
			if (inject != null) {
				injections.add(new Object[] { mn, inject });
			}
		}
		for (Object[] pair : injections) {
			applyInject(label, target, (MethodNode) pair[0], (AnnotationNode) pair[1]);
		}
	}

	// ---------------------------------------------------------------- injection

	private static boolean isCi(final Type t) {
		return t.getSort() == Type.OBJECT && (t.getInternalName().equals(CI) || t.getInternalName().equals(CIR));
	}

	private void applyInject(final String label, final ClassNode target, final MethodNode handler, final AnnotationNode inj) {
		String hname = handler.name;
		Type[] hargs = Type.getArgumentTypes(handler.desc);
		if (hargs.length == 0 || !isCi(hargs[hargs.length - 1])) {
			this.report.skipped(label + ": @Inject " + hname + " must end with a CallbackInfo parameter (captured locals are unsupported)");
			return;
		}
		boolean returnable = hargs[hargs.length - 1].getInternalName().equals(CIR);
		boolean cancellable = Boolean.TRUE.equals(val(inj, "cancellable"));
		List<String> kinds = new ArrayList<>();
		for (Object o : list(val(inj, "at"))) {
			Object v = o instanceof AnnotationNode ? val((AnnotationNode) o, "value") : null;
			kinds.add(v instanceof String ? (String) v : "?");
		}
		if (kinds.isEmpty()) {
			this.report.skipped(label + ": @Inject " + hname + " has no @At");
			return;
		}
		for (Object sel : list(val(inj, "method"))) {
			String s = (String) sel;
			int paren = s.indexOf('(');
			String name = paren < 0 ? s : s.substring(0, paren);
			String desc = paren < 0 ? null : s.substring(paren);
			int semi = name.indexOf(';');
			if (name.startsWith("L") && semi > 0) {
				name = name.substring(semi + 1);
			}
			List<MethodNode> matches = new ArrayList<>();
			for (MethodNode tm : target.methods) {
				if (tm.name.equals(name) && (desc == null || tm.desc.equals(desc)) && tm != handler) {
					matches.add(tm);
				}
			}
			if (matches.isEmpty()) {
				this.report.skipped(label + ": @Inject target '" + s + "' not found");
				continue;
			}
			for (MethodNode tm : matches) {
				if (tm.name.startsWith("<")) {
					this.report.skipped(label + ": @Inject into " + tm.name + " is not supported");
					continue;
				}
				if (!signatureFits(handler, hargs, returnable, tm)) {
					this.report.skipped(label + ": @Inject " + hname + " signature does not fit " + tm.name + tm.desc);
					continue;
				}
				for (String kind : kinds) {
					switch (kind) {
						case "HEAD" -> {
							injectHead(target, tm, handler, returnable, cancellable);
							this.report.applied(label + ": @Inject HEAD " + tm.name + tm.desc);
						}
						case "RETURN" -> {
							int n = injectReturns(target, tm, handler, returnable, cancellable, false);
							this.report.applied(label + ": @Inject RETURN " + tm.name + tm.desc + " (" + n + " site(s))");
						}
						case "TAIL" -> {
							int n = injectReturns(target, tm, handler, returnable, cancellable, true);
							this.report.applied(label + ": @Inject TAIL " + tm.name + tm.desc + " (" + n + " site(s))");
						}
						default -> this.report.skipped(label + ": @At(\"" + kind + "\") on " + tm.name + " is not supported (HEAD, RETURN, TAIL only)");
					}
				}
			}
		}
	}

	private static boolean signatureFits(final MethodNode handler, final Type[] hargs, final boolean returnable, final MethodNode tm) {
		Type[] targs = Type.getArgumentTypes(tm.desc);
		if (hargs.length != targs.length + 1) {
			return false;
		}
		for (int i = 0; i < targs.length; i++) {
			if (!hargs[i].equals(targs[i])) {
				return false;
			}
		}
		boolean isVoid = Type.getReturnType(tm.desc).getSort() == Type.VOID;
		if (returnable == isVoid) {
			return false;
		}
		boolean tStatic = (tm.access & Opcodes.ACC_STATIC) != 0;
		boolean hStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		return !tStatic || hStatic;
	}

	private static void loadArgs(final InsnList out, final MethodNode tm, final MethodNode handler) {
		boolean tStatic = (tm.access & Opcodes.ACC_STATIC) != 0;
		boolean hStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		int slot = tStatic ? 0 : 1;
		if (!hStatic) {
			out.add(new VarInsnNode(Opcodes.ALOAD, 0));
		}
		for (Type t : Type.getArgumentTypes(tm.desc)) {
			out.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
			slot += t.getSize();
		}
	}

	private static void callHandler(final InsnList out, final ClassNode target, final MethodNode handler) {
		int op = (handler.access & Opcodes.ACC_STATIC) != 0 ? Opcodes.INVOKESTATIC
			: (handler.access & Opcodes.ACC_PRIVATE) != 0 ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL;
		out.add(new MethodInsnNode(op, target.name, handler.name, handler.desc, false));
	}

	private static void newCallbackInfo(final InsnList out, final String id, final boolean returnable, final boolean cancellable,
			final int rvLocal) {
		String cls = returnable ? CIR : CI;
		out.add(new TypeInsnNode(Opcodes.NEW, cls));
		out.add(new InsnNode(Opcodes.DUP));
		out.add(new LdcInsnNode(id));
		out.add(new InsnNode(cancellable ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
		if (rvLocal >= 0) {
			out.add(new VarInsnNode(Opcodes.ALOAD, rvLocal));
			out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, cls, "<init>", "(Ljava/lang/String;ZLjava/lang/Object;)V", false));
		} else {
			out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, cls, "<init>", "(Ljava/lang/String;Z)V", false));
		}
	}

	private static void box(final InsnList out, final Type t) {
		String w = wrapper(t);
		if (w != null) {
			out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, w, "valueOf", "(" + t.getDescriptor() + ")L" + w + ";", false));
		}
	}

	/** Object on stack -> value of type t on stack. */
	private static void unbox(final InsnList out, final Type t) {
		String w = wrapper(t);
		if (w != null) {
			out.add(new TypeInsnNode(Opcodes.CHECKCAST, w));
			out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, w, t.getClassName() + "Value", "()" + t.getDescriptor(), false));
		} else if (!"java/lang/Object".equals(t.getInternalName())) {
			out.add(new TypeInsnNode(Opcodes.CHECKCAST, t.getSort() == Type.ARRAY ? t.getDescriptor() : t.getInternalName()));
		}
	}

	private static String wrapper(final Type t) {
		return switch (t.getSort()) {
			case Type.BOOLEAN -> "java/lang/Boolean";
			case Type.CHAR -> "java/lang/Character";
			case Type.BYTE -> "java/lang/Byte";
			case Type.SHORT -> "java/lang/Short";
			case Type.INT -> "java/lang/Integer";
			case Type.LONG -> "java/lang/Long";
			case Type.FLOAT -> "java/lang/Float";
			case Type.DOUBLE -> "java/lang/Double";
			default -> null;
		};
	}

	private static void emitReturnFromCi(final InsnList out, final Type ret, final int ciLocal) {
		out.add(new VarInsnNode(Opcodes.ALOAD, ciLocal));
		out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CIR, "getReturnValue", "()Ljava/lang/Object;", false));
		unbox(out, ret);
		out.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
	}

	private void injectHead(final ClassNode target, final MethodNode tm, final MethodNode handler, final boolean returnable,
			final boolean cancellable) {
		Type ret = Type.getReturnType(tm.desc);
		int ci = tm.maxLocals;
		tm.maxLocals += 1;
		InsnList code = new InsnList();
		newCallbackInfo(code, tm.name, returnable, cancellable, -1);
		code.add(new VarInsnNode(Opcodes.ASTORE, ci));
		loadArgs(code, tm, handler);
		code.add(new VarInsnNode(Opcodes.ALOAD, ci));
		callHandler(code, target, handler);
		if (cancellable) {
			LabelNode cont = new LabelNode();
			code.add(new VarInsnNode(Opcodes.ALOAD, ci));
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CI, "isCancelled", "()Z", false));
			code.add(new JumpInsnNode(Opcodes.IFEQ, cont));
			if (returnable) {
				emitReturnFromCi(code, ret, ci);
			} else {
				code.add(new InsnNode(Opcodes.RETURN));
			}
			code.add(cont);
		}
		tm.instructions.insertBefore(tm.instructions.getFirst(), code);
	}

	private int injectReturns(final ClassNode target, final MethodNode tm, final MethodNode handler, final boolean returnable,
			final boolean cancellable, final boolean lastOnly) {
		Type ret = Type.getReturnType(tm.desc);
		List<AbstractInsnNode> sites = new ArrayList<>();
		for (AbstractInsnNode n : tm.instructions) {
			if (n.getOpcode() >= Opcodes.IRETURN && n.getOpcode() <= Opcodes.RETURN) {
				sites.add(n);
			}
		}
		if (lastOnly && sites.size() > 1) {
			sites = sites.subList(sites.size() - 1, sites.size());
		}
		for (AbstractInsnNode site : sites) {
			int ci = tm.maxLocals;
			int rv = ci + 1;
			tm.maxLocals += 2;
			InsnList code = new InsnList();
			if (returnable) {
				box(code, ret);
				code.add(new VarInsnNode(Opcodes.ASTORE, rv));
				newCallbackInfo(code, tm.name, true, cancellable, rv);
			} else {
				newCallbackInfo(code, tm.name, false, cancellable, -1);
			}
			code.add(new VarInsnNode(Opcodes.ASTORE, ci));
			loadArgs(code, tm, handler);
			code.add(new VarInsnNode(Opcodes.ALOAD, ci));
			callHandler(code, target, handler);
			if (returnable) {
				emitReturnFromCi(code, ret, ci);
				tm.instructions.insertBefore(site, code);
				tm.instructions.remove(site);
			} else {
				tm.instructions.insertBefore(site, code);
			}
		}
		return sites.size();
	}

	// ---------------------------------------------------------------- accessors / invokers

	private void applyAccessor(final ModInfo mod, final ClassNode mixin, final ClassNode target) {
		String label = mod.id + ": " + mixin.name + " -> " + target.name;
		if (!target.interfaces.contains(mixin.name)) {
			target.interfaces.add(mixin.name);
		}
		for (MethodNode am : mixin.methods) {
			AnnotationNode acc = anno(am, A_ACCESSOR);
			AnnotationNode inv = anno(am, A_INVOKER);
			if (acc == null && inv == null) {
				continue;
			}
			if ((am.access & Opcodes.ACC_STATIC) != 0) {
				this.report.skipped(label + ": static accessor/invoker " + am.name + " is not supported");
				continue;
			}
			if (method(target, am.name, am.desc) != null) {
				this.report.skipped(label + ": target already has " + am.name + am.desc);
				continue;
			}
			MethodNode impl = new MethodNode(Opcodes.ACC_PUBLIC, am.name, am.desc, null, null);
			InsnList c = impl.instructions;
			Type[] args = Type.getArgumentTypes(am.desc);
			Type ret = Type.getReturnType(am.desc);
			if (acc != null) {
				String fname = (String) val(acc, "value");
				if (fname == null) {
					fname = derive(am.name, "get", "is", "set");
				}
				FieldNode f = field(target, fname);
				if (f == null) {
					this.report.skipped(label + ": @Accessor field '" + fname + "' not found");
					continue;
				}
				if ((f.access & Opcodes.ACC_STATIC) != 0) {
					this.report.skipped(label + ": @Accessor on static field '" + fname + "' is not supported");
					continue;
				}
				boolean getter = args.length == 0 && ret.getSort() != Type.VOID;
				boolean setter = args.length == 1 && ret.getSort() == Type.VOID;
				if (!(getter && ret.getDescriptor().equals(f.desc)) && !(setter && args[0].getDescriptor().equals(f.desc))) {
					this.report.skipped(label + ": @Accessor " + am.name + am.desc + " does not match field type " + f.desc);
					continue;
				}
				c.add(new VarInsnNode(Opcodes.ALOAD, 0));
				if (getter) {
					c.add(new FieldInsnNode(Opcodes.GETFIELD, target.name, f.name, f.desc));
					c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
				} else {
					c.add(new VarInsnNode(args[0].getOpcode(Opcodes.ILOAD), 1));
					c.add(new FieldInsnNode(Opcodes.PUTFIELD, target.name, f.name, f.desc));
					c.add(new InsnNode(Opcodes.RETURN));
					f.access &= ~Opcodes.ACC_FINAL;
				}
			} else {
				String mname = (String) val(inv, "value");
				if (mname == null) {
					mname = derive(am.name, "call", "invoke");
				}
				MethodNode tm = method(target, mname, am.desc);
				if (tm == null) {
					this.report.skipped(label + ": @Invoker target '" + mname + am.desc + "' not found");
					continue;
				}
				if ((tm.access & Opcodes.ACC_STATIC) != 0) {
					this.report.skipped(label + ": @Invoker on static method '" + mname + "' is not supported");
					continue;
				}
				c.add(new VarInsnNode(Opcodes.ALOAD, 0));
				int slot = 1;
				for (Type t : args) {
					c.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot));
					slot += t.getSize();
				}
				int op = (tm.access & Opcodes.ACC_PRIVATE) != 0 ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL;
				c.add(new MethodInsnNode(op, target.name, tm.name, tm.desc, false));
				c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
			}
			impl.maxLocals = 1 + Type.getArgumentsAndReturnSizes(am.desc) / 4 + 1;
			impl.maxStack = 4;
			target.methods.add(impl);
			this.report.applied(label + ": " + (acc != null ? "@Accessor " : "@Invoker ") + am.name + am.desc);
		}
	}

	private static String derive(final String name, final String... prefixes) {
		for (String p : prefixes) {
			if (name.startsWith(p) && name.length() > p.length() && Character.isUpperCase(name.charAt(p.length()))) {
				String rest = name.substring(p.length());
				return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
			}
		}
		return name;
	}
}
