package org.spongepowered.asm.mixin.gen;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
public @interface Invoker { String value() default ""; }
