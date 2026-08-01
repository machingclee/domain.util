export interface InvolvedEntity {
    entity: string;
    childEntity: string[];
}

/** Mirrors backend EventPayloadDTO / CommandPayloadDTO shape. */
export interface NamedPayload {
    /** event or command simple name */
    name: string;
    payload: Record<string, unknown>;  // field-name → type-descriptor schema
}

export interface EventPayload {
    event: string;
    payload: Record<string, unknown>;  // field-name → type-descriptor schema
}

/** Mirrors backend CommandPayloadDTO — same representation as EventPayloadDTO. */
export interface CommandPayload {
    command: string;
    payload: Record<string, unknown>;  // field-name → TypeScript type-descriptor schema
}

/** Mirrors backend QueryPayloadDTO. */
export interface QueryPayload {
    query: string;
    payload: Record<string, unknown>;
}

/** Mirrors backend QueryFlowDTO — same shape as Command but with result instead of to. */
export interface QueryItem {
    from: QueryPayload;
    result: QueryPayload;
    context: string;
    actors?: string[];
    httpMethod?: string;
    path?: string;
    summary?: string;
    description?: string;
    roles?: string[];
}

export interface Command {
    from: CommandPayload;
    to: EventPayload[];
    context: string;
    actors?: string[];
    httpMethod?: string;
    path?: string;
    summary?: string;
    description?: string;
    roles?: string[];
    /** Kept for API compatibility; no longer rendered in the diagram. */
    involvedEntities?: InvolvedEntity[];
}

export interface PolicyFlow {
    fromEvent: string | null;
    toCommand: string | null;
    invariant: string;
}

/** Shared sort so layout edge handle indices match PolicyNode handle ids. */
export function sortPolicyFlows(flows: PolicyFlow[]): PolicyFlow[] {
    return [...flows].sort((f1, f2) => {
        if (!f1.invariant.trim() && !f2.invariant.trim()) return 0;
        if (!f1.invariant.trim()) return 1;
        if (!f2.invariant.trim()) return -1;
        return f1.invariant.localeCompare(f2.invariant, undefined, {
            sensitivity: "base",
        });
    });
}

export interface PolicyData {
    flows: PolicyFlow[];
}

/** Mirrors backend FactoryMethodDTO. */
export interface FactoryMethod {
    entityName: string;
    methodName: string;
    parameters: Record<string, unknown>;
}

/** Mirrors backend EntityMethodDTO. */
export interface EntityMethod {
    methodName: string;
    parameters: Record<string, unknown>;
    returnType: string;
    factory: boolean;
}

/** Mirrors backend EntityRelationDTO. */
export interface EntityRelation {
    fieldName: string;
    targetEntity: string;
    type: "ONE_TO_ONE" | "ONE_TO_MANY" | "MANY_TO_ONE" | "MANY_TO_MANY" | string;
    mappedBy: string;
    owningSide: boolean;
    /** @JoinColumn.insertable when present; omitted/null when no JoinColumn. */
    insertable?: boolean | null;
    /** @JoinColumn.updatable when present; omitted/null when no JoinColumn. */
    updatable?: boolean | null;
    /**
     * True when this side is a 1–1 polymorphic / secondary-table extension child
     * of targetEntity (child owns the FK toward the parent).
     */
    extensionChild?: boolean;
}

/** Mirrors backend EntityNodeDTO — one JPA entity in the entity graph. */
export interface EntityNode {
    entityName: string;
    context: string;
    factories: EntityMethod[];
    domainMethods: EntityMethod[];
    relations: EntityRelation[];
}

export interface FlowData {
    commands: Command[];
    policies: { [policyName: string]: PolicyData };
    schema?: Record<string, Record<string, string>>;
    dtos?: Record<string, Record<string, string>>;
    queries?: QueryItem[];
    queryDtos?: Record<string, Record<string, string>>;
    /** @deprecated Prefer {@link entities}; kept for older payloads. */
    factories?: FactoryMethod[];
    factoryDtos?: Record<string, Record<string, string>>;
    /** Full entity graph (replaces Factories tab). */
    entities?: EntityNode[];
    entityDtos?: Record<string, Record<string, string>>;
}

