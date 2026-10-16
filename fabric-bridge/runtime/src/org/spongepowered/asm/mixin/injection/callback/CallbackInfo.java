package org.spongepowered.asm.mixin.injection.callback;

/** Same name/shape as SpongePowered Mixin's CallbackInfo so mod bytecode links unchanged. */
public class CallbackInfo {
	private final String id;
	private final boolean cancellable;
	private boolean cancelled;

	public CallbackInfo(final String id, final boolean cancellable) {
		this.id = id;
		this.cancellable = cancellable;
	}

	public String getId() {
		return this.id;
	}

	public boolean isCancellable() {
		return this.cancellable;
	}

	public boolean isCancelled() {
		return this.cancelled;
	}

	public void cancel() {
		if (!this.cancellable) {
			throw new IllegalStateException("The call " + this.id + " is not cancellable.");
		}
		this.cancelled = true;
	}
}
