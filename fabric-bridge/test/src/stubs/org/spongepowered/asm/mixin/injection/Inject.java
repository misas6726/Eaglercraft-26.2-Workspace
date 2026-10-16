package org.spongepowered.asm.mixin.injection;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
public @interface Inject { String[] method(); At[] at(); boolean cancellable() default false; }
