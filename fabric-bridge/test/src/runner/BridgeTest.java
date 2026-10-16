import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.demo.Game;
import net.minecraft.demo.Target;
import demo.mod.DemoClient;
import demo.mod.DemoMod;
import demo.mod.mixin.TargetAccessor;

public class BridgeTest {
	static int failures;

	static void check(String what, Object expected, Object actual) {
		boolean ok = expected.equals(actual);
		System.out.println((ok ? "PASS " : "FAIL ") + what + " -> " + actual + (ok ? "" : " (expected " + expected + ")"));
		if (!ok) {
			failures++;
		}
	}

	public static void main(String[] args) throws Exception {
		Game.boot();
		Game.run();
		check("init hook ran mod initializer", true, DemoMod.ran);
		check("client hook ran client initializer", true, DemoClient.ran);
		check("FabricLoader sees mod", true, FabricLoader.getInstance().isModLoaded("demo"));
		check("hook order", "[mod-init, boot, client-init, run]", Game.LOG.toString());

		Target t = new Target();
		t.tick();
		t.tick();
		t.tick();
		check("HEAD cancel stops 3rd tick", 2, t.ticks);
		check("@Unique field merged + counted", 3, t.getClass().getMethod("modCallCount").invoke(t));
		check("RETURN inject modifies int result", 1003, t.add(1, 2));
		check("static HEAD cancellable (x=7)", -1, Target.twice(7));
		check("static HEAD falls through (x=4)", 8, Target.twice(4));
		check("TAIL inject, long/double args", 31L, t.big(10L, 5.0));
		check("TAIL only patches the last return (early return untouched)", 0L, t.big(-1L, 1.0));
		t.counter = 5;
		t.reset();
		check("void RETURN inject", 99, t.counter);
		check("@Overwrite + @Shadow call", "modded:1003:3", t.name());

		TargetAccessor acc = (TargetAccessor) (Object) t;
		check("@Accessor getter", 42, acc.getSecret());
		acc.setSecret(7);
		check("@Accessor setter", 7, acc.getSecret());
		check("@Invoker private", 10, acc.callHidden(3));

		System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
