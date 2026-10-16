package net.minecraft.demo;

public class Target {
	public int counter;
	public int ticks;
	private int secret = 42;

	public void tick() {
		this.ticks++;
	}

	public void reset() {
		this.counter = 0;
	}

	public int add(int a, int b) {
		return a + b;
	}

	public String name() {
		return "vanilla";
	}

	public static int twice(int x) {
		return x * 2;
	}

	private int hidden(int x) {
		return x + this.secret;
	}

	public long big(long a, double d) {
		if (a < 0) {
			return 0L;
		}
		return a + (long) d;
	}
}
