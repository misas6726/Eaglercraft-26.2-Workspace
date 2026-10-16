package net.eaglercraft.fabric.tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Applies a subset of SpongePowered Mixin at build time. Supported: @Mixin (class and interface),
 * @Shadow, @Unique, merging of ordinary members, @Inject (HEAD, RETURN, TAIL, INVOKE),
 * @Redirect (INVOKE), @Overwrite, @Accessor and @Invoker. Anything else is reported, not guessed at.
 *
 * <p>There is no refmap or name remapping: Minecraft 26.x is unobfuscated, so mods already use the
 * same names as the game source.
 */
final class MixinApplier implements Opcodes {
	/** One mixin class waiting to be applied. */
	record MixinSource(String modId, ClassNode node, int priority, int order) {
	}

	private record Handler(MethodNode method, AnnotationNode annotation, String source) {
	}

	private final ClassIndex index;
	private final Report report;
	/** Patched target classes, with code, keyed by internal name. */
	final Map<String, ClassNode> patched = new HashMap<>();
	/** Mixin interfaces that stay in the program because mod code references them. */
	final Set<String> keptInterfaces = new HashSet<>();
	private int injections;

	MixinApplier(final ClassIndex index, final Report report) {
		this.index = index;
		this.report = report;
	}

	int injectionCount() {
		return this.injections;
	}

	void applyAll(final List<MixinSource> mixins) {
		List<MixinSource> ordered = new ArrayList<>(mixins);
		ordered.sort(Comparator.comparingInt(MixinSource::priority).thenComparingInt(MixinSource::order));
		for (MixinSource mixin : ordered) {
			AnnotationNode mixinAnnotation = Annotations.find(mixin.node().visibleAnnotations, mixin.node().invisibleAnnotations, Annotations.MIXIN);
			if (mixinAnnotation == null) {
				this.report.warn(mixin.node().name + ": listed in a mixin config but has no @Mixin annotation; ignored");
				continue;
			}
			boolean isInterface = (mixin.node().access & ACC_INTERFACE) != 0;
			if (isInterface) {
				this.keptInterfaces.add(mixin.node().name);
			}
			for (String targetName : resolveTargets(mixinAnnotation)) {
				ClassNode target = loadTarget(targetName);
				if (target == null) {
					this.report.error(mixin.node().name + ": mixin target " + targetName + " does not exist in the game or libraries");
					continue;
				}
				try {
					applyOne(mixin, target);
				} catch (RuntimeException e) {
					this.report.error(mixin.node().name + " -> " + targetName + ": " + e.getMessage());
				}
			}
		}
	}

	private List<String> resolveTargets(final AnnotationNode mixinAnnotation) {
		List<String> targets = new ArrayList<>();
		for (Type type : Annotations.types(mixinAnnotation, "value")) {
			targets.add(type.getInternalName());
		}
		for (String name : Annotations.strings(mixinAnnotation, "targets")) {
			targets.add(name.replace('.', '/'));
		}
		return targets;
	}

	private ClassNode loadTarget(final String name) {
		ClassNode node = this.patched.get(name);
		if (node == null) {
			byte[] bytes = this.index.bytes(name);
			if (bytes == null) {
				return null;
			}
			node = Classes.readWithCode(bytes);
			this.patched.put(name, node);
		}
		return node;
	}

	// ---------------------------------------------------------------- applying one mixin to one target

