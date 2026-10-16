package net.minecraft.demo;
/** Superset of the real game Target: adds ghost(), which the "game" does not have. */
public class Target {
	public int counter;
	public int ticks;
	public void tick() {}
	public void reset() {}
	public int add(int a, int b) { return 0; }
	public String name() { return ""; }
	public static int twice(int x) { return 0; }
	public long big(long a, double d) { return 0; }
	public void ghost() {}
}
