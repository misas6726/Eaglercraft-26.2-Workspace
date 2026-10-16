package net.minecraft.demo;

import java.util.ArrayList;
import java.util.List;

public class Game {
	public static final List<String> LOG = new ArrayList<>();

	public static void boot() {
		LOG.add("boot");
	}

	public static void run() {
		LOG.add("run");
	}
}
