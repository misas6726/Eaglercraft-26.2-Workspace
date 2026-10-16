package net.eaglercraft.fabric.tool;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Bytecode generation for the injection kinds the tool supports. */
final class Injector implements Opcodes {
	static final String CI = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
	static final String CIR = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";

	private Injector() {
	}

	static boolean isReturn(final AbstractInsnNode insn) {
		int op = insn.getOpcode();
		return op >= IRETURN && op <= RETURN;
	}

	static AbstractInsnNode[] insns(final MethodNode method) {
		return method.instructions.toArray();
	}

	/** Allocates {@code size} new local-variable slots after everything the method already uses. */
	static int newLocal(final MethodNode method, final int size) {
		int slot = method.maxLocals;
		method.maxLocals += size;
		return slot;
	}

	/**
	 * The callback-based injection used by @Inject: builds a CallbackInfo, calls the merged handler,
	 * and optionally returns early if the handler cancelled.
	 *
	 * @param holdsReturnValue true when a method return value is currently on the stack (RETURN points)
	 */
	static InsnList buildInject(final MethodNode target, final String targetOwner, final MethodNode handler, final String callbackName,
			final boolean cancellable, final boolean holdsReturnValue) {
		Type[] args = Type.getArgumentTypes(target.desc);
		Type ret = Type.getReturnType(target.desc);
		boolean targetStatic = (target.access & ACC_STATIC) != 0;
		boolean returnsValue = ret.getSort() != Type.VOID;

		InsnList list = new InsnList();
		int ciSlot = newLocal(target, 1);
		int rvSlot = holdsReturnValue ? newLocal(target, ret.getSize()) : -1;

		if (holdsReturnValue) {
			list.add(new VarInsnNode(ret.getOpcode(ISTORE), rvSlot));
		}
		String ciClass = returnsValue ? CIR : CI;
		list.add(new TypeInsnNode(NEW, ciClass));
		list.add(new InsnNode(DUP));
		list.add(new LdcInsnNode(callbackName));
		list.add(new InsnNode(cancellable ? ICONST_1 : ICONST_0));
		if (holdsReturnValue) {
			list.add(new VarInsnNode(ret.getOpcode(ILOAD), rvSlot));
			box(list, ret);
			list.add(new MethodInsnNode(INVOKESPECIAL, ciClass, "<init>", "(Ljava/lang/String;ZLjava/lang/Object;)V", false));
		} else {
			list.add(new MethodInsnNode(INVOKESPECIAL, ciClass, "<init>", "(Ljava/lang/String;Z)V", false));
		}
		list.add(new VarInsnNode(ASTORE, ciSlot));

		boolean handlerStatic = (handler.access & ACC_STATIC) != 0;
		if (!handlerStatic) {
			list.add(new VarInsnNode(ALOAD, 0));
		}
		int slot = targetStatic ? 0 : 1;
		for (Type arg : args) {
			list.add(new VarInsnNode(arg.getOpcode(ILOAD), slot));
			slot += arg.getSize();
		}
		list.add(new VarInsnNode(ALOAD, ciSlot));
		list.add(new MethodInsnNode(handlerStatic ? INVOKESTATIC : INVOKEVIRTUAL, targetOwner, handler.name, handler.desc, false));

		if (cancellable) {
			LabelNode cont = new LabelNode();
			list.add(new VarInsnNode(ALOAD, ciSlot));
			list.add(new MethodInsnNode(INVOKEVIRTUAL, CI, "isCancelled", "()Z", false));
			list.add(new JumpInsnNode(IFEQ, cont));
			if (returnsValue) {
				list.add(new VarInsnNode(ALOAD, ciSlot));
				unboxReturn(list, ret);
				list.add(new InsnNode(ret.getOpcode(IRETURN)));
			} else {
				list.add(new InsnNode(RETURN));
			}
			list.add(cont);
		}
		if (holdsReturnValue) {
			list.add(new VarInsnNode(ret.getOpcode(ILOAD), rvSlot));
		}
		return list;
	}

	/** Calls {@code owner.name(desc)} with no arguments, or with {@code this} when {@code passThis}. */
	static InsnList buildStaticCall(final String owner, final String name, final boolean passThis) {
		InsnList list = new InsnList();
		if (passThis) {
			list.add(new VarInsnNode(ALOAD, 0));
			list.add(new MethodInsnNode(INVOKESTATIC, owner, name, "(Lnet/minecraft/client/Minecraft;)V", false));
		} else {
			list.add(new MethodInsnNode(INVOKESTATIC, owner, name, "()V", false));
		}
		return list;
	}

