import { Handle, Position } from "@xyflow/react";
import type { DtoMap, EntityMethod, SchemaLine } from "../../types";
import { flattenSchema } from "../../types";

const monoFont =
    "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace";

const SchemaLineView = ({ line }: { line: SchemaLine }) => {
    const padLeft = line.indent * 14;
    if (line.isCloseBrace) {
        return (
            <div
                style={{
                    paddingLeft: padLeft,
                    display: "flex",
                    alignItems: "baseline",
                    gap: 4,
                    opacity: 0.5,
                    fontSize: "10px",
                    lineHeight: 1.5,
                }}
            >
                {"}"}
                {line.closeBraceArray && (
                    <span style={{ fontFamily: monoFont }}>[]</span>
                )}
            </div>
        );
    }
    if (line.isOpenBrace) {
        return (
            <div
                style={{
                    paddingLeft: padLeft,
                    display: "flex",
                    alignItems: "baseline",
                    gap: 4,
                    fontSize: "11px",
                    lineHeight: 1.5,
                    color: "rgba(255,255,255,0.9)",
                }}
            >
                <span style={{ fontWeight: 600, opacity: 0.9, fontFamily: monoFont }}>
                    {line.fieldName}
                </span>
                <span style={{ opacity: 0.45 }}>:</span>
                <span style={{ fontFamily: monoFont, opacity: 0.7, color: "#93c5fd" }}>
                    {line.typeName}
                </span>
                <span style={{ fontFamily: monoFont, fontSize: "10px", opacity: 0.45 }}>
                    {"{"}
                </span>
            </div>
        );
    }
    return (
        <div
            style={{
                paddingLeft: padLeft,
                display: "flex",
                alignItems: "baseline",
                gap: 4,
                fontSize: "11px",
                lineHeight: 1.5,
                color: "rgba(255,255,255,0.9)",
            }}
        >
            <span style={{ fontWeight: 600, opacity: 0.9, fontFamily: monoFont }}>
                {line.fieldName}
            </span>
            <span style={{ opacity: 0.45 }}>:</span>
            <span style={{ fontStyle: "italic", opacity: 0.8, fontFamily: monoFont }}>
                {line.typeName}
            </span>
        </div>
    );
};

