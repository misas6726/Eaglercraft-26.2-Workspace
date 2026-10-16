package org.spongepowered.asm.mixin.injection.callback;

/** Mixin API shim: callback object for @Inject handlers on methods that return a value. */
public class CallbackInfoReturnable<R> extends CallbackInfo {
	private R returnValue;

	public CallbackInfoReturnable(final String name, final boolean cancellable) {
		super(name, cancellable);
	}

	public CallbackInfoReturnable(final String name, final boolean cancellable, final R returnValue) {
		super(name, cancellable);
		this.returnValue = returnValue;
	}

	public void setReturnValue(final R returnValue) {
		super.cancel();
		this.returnValue = returnValue;
	}

	public R getReturnValue() {
		return this.returnValue;
	}

	// Primitive returns are boxed in the callback; the injected epilogue unboxes through these.
	public int getReturnValueI() {
		return (Integer) this.returnValue;
	}

	public long getReturnValueJ() {
		return (Long) this.returnValue;
	}

	public float getReturnValueF() {
		return (Float) this.returnValue;
	}

	public double getReturnValueD() {
		return (Double) this.returnValue;
	}

	public boolean getReturnValueZ() {
		return (Boolean) this.returnValue;
	}

	public byte getReturnValueB() {
		return (Byte) this.returnValue;
	}

	public char getReturnValueC() {
		return (Character) this.returnValue;
	}

	public short getReturnValueS() {
		return (Short) this.returnValue;
	}
}