	private void applyOne(final MixinSource source, final ClassNode target) {
		ClassNode mixin = source.node();
		boolean isInterface = (mixin.access & ACC_INTERFACE) != 0;
		if ((target.access & ACC_INTERFACE) != 0) {
			throw new IllegalStateException("mixing into interfaces is not supported");
		}
		String prefix = "fabric$" + sanitize(source.modId()) + "$" + simpleName(mixin.name) + "$";
		Map<String, String> renames = new HashMap<>();

		if (isInterface) {
			if (!target.interfaces.contains(mixin.name)) {
				target.interfaces.add(mixin.name);
			}
		} else {
			for (String iface : mixin.interfaces) {
				if (!target.interfaces.contains(iface)) {
					target.interfaces.add(iface);
				}
			}
		}

		// Fields: shadows must already exist; everything else is copied across.
		for (FieldNode field : mixin.fields) {
			if (hasAnnotation(field.visibleAnnotations, field.invisibleAnnotations, Annotations.SHADOW)) {
				if (findField(target, field.name) == null) {
					this.report.warn(mixin.name + ": @Shadow field " + field.name + " not found in " + target.name);
				}
				continue;
			}
			if (findField(target, field.name) != null) {
				throw new IllegalStateException("field " + field.name + " already exists in the target (use a different name or @Shadow)");
			}
			FieldNode copy = new FieldNode(field.access, field.name, field.desc, field.signature, field.value);
			target.fields.add(copy);
		}

		// Classify methods.
		List<MethodNode> merged = new ArrayList<>();
		List<Handler> injects = new ArrayList<>();
		List<Handler> redirects = new ArrayList<>();
		List<Handler> overwrites = new ArrayList<>();
		List<Handler> accessors = new ArrayList<>();
		MethodNode mixinClinit = null;

		for (MethodNode method : mixin.methods) {
			if (method.name.equals("<init>")) {
				continue;
			}
			if (method.name.equals("<clinit>")) {
				mixinClinit = method;
				continue;
			}
			List<String> descs = Annotations.descriptors(method.visibleAnnotations, method.invisibleAnnotations);
			if (descs.contains(Annotations.SHADOW)) {
				if (findMethod(target, method.name, method.desc) == null) {
					this.report.warn(mixin.name + ": @Shadow method " + method.name + method.desc + " not found in " + target.name);
				}
				continue;
			}
			AnnotationNode inject = Annotations.find(method.visibleAnnotations, method.invisibleAnnotations, Annotations.INJECT);
			AnnotationNode redirect = Annotations.find(method.visibleAnnotations, method.invisibleAnnotations, Annotations.REDIRECT);
			AnnotationNode overwrite = Annotations.find(method.visibleAnnotations, method.invisibleAnnotations, Annotations.OVERWRITE);
			AnnotationNode accessor = Annotations.find(method.visibleAnnotations, method.invisibleAnnotations, Annotations.ACCESSOR);
			AnnotationNode invoker = Annotations.find(method.visibleAnnotations, method.invisibleAnnotations, Annotations.INVOKER);
			String unsupported = unsupportedInjector(descs);
			if (unsupported != null) {
				this.report.warn(mixin.name + "." + method.name + ": " + unsupported + " is not supported; this injection was skipped");
				continue;
			}
			if (accessor != null || invoker != null) {
				accessors.add(new Handler(method, accessor != null ? accessor : invoker, accessor != null ? "accessor" : "invoker"));
			} else if (overwrite != null) {
				overwrites.add(new Handler(method, overwrite, "overwrite"));
			} else if (inject != null) {
				injects.add(new Handler(method, inject, "inject"));
			} else if (redirect != null) {
				redirects.add(new Handler(method, redirect, "redirect"));
			} else if ((method.access & ACC_ABSTRACT) == 0) {
				merged.add(method);
			}
		}

		// Merge ordinary methods (and lambda bodies), then the handler bodies under unique names.
		for (MethodNode method : merged) {
			String name = method.name;
			boolean unique = hasAnnotation(method.visibleAnnotations, method.invisibleAnnotations, Annotations.UNIQUE);
			if (findMethod(target, name, method.desc) != null || renames.containsKey(name + method.desc)) {
				if (!unique && !name.startsWith("lambda$")) {
					throw new IllegalStateException("method " + name + method.desc + " already exists in the target (use @Unique or a different name)");
				}
				name = prefix + name;
			}
			renames.put(method.name + method.desc, name);
		}
		for (Handler handler : concat(injects, redirects)) {
			renames.put(handler.method().name + handler.method().desc, prefix + handler.method().name);
		}

		for (MethodNode method : merged) {
			addCopy(target, mixin, method, renames, false);
		}
		List<MethodNode> injectorMethods = new ArrayList<>();
		for (Handler handler : concat(injects, redirects)) {
			injectorMethods.add(addCopy(target, mixin, handler.method(), renames, true));
		}
		if (mixinClinit != null) {
			mergeStaticInit(target, mixin, mixinClinit, renames);
		}

		for (Handler handler : overwrites) {
			applyOverwrite(target, mixin, handler, renames);
		}
		for (Handler handler : accessors) {
			generateAccessor(target, mixin, handler);
		}
		int index = 0;
		for (Handler handler : injects) {
			applyInject(target, mixin, handler, injectorMethods.get(index++));
		}
		for (Handler handler : redirects) {
			applyRedirect(target, mixin, handler, injectorMethods.get(index++));
		}
	}

