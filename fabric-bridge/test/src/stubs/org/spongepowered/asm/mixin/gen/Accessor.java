package org.spongepowered.asm.mixin.gen;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
public @interface Accessor { String value() default ""; }
