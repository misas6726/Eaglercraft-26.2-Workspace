package demo.mod;

import net.fabricmc.api.ModInitializer;
import net.minecraft.demo.Game;

public class DemoMod implements ModInitializer {
	public static boolean ran;

	@Override
	public void onInitialize() {
		ran = true;
		Game.LOG.add("mod-init");
	}
}