	private static String unsupportedInjector(final List<String> descs) {
		for (String desc : descs) {
			if (desc.startsWith("Lcom/llamalad7/mixinextras/")) {
				return desc.substring(1, desc.length() - 1).replace('/', '.');
			}
			if (desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
					&& !desc.equals(Annotations.INJECT) && !desc.equals(Annotations.REDIRECT)
					&& !desc.endsWith("/At;") && !desc.endsWith("/Slice;") && !desc.endsWith("/Coerce;")) {
				return desc.substring(1, desc.length() - 1).replace('/', '.');
			}
		}
		return null;
	}

	@SafeVarargs
	private static List<Handler> concat(final List<Handler>... lists) {
		List<Handler> all = new ArrayList<>();
		for (List<Handler> list : lists) {
			all.addAll(list);
		}
		return all;
	}

	// ---------------------------------------------------------------- copying members across

	private MethodNode addCopy(final ClassNode target, final ClassNode mixin, final MethodNode source, final Map<String, String> renames,
			final boolean isHandler) {
		String newName = renames.getOrDefault(source.name + source.desc, source.name);
		int access = source.access;
		if (isHandler) {
			// Handlers are private in the mixin; after merging they are called from the target class only.
			access &= ~(ACC_PRIVATE | ACC_PROTECTED | ACC_PUBLIC);
		}
		MethodNode copy = new MethodNode(ASM9, access, newName, source.desc, source.signature,
				source.exceptions == null ? null : source.exceptions.toArray(new String[0]));
		source.accept(copy);
		copy.name = newName;
		copy.access = access;
		copy.visibleAnnotations = null;
		copy.invisibleAnnotations = null;
		remap(copy, mixin.name, target.name, renames);
		target.methods.add(copy);
		return copy;
	}

	private void mergeStaticInit(final ClassNode target, final ClassNode mixin, final MethodNode mixinClinit, final Map<String, String> renames) {
		MethodNode copy = new MethodNode(ASM9, mixinClinit.access, "<clinit>", "()V", null, null);
		mixinClinit.accept(copy);
		remap(copy, mixin.name, target.name, renames);
		boolean trivial = true;
		for (AbstractInsnNode insn : copy.instructions) {
			if (insn.getOpcode() >= 0 && insn.getOpcode() != RETURN) {
				trivial = false;
				break;
			}
		}
		if (trivial) {
			return;
		}
		MethodNode existing = findMethod(target, "<clinit>", "()V");
		if (existing == null) {
			target.methods.add(copy);
			return;
		}
		AbstractInsnNode lastReturn = null;
		for (AbstractInsnNode insn : existing.instructions) {
			if (insn.getOpcode() == RETURN) {
				lastReturn = insn;
			}
		}
		InsnList body = new InsnList();
		for (AbstractInsnNode insn : copy.instructions) {
			if (insn.getOpcode() != RETURN) {
				body.add(insn.clone(new java.util.HashMap<>() {
					@Override
					public org.objectweb.asm.tree.LabelNode get(final Object key) {
						return (org.objectweb.asm.tree.LabelNode) key;
					}
				}));
			}
		}
		existing.maxStack = Math.max(existing.maxStack, copy.maxStack);
		existing.maxLocals = Math.max(existing.maxLocals, copy.maxLocals);
		if (lastReturn != null) {
			existing.instructions.insertBefore(lastReturn, body);
		} else {
			existing.instructions.add(body);
		}
	}

