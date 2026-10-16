package net.fabricmc.fabric.api.event;

/** Fabric API shim: an event with an invoker that fans out to every registered listener. */
public abstract class Event<T> {
	protected T invoker;

	/** The listener to call to fire the event. */
	public final T invoker() {
		return this.invoker;
	}

	public abstract void register(T listener);
}
