package net.fabricmc.api;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Marker only; the bridge treats every annotated element as present (client build). */
@Retention(RetentionPolicy.CLASS)
public @interface Environment {
	EnvType value();
}