	/** Rewrites every reference to the mixin class (and to methods that were renamed on merge) so it points at the target. */
	private static void remap(final MethodNode method, final String mixinName, final String targetName, final Map<String, String> renames) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode m) {
				if (m.owner.equals(mixinName)) {
					m.owner = targetName;
					m.name = renames.getOrDefault(m.name + m.desc, m.name);
				}
				m.desc = swap(m.desc, mixinName, targetName);
			} else if (insn instanceof FieldInsnNode f) {
				if (f.owner.equals(mixinName)) {
					f.owner = targetName;
				}
				f.desc = swap(f.desc, mixinName, targetName);
			} else if (insn instanceof TypeInsnNode t) {
				if (t.desc.equals(mixinName)) {
					t.desc = targetName;
				} else {
					t.desc = swap(t.desc, mixinName, targetName);
				}
			} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
				ldc.cst = Type.getType(swap(type.getDescriptor(), mixinName, targetName));
			} else if (insn instanceof MultiANewArrayInsnNode multi) {
				multi.desc = swap(multi.desc, mixinName, targetName);
			} else if (insn instanceof InvokeDynamicInsnNode indy) {
				indy.desc = swap(indy.desc, mixinName, targetName);
				for (int i = 0; i < indy.bsmArgs.length; i++) {
					indy.bsmArgs[i] = remapConstant(indy.bsmArgs[i], mixinName, targetName, renames);
				}
			}
		}
		if (method.localVariables != null) {
			for (LocalVariableNode local : method.localVariables) {
				local.desc = swap(local.desc, mixinName, targetName);
				if (local.signature != null) {
					local.signature = swap(local.signature, mixinName, targetName);
				}
			}
		}
		method.desc = swap(method.desc, mixinName, targetName);
	}

	private static Object remapConstant(final Object value, final String mixinName, final String targetName, final Map<String, String> renames) {
		if (value instanceof Handle h) {
			String owner = h.getOwner().equals(mixinName) ? targetName : h.getOwner();
			String name = h.getOwner().equals(mixinName) ? renames.getOrDefault(h.getName() + h.getDesc(), h.getName()) : h.getName();
			return new Handle(h.getTag(), owner, name, swap(h.getDesc(), mixinName, targetName), h.isInterface());
		}
		if (value instanceof Type t) {
			return Type.getType(swap(t.getDescriptor(), mixinName, targetName));
		}
		return value;
	}

	private static String swap(final String desc, final String from, final String to) {
		return desc.replace("L" + from + ";", "L" + to + ";");
	}

	// ---------------------------------------------------------------- @Overwrite

	private void applyOverwrite(final ClassNode target, final ClassNode mixin, final Handler handler, final Map<String, String> renames) {
		MethodNode source = handler.method();
		MethodNode existing = findMethod(target, source.name, source.desc);
		if (existing == null) {
			throw new IllegalStateException("@Overwrite " + source.name + source.desc + " has no matching method in the target");
		}
		MethodNode copy = new MethodNode(ASM9, existing.access, existing.name, existing.desc, existing.signature,
				existing.exceptions == null ? null : existing.exceptions.toArray(new String[0]));
		source.accept(copy);
		remap(copy, mixin.name, target.name, renames);
		existing.instructions = copy.instructions;
		existing.tryCatchBlocks = copy.tryCatchBlocks;
		existing.localVariables = copy.localVariables;
		existing.maxStack = copy.maxStack;
		existing.maxLocals = copy.maxLocals;
		this.injections++;
	}

	// ---------------------------------------------------------------- @Accessor / @Invoker

	private void generateAccessor(final ClassNode target, final ClassNode mixin, final Handler handler) {
		MethodNode source = handler.method();
		if ((source.access & ACC_STATIC) != 0) {
			throw new IllegalStateException(source.name + ": static @Accessor/@Invoker is not supported");
		}
		MethodNode existing = findMethod(target, source.name, source.desc);
		if (existing != null) {
			return;
		}
		boolean invoker = handler.source().equals("invoker");
		String named = Annotations.string(handler.annotation(), "value");
		MethodNode generated = new MethodNode(ASM9, ACC_PUBLIC, source.name, source.desc, source.signature, null);
		InsnList code = generated.instructions;
		Type[] params = Type.getArgumentTypes(source.desc);
		Type ret = Type.getReturnType(source.desc);

		if (invoker) {
			String name = named != null && !named.isEmpty() ? named : inferName(source.name, "invoke", "call");
			MethodNode method = findMethod(target, name, source.desc);
			if (method == null) {
				throw new IllegalStateException("@Invoker " + source.name + ": target has no method " + name + source.desc);
			}
			if ((method.access & ACC_STATIC) != 0) {
				throw new IllegalStateException("@Invoker " + source.name + ": static target methods are not supported");
			}
			code.add(new VarInsnNode(ALOAD, 0));
			int slot = 1;
			for (Type param : params) {
				code.add(new VarInsnNode(param.getOpcode(ILOAD), slot));
				slot += param.getSize();
			}
			boolean isPrivate = (method.access & ACC_PRIVATE) != 0;
			code.add(new MethodInsnNode(isPrivate ? INVOKESPECIAL : INVOKEVIRTUAL, target.name, name, source.desc, false));
			code.add(new InsnNode(ret.getOpcode(IRETURN)));
		} else {
			boolean setter = params.length == 1 && ret.getSort() == Type.VOID;
			boolean getter = params.length == 0 && ret.getSort() != Type.VOID;
			if (!setter && !getter) {
				throw new IllegalStateException("@Accessor " + source.name + source.desc + " is neither a getter nor a setter");
			}
			String name = named != null && !named.isEmpty() ? named : inferName(source.name, "get", "is", "set");
			FieldNode field = findField(target, name);
			if (field == null) {
				throw new IllegalStateException("@Accessor " + source.name + ": target has no field " + name);
			}
			if ((field.access & ACC_STATIC) != 0) {
				throw new IllegalStateException("@Accessor " + source.name + ": static fields are not supported");
			}
			code.add(new VarInsnNode(ALOAD, 0));
			if (getter) {
				code.add(new org.objectweb.asm.tree.FieldInsnNode(GETFIELD, target.name, name, field.desc));
				code.add(new InsnNode(ret.getOpcode(IRETURN)));
			} else {
				code.add(new VarInsnNode(params[0].getOpcode(ILOAD), 1));
				code.add(new org.objectweb.asm.tree.FieldInsnNode(PUTFIELD, target.name, name, field.desc));
				code.add(new InsnNode(RETURN));
			}
		}
		generated.maxStack = 8;
		generated.maxLocals = 8;
		target.methods.add(generated);
		this.injections++;
	}

	private static String inferName(final String methodName, final String... prefixes) {
		for (String prefix : prefixes) {
			if (methodName.startsWith(prefix) && methodName.length() > prefix.length()
					&& Character.isUpperCase(methodName.charAt(prefix.length()))) {
				String rest = methodName.substring(prefix.length());
				return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
			}
		}
		throw new IllegalStateException("cannot infer a target name from " + methodName + "; give the annotation an explicit value");
	}

	// ---------------------------------------------------------------- @Inject

	private void applyInject(final ClassNode target, final ClassNode mixin, final Handler handler, final MethodNode merged) {
		AnnotationNode annotation = handler.annotation();
		List<MethodNode> targets = targetMethods(target, mixin, handler, merged);
		boolean cancellable = Annotations.bool(annotation, "cancellable", false);
		List<AnnotationNode> points = Annotations.annotations(annotation, "at");
		if (points.isEmpty()) {
			throw new IllegalStateException(handler.method().name + ": @Inject has no @At");
		}
		for (MethodNode targetMethod : targets) {
			validateInjectSignature(mixin, handler.method(), targetMethod);
			for (AnnotationNode at : points) {
				injectAt(target, targetMethod, merged, at, cancellable);
			}
		}
	}

	private void injectAt(final ClassNode target, final MethodNode method, final MethodNode handler, final AnnotationNode at, final boolean cancellable) {
		String kind = Annotations.string(at, "value");
		String callbackName = method.name;
		switch (kind == null ? "" : kind) {
			case "HEAD" -> {
				AbstractInsnNode after = method.name.equals("<init>") ? Injector.constructorChainCall(method) : null;
				InsnList code = Injector.buildInject(method, target.name, handler, callbackName, cancellable, false);
				if (method.name.equals("<init>")) {
					if (after == null) {
						throw new IllegalStateException("cannot find the super()/this() call in constructor of " + target.name);
					}
					method.instructions.insert(after, code);
				} else {
					method.instructions.insert(code);
				}
				this.injections++;
			}
			case "RETURN" -> {
				for (AbstractInsnNode ret : Injector.returns(method)) {
					method.instructions.insertBefore(ret, Injector.buildInject(method, target.name, handler, callbackName, cancellable,
							ret.getOpcode() != RETURN));
					this.injections++;
				}
			}
			case "TAIL" -> {
				List<AbstractInsnNode> returns = Injector.returns(method);
				if (!returns.isEmpty()) {
					AbstractInsnNode ret = returns.get(returns.size() - 1);
					method.instructions.insertBefore(ret, Injector.buildInject(method, target.name, handler, callbackName, cancellable,
							ret.getOpcode() != RETURN));
					this.injections++;
				}
			}
			case "INVOKE" -> {
				List<MethodInsnNode> calls = matchInvokes(method, Annotations.string(at, "target"), Annotations.integer(at, "ordinal", -1));
				if (calls.isEmpty()) {
					throw new IllegalStateException("no call matching " + Annotations.string(at, "target") + " in " + method.name + method.desc);
				}
				boolean after = "AFTER".equals(Annotations.enumName(at, "shift"));
				for (MethodInsnNode call : calls) {
					InsnList code = Injector.buildInject(method, target.name, handler, callbackName, cancellable, false);
					if (after) {
						method.instructions.insert(call, code);
					} else {
						method.instructions.insertBefore(call, code);
					}
					this.injections++;
				}
			}
			default -> throw new IllegalStateException("@At(\"" + kind + "\") is not supported (supported: HEAD, RETURN, TAIL, INVOKE)");
		}
	}

	private static void validateInjectSignature(final ClassNode mixin, final MethodNode handler, final MethodNode target) {
		Type[] expected = Type.getArgumentTypes(target.desc);
		Type[] actual = Type.getArgumentTypes(handler.desc);
		boolean returnsValue = Type.getReturnType(target.desc).getSort() != Type.VOID;
		boolean ok = actual.length == expected.length + 1;
		if (ok) {
			for (int i = 0; i < expected.length; i++) {
				ok &= expected[i].equals(actual[i]);
			}
			String callback = actual[actual.length - 1].getInternalName();
			ok &= callback.equals(returnsValue ? Injector.CIR : Injector.CI) || callback.equals(Injector.CI);
		}
		if (!ok) {
			throw new IllegalStateException(mixin.name + "." + handler.name + handler.desc + " does not match target " + target.name + target.desc
					+ " (local variable capture is not supported)");
		}
		if (((handler.access & ACC_STATIC) != 0) != ((target.access & ACC_STATIC) != 0)) {
			throw new IllegalStateException(handler.name + ": handler and target must both be static or both be instance methods");
		}
	}

	// ---------------------------------------------------------------- @Redirect

	private void applyRedirect(final ClassNode target, final ClassNode mixin, final Handler handler, final MethodNode merged) {
		AnnotationNode annotation = handler.annotation();
		AnnotationNode at = Annotations.annotations(annotation, "at").stream().findFirst().orElse(null);
		if (at == null || !"INVOKE".equals(Annotations.string(at, "value"))) {
			throw new IllegalStateException(handler.method().name + ": only @Redirect with @At(\"INVOKE\") is supported");
		}
		if ((merged.access & ACC_STATIC) == 0) {
			for (MethodNode t : targetMethods(target, mixin, handler, merged)) {
				if ((t.access & ACC_STATIC) != 0) {
					throw new IllegalStateException(handler.method().name + ": an instance @Redirect handler cannot be used in static method " + t.name);
				}
			}
		}
		for (MethodNode targetMethod : targetMethods(target, mixin, handler, merged)) {
			List<MethodInsnNode> calls = matchInvokes(targetMethod, Annotations.string(at, "target"), Annotations.integer(at, "ordinal", -1));
			if (calls.isEmpty()) {
				throw new IllegalStateException("no call matching " + Annotations.string(at, "target") + " in " + targetMethod.name + targetMethod.desc);
			}
			for (MethodInsnNode call : calls) {
				Injector.redirect(targetMethod, target.name, call, merged);
				this.injections++;
			}
		}
	}

	// ---------------------------------------------------------------- shared lookup

	private List<MethodNode> targetMethods(final ClassNode target, final ClassNode mixin, final Handler handler, final MethodNode merged) {
		List<String> specs = Annotations.strings(handler.annotation(), "method");
		if (specs.isEmpty()) {
			throw new IllegalStateException(handler.method().name + ": no target method given");
		}
		List<MethodNode> result = new ArrayList<>();
		for (String spec : specs) {
			String name = spec;
			String desc = null;
			int paren = spec.indexOf('(');
			if (paren >= 0) {
				name = spec.substring(0, paren);
				desc = spec.substring(paren);
			}
			boolean found = false;
			for (MethodNode m : target.methods) {
				if (m != merged && m.name.equals(name) && (desc == null || m.desc.equals(desc)) && !m.name.startsWith("fabric$")) {
					if (!result.contains(m)) {
						result.add(m);
					}
					found = true;
				}
			}
			if (!found) {
				throw new IllegalStateException("target method " + spec + " not found in " + target.name);
			}
		}
		return result;
	}

	/** Finds calls matching "Lowner;name(desc)ret" (owner and descriptor optional), honouring an ordinal. */
	private static List<MethodInsnNode> matchInvokes(final MethodNode method, final String spec, final int ordinal) {
		if (spec == null) {
			throw new IllegalStateException("@At(\"INVOKE\") needs a target");
		}
		String owner = null;
		String rest = spec;
		if (spec.startsWith("L") && spec.indexOf(';') > 0) {
			int semi = spec.indexOf(';');
			owner = spec.substring(1, semi);
			rest = spec.substring(semi + 1);
		}
		String name = rest;
		String desc = null;
		int paren = rest.indexOf('(');
		if (paren >= 0) {
			name = rest.substring(0, paren);
			desc = rest.substring(paren);
		}
		List<MethodInsnNode> matches = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode m && m.name.equals(name) && (owner == null || m.owner.equals(owner)) && (desc == null || m.desc.equals(desc))) {
				matches.add(m);
			}
		}
		if (ordinal >= 0) {
			return ordinal < matches.size() ? List.of(matches.get(ordinal)) : List.of();
		}
		return matches;
	}

	private static boolean hasAnnotation(final List<AnnotationNode> visible, final List<AnnotationNode> invisible, final String desc) {
		return Annotations.find(visible, invisible, desc) != null;
	}

	private static MethodNode findMethod(final ClassNode node, final String name, final String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) {
				return m;
			}
		}
		return null;
	}

	private static FieldNode findField(final ClassNode node, final String name) {
		for (FieldNode f : node.fields) {
			if (f.name.equals(name)) {
				return f;
			}
		}
		return null;
	}

	private static String simpleName(final String internalName) {
		return internalName.substring(internalName.lastIndexOf('/') + 1).replace('$', '_');
	}

	private static String sanitize(final String modId) {
		return modId.replaceAll("[^A-Za-z0-9_]", "_");
	}
}
