package dev.frozenclient.fabricbridge;

import java.util.ArrayList;
import java.util.List;

/** Collects human-readable build output. {@code skipped} counts functionality that was NOT applied. */
final class Report {
	final List<String> lines = new ArrayList<>();
	int applied;
	int skipped;
	int compatWarnings;

	void info(final String s) {
		this.lines.add("  " + s);
	}

	void heading(final String s) {
		this.lines.add(s);
	}

	void applied(final String s) {
		this.applied++;
		this.lines.add("  [ok]   " + s);
	}

	void skipped(final String s) {
		this.skipped++;
		this.lines.add("  [SKIP] " + s);
	}

	void compat(final String s) {
		this.compatWarnings++;
		this.lines.add("  [warn] " + s);
	}
}
