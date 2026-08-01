package com.machingclee.domain.util.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Command class as belonging to a specific bounded context (DDD).
 * The value is surfaced in {@link com.machingclee.domain.util.common.dto.CommandEventFlowDTO#context()} for use by
 * the command-flow visualizer.
 *
 * <pre>
 * {@code
 * @BoundedContext("Car Catalog")
 * public class CreateCarCommand implements Command<Void> { ... }
 * }
 * </pre>
 *
 * When absent, {@link com.machingclee.domain.util.common.dto.CommandEventFlowDTO#context()} returns an empty string.
 */
@Target({ElementType.TYPE, ElementType.PACKAGE})
@Retention(RetentionPolicy.RUNTIME)
public @interface BoundedContext {

    /**
     * A human-readable context name for this command/entity, e.g. "Car Catalog",
     * "Order Management", "User Profile", "Booking". When placed on a package,
     * all entities in that package inherit the context unless overridden on the class.
     */
    String value();
}
