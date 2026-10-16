package dev.frozenclient.fabricbridge;

import java.util.List;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Generates the FabricBridgeBootstrap class and wires it into game classes. */
final class Bootstrap {
	static final String NAME = "dev/frozenclient/fabricbridge/FabricBridgeBootstrap";

	private Bootstrap() {
	}

	/** init(): registers mod ids, then runs every "main" entrypoint. initClient(): runs "client" entrypoints. */
	static byte[] generate(final ClassIndex index, final List<ModInfo> mods) {
		ClassWriter cw = index.writer(Opcodes.V17);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, NAME, null, "java/lang/Object", null);

		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "init", "()V", null, null);
		init.visitCode();
		// Idempotent: BuiltInRegistries.freeze() can run once per isolate but be defensive.
		init.visitFieldInsn(Opcodes.GETSTATIC, NAME, "initialized", "Z");
		Label notYet = new Label();
		init.visitJumpInsn(Opcodes.IFEQ, notYet);
		init.visitInsn(Opcodes.RETURN);
		init.visitLabel(notYet);
		init.visitInsn(Opcodes.ICONST_1);
		init.visitFieldInsn(Opcodes.PUTSTATIC, NAME, "initialized", "Z");
		for (ModInfo mod : mods) {
			init.visitLdcInsn(mod.id);
			init.visitMethodInsn(Opcodes.INVOKESTATIC, "net/fabricmc/loader/api/FabricLoader", "registerMod", "(Ljava/lang/String;)V", false);
		}
		for (ModInfo mod : mods) {
			for (String ep : mod.mainEntrypoints) {
				callEntrypoint(init, mod.id, ep, "onInitialize");
			}
		}
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(0, 0);
		init.visitEnd();

		MethodVisitor client = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "initClient", "()V", null, null);
		client.visitCode();
		client.visitFieldInsn(Opcodes.GETSTATIC, NAME, "clientInitialized", "Z");
		Label clientNotYet = new Label();
		client.visitJumpInsn(Opcodes.IFEQ, clientNotYet);
		client.visitInsn(Opcodes.RETURN);
		client.visitLabel(clientNotYet);
		client.visitInsn(Opcodes.ICONST_1);
		client.visitFieldInsn(Opcodes.PUTSTATIC, NAME, "clientInitialized", "Z");
		for (ModInfo mod : mods) {
			for (String ep : mod.clientEntrypoints) {
				callEntrypoint(client, mod.id, ep, "onInitializeClient");
			}
		}
		client.visitInsn(Opcodes.RETURN);
		client.visitMaxs(0, 0);
		client.visitEnd();

		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "initialized", "Z", null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "clientInitialized", "Z", null, null).visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static void callEntrypoint(final MethodVisitor mv, final String modId, final String cls, final String method) {
		Label start = new Label();
		Label end = new Label();
		Label handler = new Label();
		Label next = new Label();
		mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
		mv.visitLabel(start);
		mv.visitTypeInsn(Opcodes.NEW, cls);
		mv.visitInsn(Opcodes.DUP);
		mv.visitMethodInsn(Opcodes.INVOKESPECIAL, cls, "<init>", "()V", false);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, cls, method, "()V", false);
		mv.visitLabel(end);
		mv.visitJumpInsn(Opcodes.GOTO, next);
		mv.visitLabel(handler);
		mv.visitVarInsn(Opcodes.ASTORE, 0);
		mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;");
		mv.visitLdcInsn("[fabric-bridge] mod '" + modId + "' entrypoint " + cls.replace('/', '.') + " failed:");
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
		mv.visitVarInsn(Opcodes.ALOAD, 0);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Throwable", "printStackTrace", "()V", false);
		mv.visitLabel(next);
	}

	/**
	 * Insert "BootstrapClass.&lt;callee&gt;()V" at the start of {@code hookClass.hookMethod}. Returns false if the
	 * method does not exist.
	 */
	static boolean hook(final ClassNode hookClass, final String hookMethod, final String callee) {
		int paren = hookMethod.indexOf('(');
		if (paren < 0) {
			throw new IllegalArgumentException("hook method needs a descriptor, e.g. run()V: " + hookMethod);
		}
		String name = hookMethod.substring(0, paren);
		String desc = hookMethod.substring(paren);
		for (MethodNode m : hookClass.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) {
				InsnList code = new InsnList();
				code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, NAME, callee, "()V", false));
				AbstractInsnNode first = m.instructions.getFirst();
				if (first == null) {
					m.instructions.add(code);
				} else {
					m.instructions.insertBefore(first, code);
				}
				return true;
			}
		}
		return false;
	}
}