/**
 * When the API only sends flat factories, synthesize minimal entity nodes
 * so the Entities tab still renders.
 */
export function entitiesFromFlowData(flowData: FlowData): EntityNode[] {
    if (flowData.entities && flowData.entities.length > 0) {
        return flowData.entities;
    }
    const factories = flowData.factories ?? [];
    if (factories.length === 0) return [];
    const byEntity = new Map<string, FactoryMethod[]>();
    for (const f of factories) {
        const name = f.entityName || "Unknown";
        if (!byEntity.has(name)) byEntity.set(name, []);
        byEntity.get(name)!.push(f);
    }
    return [...byEntity.entries()].map(([entityName, items]) => ({
        entityName,
        context: "default",
        factories: items.map((f) => ({
            methodName: f.methodName,
            parameters: f.parameters as Record<string, unknown>,
            returnType: entityName,
            factory: true,
        })),
        domainMethods: [],
        relations: [],
    }));
}

// ── Context tree for nested sub-context grouping ──

/** A node in the context hierarchy built from dot-separated context names. */
export interface ContextNode {
    /** Full dotted name, e.g. "Booking.ScheduleLink" */
    name: string;
    /** Display label — the last segment, e.g. "ScheduleLink" */
    label: string;
    /** 0 = top-level context, 1 = first sub-level, etc. */
    depth: number;
    /** Full dotted name of the parent context, or null for top-level. */
    parentPath: string | null;
    /** Immediate child sub-contexts. */
    children: ContextNode[];
    /** Commands whose `context` matches this node's name exactly. */
    commands: Command[];
}

/**
 * Build a context tree from a flat list of commands.
 * Dot-separated context names like "Booking.ScheduleLink" create nested nodes:
 * "Booking" (depth 0) → "Booking.ScheduleLink" (depth 1).
 * Returns [roots, lookupMap].
 */
export function buildContextTree(commands: Command[]): [ContextNode[], Map<string, ContextNode>] {
    const lookup = new Map<string, ContextNode>();

    // Collect all unique context strings, including intermediate parents
    // so "Booking.ScheduleLink.Foo" also creates "Booking" and "Booking.ScheduleLink".
    const contextSet = new Set<string>();
    commands.forEach((cmd) => {
        const ctx = cmd.context || "Default";
        const parts = ctx.split(".");
        for (let i = 1; i <= parts.length; i++) {
            contextSet.add(parts.slice(0, i).join("."));
        }
    });

    // Sort so parents come before children (shorter paths first)
    const sorted = [...contextSet].sort((a, b) => {
        const aDepth = a.split(".").length;
        const bDepth = b.split(".").length;
        if (aDepth !== bDepth) return aDepth - bDepth;
        return a.localeCompare(b, undefined, { sensitivity: "base" });
    });

    // Create nodes and wire parent/child relationships
    const roots: ContextNode[] = [];

    for (const fullName of sorted) {
        const parts = fullName.split(".");
        const label = parts[parts.length - 1];
        const depth = parts.length - 1;
        const parentPath = depth > 0 ? parts.slice(0, -1).join(".") : null;

        const node: ContextNode = {
            name: fullName,
            label,
            depth,
            parentPath,
            children: [],
            commands: [],
        };

        lookup.set(fullName, node);

        if (parentPath) {
            const parent = lookup.get(parentPath);
            if (parent) {
                parent.children.push(node);
            } else {
                // Should not happen after intermediate-parent expansion
                roots.push(node);
            }
        } else {
            roots.push(node);
        }
    }

    // Assign commands to their matching context node
    commands.forEach((cmd) => {
        const ctx = cmd.context || "Default";
        const node = lookup.get(ctx);
        if (node) {
            node.commands.push(cmd);
        }
    });

    // Ensure "Default" exists if any command has no context
    if (!lookup.has("Default") && commands.some((c) => !c.context)) {
        const defaultNode: ContextNode = {
            name: "Default",
            label: "Default",
            depth: 0,
            parentPath: null,
            children: [],
            commands: commands.filter((c) => !c.context),
        };
        lookup.set("Default", defaultNode);
        roots.push(defaultNode);
    }

    return [roots, lookup];
}

