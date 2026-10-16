package demo.mod.mixin;

import net.minecraft.demo.Target;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Target.class)
public abstract class TargetMixin {
	@Shadow public int ticks;
	@Shadow public int counter;
	@Shadow public abstract int add(int a, int b);

	@Unique private int modCalls;

	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void onTickHead(CallbackInfo ci) {
		this.modCalls++;
		if (this.ticks >= 2) {
			ci.cancel();
		}
	}

	@Inject(method = "add(II)I", at = @At("RETURN"), cancellable = true)
	private void onAdd(int a, int b, CallbackInfoReturnable<Integer> cir) {
		cir.setReturnValue(cir.getReturnValue() + 1000);
	}

	@Inject(method = "twice", at = @At("HEAD"), cancellable = true)
	private static void onTwice(int x, CallbackInfoReturnable<Integer> cir) {
		if (x == 7) {
			cir.setReturnValue(-1);
		}
	}

	@Inject(method = "big", at = @At("TAIL"), cancellable = true)
	private void onBig(long a, double d, CallbackInfoReturnable<Long> cir) {
		cir.setReturnValue(cir.getReturnValue() * 2L + 1L);
	}

	@Inject(method = "reset", at = @At("RETURN"))
	private void afterReset(CallbackInfo ci) {
		this.counter = 99;
	}

	@Overwrite
	public String name() {
		return "modded:" + this.add(1, 2) + ":" + this.modCalls;
	}

	public int modCallCount() {
		return this.modCalls;
	}
}
