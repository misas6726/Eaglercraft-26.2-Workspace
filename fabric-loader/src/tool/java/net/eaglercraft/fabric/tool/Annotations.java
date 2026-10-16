package net.eaglercraft.fabric.tool;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;

/** Small helpers for reading ASM annotation nodes. */
final class Annotations {
	static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
	static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
	static final String UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;";
	static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
	static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
	static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
	static final String ENVIRONMENT = "Lnet/fabricmc/api/Environment;";

	private Annotations() {
	}

	static AnnotationNode find(final List<AnnotationNode> visible, final List<AnnotationNode> invisible, final String desc) {
		for (List<AnnotationNode> list : List.of(visible == null ? List.<AnnotationNode>of() : visible,
				invisible == null ? List.<AnnotationNode>of() : invisible)) {
			for (AnnotationNode annotation : list) {
				if (annotation.desc.equals(desc)) {
					return annotation;
				}
			}
		}
		return null;
	}

	/** All annotation descriptors present on a member or class. */
	static List<String> descriptors(final List<AnnotationNode> visible, final List<AnnotationNode> invisible) {
		List<String> result = new ArrayList<>();
		if (visible != null) {
			visible.forEach(a -> result.add(a.desc));
		}
		if (invisible != null) {
			invisible.forEach(a -> result.add(a.desc));
		}
		return result;
	}

	static Object value(final AnnotationNode annotation, final String key) {
		if (annotation == null || annotation.values == null) {
			return null;
		}
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if (annotation.values.get(i).equals(key)) {
				return annotation.values.get(i + 1);
			}
		}
		return null;
	}

	static String string(final AnnotationNode annotation, final String key) {
		return value(annotation, key) instanceof String s ? s : null;
	}

	static boolean bool(final AnnotationNode annotation, final String key, final boolean fallback) {
		return value(annotation, key) instanceof Boolean b ? b : fallback;
	}

	static int integer(final AnnotationNode annotation, final String key, final int fallback) {
		return value(annotation, key) instanceof Integer i ? i : fallback;
	}

	/** Enum constant name of an annotation value, or null. */
	static String enumName(final AnnotationNode annotation, final String key) {
		return value(annotation, key) instanceof String[] pair && pair.length == 2 ? pair[1] : null;
	}

	/** A value that may be written as a single element or as an array, as a list of raw elements. */
	static List<Object> list(final AnnotationNode annotation, final String key) {
		Object value = value(annotation, key);
		List<Object> result = new ArrayList<>();
		if (value instanceof List<?> items) {
			result.addAll(items);
		} else if (value != null) {
			result.add(value);
		}
		return result;
	}

	static List<String> strings(final AnnotationNode annotation, final String key) {
		List<String> result = new ArrayList<>();
		for (Object item : list(annotation, key)) {
			if (item instanceof String s) {
				result.add(s);
			}
		}
		return result;
	}

	static List<AnnotationNode> annotations(final AnnotationNode annotation, final String key) {
		List<AnnotationNode> result = new ArrayList<>();
		for (Object item : list(annotation, key)) {
			if (item instanceof AnnotationNode a) {
				result.add(a);
			}
		}
		return result;
	}

	static List<Type> types(final AnnotationNode annotation, final String key) {
		List<Type> result = new ArrayList<>();
		for (Object item : list(annotation, key)) {
			if (item instanceof Type t) {
				result.add(t);
			}
		}
		return result;
	}
}
