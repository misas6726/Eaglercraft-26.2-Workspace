package net.eaglercraft.fabric.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A parsed Mixin config JSON: which classes are mixins and what they apply to. */
final class MixinConfig {
	final String resourceName;
	final String packagePrefix;
	final int priority;
	final boolean hasPlugin;
	/** Internal names of the mixin classes to apply (common + client lists; the server list is skipped). */
	final List<String> mixinClasses = new ArrayList<>();
	final List<String> skippedServerClasses = new ArrayList<>();

	MixinConfig(final String resourceName, final String json) {
		this.resourceName = resourceName;
		Map<String, Object> root = Json.asObject(Json.parse(json));
		if (root == null) {
			throw new IllegalArgumentException(resourceName + ": mixin config is not an object");
		}
		String pkg = Json.asString(root.get("package"));
		this.packagePrefix = pkg == null ? "" : pkg.replace('.', '/') + "/";
		Object declaredPriority = root.get("priority");
		this.priority = declaredPriority instanceof Double d ? d.intValue() : 1000;
		this.hasPlugin = root.get("plugin") != null;
		collect(root.get("mixins"), this.mixinClasses);
		collect(root.get("client"), this.mixinClasses);
		collect(root.get("server"), this.skippedServerClasses);
	}

	private void collect(final Object list, final List<String> into) {
		List<Object> values = Json.asArray(list);
		if (values == null) {
			return;
		}
		for (Object value : values) {
			String name = Json.asString(value);
			if (name != null) {
				into.add(this.packagePrefix + name.replace('.', '/'));
			}
		}
	}
}
