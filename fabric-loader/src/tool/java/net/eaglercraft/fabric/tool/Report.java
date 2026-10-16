package net.eaglercraft.fabric.tool;

import java.util.ArrayList;
import java.util.List;

/** Everything the tool wants the user to know: what it did, what it could not do, and what is broken. */
final class Report {
	final List<String> info = new ArrayList<>();
	final List<String> warnings = new ArrayList<>();
	final List<String> errors = new ArrayList<>();

	void info(final String message) {
		this.info.add(message);
	}

	void warn(final String message) {
		this.warnings.add(message);
	}

	void error(final String message) {
		this.errors.add(message);
	}

	boolean hasErrors() {
		return !this.errors.isEmpty();
	}

	String render() {
		StringBuilder out = new StringBuilder();
		section(out, "ERRORS (" + this.errors.size() + ")", this.errors);
		section(out, "WARNINGS (" + this.warnings.size() + ")", this.warnings);
		section(out, "INFO", this.info);
		return out.toString();
	}

	private static void section(final StringBuilder out, final String title, final List<String> lines) {
		out.append("== ").append(title).append(" ==\n");
		for (String line : lines) {
			out.append("  ").append(line).append('\n');
		}
		out.append('\n');
	}
}
