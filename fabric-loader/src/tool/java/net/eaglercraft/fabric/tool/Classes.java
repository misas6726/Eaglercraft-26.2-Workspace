package net.eaglercraft.fabric.tool;

import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * Reads and writes classes with ASM. The game is compiled for a newer class-file version than the
 * bundled ASM may know, so an unsupported major version is temporarily lowered for reading and
 * restored on write. Frames are skipped when reading and fully recomputed when writing.
 */
final class Classes {
	private static final int FALLBACK_MAJOR = 65;
	private static final Map<String, Integer> ORIGINAL_MAJOR = new HashMap<>();

	private Classes() {
	}

	static ClassNode read(final byte[] bytes, final int flags) {
		byte[] data = bytes;
		int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
		ClassNode node = new ClassNode();
		try {
			new ClassReader(data).accept(node, flags | ClassReader.SKIP_FRAMES);
			return node;
		} catch (IllegalArgumentException unsupportedVersion) {
			data = bytes.clone();
			data[6] = (byte) (FALLBACK_MAJOR >> 8);
			data[7] = (byte) FALLBACK_MAJOR;
			node = new ClassNode();
			new ClassReader(data).accept(node, flags | ClassReader.SKIP_FRAMES);
			ORIGINAL_MAJOR.put(node.name, major);
			return node;
		}
	}

	static ClassNode readWithCode(final byte[] bytes) {
		return read(bytes, 0);
	}

	static ClassNode readSignatureOnly(final byte[] bytes) {
		return read(bytes, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
	}

	/** Writes the class with recomputed frames, restoring the original class-file version. */
	static byte[] write(final ClassNode node, final ClassIndex index) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
			@Override
			protected String getCommonSuperClass(final String a, final String b) {
				return index.commonSuperClass(a, b);
			}
		};
		Integer original = ORIGINAL_MAJOR.get(node.name);
		int savedVersion = node.version;
		node.accept(writer);
		byte[] out = writer.toByteArray();
		node.version = savedVersion;
		if (original != null) {
			out[6] = (byte) (original >> 8);
			out[7] = (byte) (int) original;
		}
		return out;
	}
}
