package demo.mod;

import java.io.File;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.demo.Missing;
import net.minecraft.demo.Target;

public class BadMod {
	public void poke(Target t) {
		Event.touch();
		Missing.gone();
		t.ghost();
		new File("x").exists();
	}
}
