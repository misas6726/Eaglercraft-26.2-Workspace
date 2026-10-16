package demo.mod;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.demo.Game;

public class DemoClient implements ClientModInitializer {
	public static boolean ran;

	@Override
	public void onInitializeClient() {
		ran = true;
		Game.LOG.add("client-init");
	}
}
