package com.meshtastic.client.utils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author Konstantin A. Smirnov (ks@privatepractice.app)
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface SystemForm {

    String name() default "";

    String description() default "";

    String[] tags() default {};
}