	/**
	 * Replaces {@code call} with a call to the merged @Redirect handler. A static handler receives
	 * exactly what the original call would have consumed; an instance handler also needs {@code this}
	 * underneath, so the arguments are parked in fresh locals while it is pushed.
	 */
	static void redirect(final MethodNode target, final String targetOwner, final MethodInsnNode call, final MethodNode handler) {
		boolean handlerStatic = (handler.access & ACC_STATIC) != 0;
		if (handlerStatic) {
			target.instructions.set(call, new MethodInsnNode(INVOKESTATIC, targetOwner, handler.name, handler.desc, false));
			return;
		}
		Type[] params = Type.getArgumentTypes(handler.desc);
		int[] slots = new int[params.length];
		for (int i = 0; i < params.length; i++) {
			slots[i] = newLocal(target, params[i].getSize());
		}
		InsnList list = new InsnList();
		for (int i = params.length - 1; i >= 0; i--) {
			list.add(new VarInsnNode(params[i].getOpcode(ISTORE), slots[i]));
		}
		list.add(new VarInsnNode(ALOAD, 0));
		for (int i = 0; i < params.length; i++) {
			list.add(new VarInsnNode(params[i].getOpcode(ILOAD), slots[i]));
		}
		list.add(new MethodInsnNode(INVOKEVIRTUAL, targetOwner, handler.name, handler.desc, false));
		target.instructions.insertBefore(call, list);
		target.instructions.remove(call);
	}

	/**
	 * Instruction after which a constructor may safely run instance code: just past the super() or
	 * this() call. Objects created as arguments to that call are skipped by tracking NEW depth.
	 */
	static AbstractInsnNode constructorChainCall(final MethodNode ctor) {
		int pendingNews = 0;
		for (AbstractInsnNode insn : ctor.instructions) {
			if (insn.getOpcode() == NEW) {
				pendingNews++;
			} else if (insn.getOpcode() == INVOKESPECIAL && ((MethodInsnNode) insn).name.equals("<init>")) {
				if (pendingNews == 0) {
					return insn;
				}
				pendingNews--;
			}
		}
		return null;
	}

	static List<AbstractInsnNode> returns(final MethodNode method) {
		List<AbstractInsnNode> result = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (isReturn(insn)) {
				result.add(insn);
			}
		}
		return result;
	}

	private static void box(final InsnList list, final Type type) {
		switch (type.getSort()) {
			case Type.BOOLEAN -> list.add(valueOf("java/lang/Boolean", "Z"));
			case Type.BYTE -> list.add(valueOf("java/lang/Byte", "B"));
			case Type.CHAR -> list.add(valueOf("java/lang/Character", "C"));
			case Type.SHORT -> list.add(valueOf("java/lang/Short", "S"));
			case Type.INT -> list.add(valueOf("java/lang/Integer", "I"));
			case Type.LONG -> list.add(valueOf("java/lang/Long", "J"));
			case Type.FLOAT -> list.add(valueOf("java/lang/Float", "F"));
			case Type.DOUBLE -> list.add(valueOf("java/lang/Double", "D"));
			default -> {
			}
		}
	}

	private static MethodInsnNode valueOf(final String owner, final String primitive) {
		return new MethodInsnNode(INVOKESTATIC, owner, "valueOf", "(" + primitive + ")L" + owner + ";", false);
	}

	/** Leaves the CallbackInfoReturnable's value on the stack as the method's real return type. */
	private static void unboxReturn(final InsnList list, final Type type) {
		String suffix = switch (type.getSort()) {
			case Type.BOOLEAN -> "Z";
			case Type.BYTE -> "B";
			case Type.CHAR -> "C";
			case Type.SHORT -> "S";
			case Type.INT -> "I";
			case Type.LONG -> "J";
			case Type.FLOAT -> "F";
			case Type.DOUBLE -> "D";
			default -> null;
		};
		if (suffix != null) {
			list.add(new MethodInsnNode(INVOKEVIRTUAL, CIR, "getReturnValue" + suffix, "()" + suffix, false));
		} else {
			list.add(new MethodInsnNode(INVOKEVIRTUAL, CIR, "getReturnValue", "()Ljava/lang/Object;", false));
			list.add(new TypeInsnNode(CHECKCAST, type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName()));
		}
	}
}
