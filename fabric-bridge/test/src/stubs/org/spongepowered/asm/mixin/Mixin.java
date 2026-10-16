package org.spongepowered.asm.mixin;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
public @interface Mixin { Class<?>[] value() default {}; String[] targets() default {};}