// ── Query context tree for nested sub-context grouping ──

/** A node in the query context hierarchy built from dot-separated context names. */
export interface QueryContextNode {
    /** Full dotted name, e.g. "Booking.ScheduleLink" */
    name: string;
    /** Display label — the last segment, e.g. "ScheduleLink" */
    label: string;
    /** 0 = top-level context, 1 = first sub-level, etc. */
    depth: number;
    /** Full dotted name of the parent context, or null for top-level. */
    parentPath: string | null;
    /** Immediate child sub-contexts. */
    children: QueryContextNode[];
    /** Queries whose `context` matches this node's name exactly. */
    queries: QueryItem[];
}

/**
 * Build a context tree from a flat list of queries.
 * Dot-separated context names like "Booking.ScheduleLink" create nested nodes:
 * "Booking" (depth 0) → "Booking.ScheduleLink" (depth 1).
 * Returns [roots, lookupMap].
 */
export function buildQueryContextTree(queries: QueryItem[]): [QueryContextNode[], Map<string, QueryContextNode>] {
    const lookup = new Map<string, QueryContextNode>();

    // Collect all unique context strings, including intermediate parents
    const contextSet = new Set<string>();
    queries.forEach((q) => {
        const ctx = q.context || "Default";
        const parts = ctx.split(".");
        for (let i = 1; i <= parts.length; i++) {
            contextSet.add(parts.slice(0, i).join("."));
        }
    });

    // Sort so parents come before children (shorter paths first)
    const sorted = [...contextSet].sort((a, b) => {
        const aDepth = a.split(".").length;
        const bDepth = b.split(".").length;
        if (aDepth !== bDepth) return aDepth - bDepth;
        return a.localeCompare(b, undefined, { sensitivity: "base" });
    });

    // Create nodes and wire parent/child relationships
    const roots: QueryContextNode[] = [];

    for (const fullName of sorted) {
        const parts = fullName.split(".");
        const label = parts[parts.length - 1];
        const depth = parts.length - 1;
        const parentPath = depth > 0 ? parts.slice(0, -1).join(".") : null;

        const node: QueryContextNode = {
            name: fullName,
            label,
            depth,
            parentPath,
            children: [],
            queries: [],
        };

        lookup.set(fullName, node);

        if (parentPath) {
            const parent = lookup.get(parentPath);
            if (parent) {
                parent.children.push(node);
            } else {
                roots.push(node);
            }
        } else {
            roots.push(node);
        }
    }

    // Assign queries to their matching context node
    queries.forEach((q) => {
        const ctx = q.context || "Default";
        const node = lookup.get(ctx);
        if (node) {
            node.queries.push(q);
        }
    });

    // Ensure "Default" exists if any query has no context
    if (!lookup.has("Default") && queries.some((q) => !q.context)) {
        const defaultNode: QueryContextNode = {
            name: "Default",
            label: "Default",
            depth: 0,
            parentPath: null,
            children: [],
            queries: queries.filter((q) => !q.context),
        };
        lookup.set("Default", defaultNode);
        roots.push(defaultNode);
    }

    return [roots, lookup];
}

/** roles overrides actors when non-empty; otherwise actors. */
export function resolveCommandPrincipals(cmd: Command): string[] {
    if (cmd.roles && cmd.roles.length > 0) return cmd.roles;
    if (cmd.actors && cmd.actors.length > 0) return cmd.actors;
    return [];
}

