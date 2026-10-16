package net.fabricmc.fabric.api.event;

import java.lang.reflect.Array;
import java.util.function.Function;

/** Fabric API shim (array-backed events only, no phases). */
public final class EventFactory {
	private EventFactory() {
	}

	@SuppressWarnings("unchecked")
	public static <T> Event<T> createArrayBacked(final Class<? super T> type, final Function<T[], T> invokerFactory) {
		return new ArrayBackedEvent<>((Class<T>) type, invokerFactory);
	}

	private static final class ArrayBackedEvent<T> extends Event<T> {
		private final Class<T> type;
		private final Function<T[], T> factory;
		private T[] handlers;

		@SuppressWarnings("unchecked")
		ArrayBackedEvent(final Class<T> type, final Function<T[], T> factory) {
			this.type = type;
			this.factory = factory;
			this.handlers = (T[]) Array.newInstance(type, 0);
			this.invoker = factory.apply(this.handlers);
		}

		@Override
		@SuppressWarnings("unchecked")
		public void register(final T listener) {
			T[] next = (T[]) Array.newInstance(this.type, this.handlers.length + 1);
			System.arraycopy(this.handlers, 0, next, 0, this.handlers.length);
			next[this.handlers.length] = listener;
			this.handlers = next;
			this.invoker = this.factory.apply(next);
		}
	}
}
