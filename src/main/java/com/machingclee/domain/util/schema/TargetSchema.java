package com.machingclee.domain.util.schema;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a CommandHandler as targeting a specific database schema.
 * Used by schema-specific CommandInvokers to filter handlers at startup.
 * <p>
 * Accepts any SchemaIdentifier — consumers define their own enum:
 *
 * <pre>
 * {@code
 * // In consumer module:
 * public enum SalesSchema implements SchemaIdentifier {
 *     SALES, REPORTING;
 *
 *     @Override public String schemaName() { return name().toLowerCase(); }
 * }
 *
 * // Usage:
 * @TargetSchema("sales")
 * @Component
 * public class CreateOrderHandler implements CommandHandler<CreateOrderCommand, Void> { ... }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TargetSchema {

    /**
     * The SchemaIdentifier implementation class (typically an enum) that this
     * handler belongs to. The AbstractCommandInvoker will check
     * {@code value().isInstance(schemaIdentifier)} to match handlers at startup.
     * <p>
     * Example: {@code @TargetSchema(SalesSchema.class)}
     */
    Class<? extends SchemaIdentifier> value();
}
