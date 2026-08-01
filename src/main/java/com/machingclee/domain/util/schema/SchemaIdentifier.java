package com.machingclee.domain.util.schema;

/**
 * Marker interface for schema identifiers.
 * Consumers implement this (typically as an enum) to define their own schemas.
 * <p>
 * Example:
 * public enum SalesSchema implements SchemaIdentifier {
 * SALES, REPORTING;
 *
 * @Override public String schemaName() { return name().toLowerCase(); }
 * }
 */
public interface SchemaIdentifier {
    String schemaName();
}
