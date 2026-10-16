package demo.mod.mixin;

import net.minecraft.demo.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Target.class)
public interface TargetAccessor {
	@Accessor("secret")
	int getSecret();

	@Accessor("secret")
	void setSecret(int value);

	@Invoker("hidden")
	int callHidden(int x);
}
