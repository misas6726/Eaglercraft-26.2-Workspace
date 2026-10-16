package org.spongepowered.asm.mixin.injection.callback;

/** Mixin API shim: the callback object handed to @Inject handlers. */
public class CallbackInfo {
	private final String name;
	private final boolean cancellable;
	private boolean cancelled;

	public CallbackInfo(final String name, final boolean cancellable) {
		this.name = name;
		this.cancellable = cancellable;
	}

	public String getId() {
		return this.name;
	}

	public boolean isCancellable() {
		return this.cancellable;
	}

	public boolean isCancelled() {
		return this.cancelled;
	}

	public void cancel() {
		if (!this.cancellable) {
			throw new IllegalStateException("The call " + this.name + " is not cancellable.");
		}
		this.cancelled = true;
	}
}