function MethodBlock({
    method,
    nodeId,
    kind,
    dtos,
    expanded,
    onToggle,
}: {
    method: EntityMethod;
    nodeId: string;
    kind: "factory" | "domain";
    dtos?: DtoMap;
    expanded: boolean;
    onToggle?: (id: string) => void;
}) {
    const paramSig = Object.values(method.parameters ?? {}).join(",");
    const methodKey = paramSig
        ? `${nodeId}::${kind}::${method.methodName}(${paramSig})`
        : `${nodeId}::${kind}::${method.methodName}`;
    const schemaLines: SchemaLine[] | null =
        method.parameters && Object.keys(method.parameters).length > 0
            ? flattenSchema(method.parameters, dtos, 0, new Set())
            : null;
    const accent = kind === "factory" ? "#34d399" : "#93c5fd";

    return (
        <div
            style={{
                textAlign: "left",
                background: "rgba(0,0,0,0.18)",
                borderRadius: 6,
                padding: "6px 8px",
                border: `1px solid ${kind === "factory" ? "rgba(52,211,153,0.25)" : "rgba(147,197,253,0.2)"}`,
            }}
        >
            <div style={{ display: "flex", alignItems: "baseline", gap: 6, flexWrap: "wrap" }}>
                <span style={{ fontWeight: 700, fontSize: 12, color: accent }}>
                    {method.methodName}
                </span>
                <span style={{ fontFamily: monoFont, fontSize: 10, opacity: 0.55 }}>
                    → {method.returnType || "void"}
                </span>
            </div>
            {schemaLines && schemaLines.length > 0 && (
                <div style={{ marginTop: 4 }}>
                    <div
                        onClick={(e) => {
                            e.stopPropagation();
                            onToggle?.(methodKey);
                        }}
                        title={expanded ? "Hide parameters" : "Show parameters"}
                        style={{
                            display: "inline-flex",
                            alignItems: "center",
                            gap: 4,
                            cursor: "pointer",
                            background: expanded
                                ? "rgba(96,165,250,0.3)"
                                : "rgba(255,255,255,0.1)",
                            borderRadius: 4,
                            padding: "1px 6px",
                            fontSize: 10,
                            fontWeight: 700,
                            userSelect: "none",
                        }}
                    >
                        <span style={{ fontSize: 9 }}>{expanded ? "▾" : "▸"}</span>
                        Params
                        <span style={{ fontWeight: 400, opacity: 0.55 }}>
                            ({schemaLines.length})
                        </span>
                    </div>
                    {expanded && (
                        <div
                            style={{
                                display: "flex",
                                flexDirection: "column",
                                background: "rgba(0,0,0,0.22)",
                                borderRadius: 5,
                                padding: "4px 6px",
                                gap: 1,
                                marginTop: 4,
                            }}
                        >
                            {schemaLines.map((line, i) => (
                                <SchemaLineView key={i} line={line} />
                            ))}
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}

/**
 * Entity graph card: entity name + factory methods + domain behaviour methods.
 * Relation edges attach to left/right handles (like command → event).
 */
const EntityNode = ({
    id,
    data,
}: {
    id: string;
    data: {
        label: string;
        context?: string;
        factories?: EntityMethod[];
        domainMethods?: EntityMethod[];
        relationSummary?: string[];
        dtos?: DtoMap;
        schemaExpanded?: boolean;
        expandedMethodKeys?: string[];
        onToggleSchema?: (nodeId: string) => void;
        copied?: boolean;
    };
}) => {
    const factories = data.factories ?? [];
    const domainMethods = data.domainMethods ?? [];
    const expandedKeys = new Set(data.expandedMethodKeys ?? []);
    // Parent toggles any key via onToggleSchema (reused expandedSchemas set)
    const onToggle = data.onToggleSchema;

    return (
        <>
            <Handle type="target" position={Position.Left} id="in" />
            <div
                style={{
                    display: "flex",
                    flexDirection: "column",
                    gap: 8,
                    textAlign: "left",
                    transition: "color 0.4s ease",
                    color: data.copied ? "#4ade80" : "inherit",
                    minWidth: 0,
                }}
            >
                <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
                    <span
                        style={{
                            fontWeight: 800,
                            fontSize: 15,
                            letterSpacing: "0.01em",
                            textAlign: "center",
                        }}
                    >
                        {data.copied ? "✓ Copied" : data.label}
                    </span>
                    {data.context && data.context !== "default" && (
                        <span
                            style={{
                                fontSize: 10,
                                opacity: 0.55,
                                textAlign: "center",
                                fontWeight: 600,
                            }}
                        >
                            {data.context}
                        </span>
                    )}
                </div>

                {factories.length > 0 && (
                    <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
                        <div
                            style={{
                                fontSize: 10,
                                fontWeight: 800,
                                letterSpacing: "0.06em",
                                opacity: 0.65,
                                textTransform: "uppercase",
                                color: "#6ee7b7",
                            }}
                        >
                            Factories
                        </div>
                        {factories.map((m, idx) => {
                            const paramSig = Object.values(m.parameters ?? {}).join(",");
                            const key = paramSig
                                ? `${id}::factory::${m.methodName}(${paramSig})`
                                : `${id}::factory::${m.methodName}`;
                            return (
                                <MethodBlock
                                    key={`${key}-${idx}`}
                                    method={m}
                                    nodeId={id}
                                    kind="factory"
                                    dtos={data.dtos}
                                    expanded={expandedKeys.has(key)}
                                    onToggle={onToggle}
                                />
                            );
                        })}
                    </div>
                )}

                {domainMethods.length > 0 && (
                    <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
                        <div
                            style={{
                                fontSize: 10,
                                fontWeight: 800,
                                letterSpacing: "0.06em",
                                opacity: 0.65,
                                textTransform: "uppercase",
                                color: "#93c5fd",
                            }}
                        >
                            Domain Methods
                        </div>
                        {domainMethods.map((m, idx) => {
                            const paramSig = Object.values(m.parameters ?? {}).join(",");
                            const key = paramSig
                                ? `${id}::domain::${m.methodName}(${paramSig})`
                                : `${id}::domain::${m.methodName}`;
                            return (
                                <MethodBlock
                                    key={`${key}-${idx}`}
                                    method={m}
                                    nodeId={id}
                                    kind="domain"
                                    dtos={data.dtos}
                                    expanded={expandedKeys.has(key)}
                                    onToggle={onToggle}
                                />
                            );
                        })}
                    </div>
                )}

                {factories.length === 0 && domainMethods.length === 0 && (
                    <div style={{ fontSize: 11, opacity: 0.45, textAlign: "center" }}>
                        (no factories / domain methods)
                    </div>
                )}
            </div>
            <Handle type="source" position={Position.Right} id="out" />
        </>
    );
};

export default EntityNode;