/** Coerce mixed string | CommandPayload into CommandPayload. */
export function normalizeCommandFrom(
    raw: string | CommandPayload | undefined | null,
): CommandPayload {
    if (raw == null) return { command: "", payload: {} };
    if (typeof raw === "string") return { command: raw, payload: {} };
    return {
        command: raw.command ?? "",
        payload: (raw.payload as Record<string, unknown>) ?? {},
    };
}

/** Coerce mixed string[] | InvolvedEntity[] into InvolvedEntity[]. */
export function normalizeInvolvedEntities(
    raw: (string | InvolvedEntity)[] | undefined,
): InvolvedEntity[] {
    if (!raw || raw.length === 0) return [];
    return raw.map((item) =>
        typeof item === "string"
            ? { entity: item, childEntity: [] as string[] }
            : item,
    );
}

/** Flatten involved entity roots + children for chip count / height estimates. */
export function flattenInvolvedEntities(
    involved?: InvolvedEntity[],
): string[] {
    if (!involved || involved.length === 0) return [];
    const out: string[] = [];
    for (const ie of involved) {
        out.push(ie.entity);
        for (const child of ie.childEntity ?? []) {
            out.push(child);
        }
    }
    return out;
}

// ── DTO schema resolution (shared between layout & rendering) ──

export type DtoMap = Record<string, Record<string, string>>;

export interface SchemaLine {
    fieldName: string;
    typeName: string;
    indent: number;
    isOpenBrace?: string; // DTO name for "{"
    isCloseBrace?: boolean;
    /** When true, append `[]` after the closing brace. */
    closeBraceArray?: boolean;
}

/** Strip [] suffix and return [baseType, isArray]. */
export function parseType(raw: string): [string, boolean] {
    if (raw.endsWith("[]")) return [raw.slice(0, -2), true];
    return [raw, false];
}

/** Flatten a (possibly nested) payload into indented display lines.
 *  Used by both the visualizer (height estimation) and LabelNode (rendering). */
export function flattenSchema(
    fields: Record<string, unknown>,
    dtos: DtoMap | undefined,
    indent: number,
    visited: Set<string>,
): SchemaLine[] {
    const lines: SchemaLine[] = [];
    for (const [fieldName, fieldType] of Object.entries(fields)) {
        const typeStr = String(fieldType);
        const [baseType, isArray] = parseType(typeStr);
        const displayType = isArray ? `${baseType}[]` : typeStr;
        const dtoFields = dtos?.[baseType];

        if (dtoFields && !visited.has(baseType)) {
            visited.add(baseType);
            // Opening: fieldName : TypeName {  ([] moved to closing brace)
            lines.push({
                fieldName,
                typeName: baseType,
                indent,
                isOpenBrace: baseType,
            });
            // Inner fields at one level deeper.
            lines.push(
                ...flattenSchema(dtoFields, dtos, indent + 1, visited),
            );
            // Closing: }  or  } []
            lines.push({
                fieldName: "",
                typeName: "",
                indent,
                isCloseBrace: true,
                closeBraceArray: isArray || undefined,
            });
        } else if (dtoFields && visited.has(baseType)) {
            // Circular reference guard — show type name only.
            lines.push({ fieldName, typeName: displayType, indent });
        } else {
            lines.push({ fieldName, typeName: displayType, indent });
        }
    }
    return lines;
}

/** Count resolved schema lines (including nested DTOs) for height estimation. */
export function countResolvedSchemaLines(
    payload: Record<string, unknown> | undefined,
    dtos: DtoMap | undefined,
): number {
    if (!payload) return 0;
    return flattenSchema(payload, dtos, 0, new Set()).length;
}

/** Estimate pixel height of an event/command node from its resolved schema line count. */
export function estimateEventNodeHeight(lineCount: number): number {
    // base: padding(20) + label(~22) + schema-header(~20) + inner-padding(~6) = ~68
    // each line: fontSize 11 × lineHeight 1.5 ≈ 16.5, rounded to 17
    return Math.max(48, 68 + lineCount * 17);
}
