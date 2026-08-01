package com.machingclee.domain.util.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Command class with the actor(s) that initiate it (event-storming
 * "who does this"). Values are surfaced in {@link com.machingclee.domain.util.common.dto.CommandEventFlowDTO#actors()}
 * for use by the command-flow visualizer.
 *
 * <pre>
 * {@code
 * @BoundedContext("Booking")
 * @Actor("Customer")
 * public class SelfAssignToScheduledCarCommand implements Command<Void> { ... }
 *
 * // Multiple actors for the same command / endpoint:
 * @Actor({"Sales", "Admin"})
 * public class UpdateScheduleCommand implements Command<Void> { ... }
 * }
 * </pre>
 *
 * When absent, {@link com.machingclee.domain.util.common.dto.CommandEventFlowDTO#actors()} returns an empty list.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Actor {

    /**
     * Human-readable actor names for this command, e.g. {@code "Customer"},
     * {@code "Sales"}, {@code "System"}, {@code "Admin"}.
     * One or more values; a single string is valid shorthand for a one-element array.
     */
    String[] value();
}
