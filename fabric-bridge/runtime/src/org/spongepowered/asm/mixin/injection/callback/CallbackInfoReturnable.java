package org.spongepowered.asm.mixin.injection.callback;

public class CallbackInfoReturnable<R> extends CallbackInfo {
	private R returnValue;

	public CallbackInfoReturnable(final String id, final boolean cancellable) {
		super(id, cancellable);
	}

	public CallbackInfoReturnable(final String id, final boolean cancellable, final R returnValue) {
		super(id, cancellable);
		this.returnValue = returnValue;
	}

	public void setReturnValue(final R value) {
		super.cancel();
		this.returnValue = value;
	}

	public R getReturnValue() {
		return this.returnValue;
	}
}
