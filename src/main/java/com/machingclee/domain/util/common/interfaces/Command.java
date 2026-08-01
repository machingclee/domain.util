package com.machingclee.domain.util.common.interfaces;

/**
 * Marker interface for commands that return a result of type R.
 * Commands are write operations that may modify state and produce domain events.
 */

/**
 * Marker interface for commands that return a result of type R.
 * Commands are write operations that may modify state and produce domain
 * events.
 * <p>
 * The type parameter R is intentionally unused inside this interface — it
 * exists
 * purely to let {@code CommandInvoker.invoke(Command<R>)} infer the return type
 * at the call site without an unchecked cast.
 */
@SuppressWarnings("java:S2326") // "unused generic type" — kept intentionally for call-site type inference
public interface Command<R> {
}
