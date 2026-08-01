import { Handle, Position } from "@xyflow/react";
import type { SchemaLine, DtoMap } from "../../types";
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
                <span
                    style={{
                        fontWeight: 600,
                        opacity: 0.9,
                        fontFamily: monoFont,
                    }}
                >
                    {line.fieldName}
                </span>
                <span style={{ opacity: 0.45 }}>:</span>
                <span
                    style={{
                        fontFamily: monoFont,
                        opacity: 0.7,
                        color: "#93c5fd",
                    }}
                >
                    {line.typeName}
                </span>
                <span
                    style={{
                        fontFamily: monoFont,
                        fontSize: "10px",
                        opacity: 0.45,
                    }}
                >
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
            <span
                style={{
                    fontWeight: 600,
                    opacity: 0.9,
                    fontFamily: monoFont,
                }}
            >
                {line.fieldName}
            </span>
            <span style={{ opacity: 0.45 }}>:</span>
            <span
                style={{
                    fontStyle: "italic",
                    opacity: 0.8,
                    fontFamily: monoFont,
                }}
            >
                {line.typeName}
            </span>
        </div>
    );
};

/** Single factory-method node — simpler than LabelNode: no edit buttons, no HTTP info. */
const FactoryNode = ({
    id,
    data,
}: {
    id: string;
    data: {
        label: string;
        summary?: string;
        copied?: boolean;
        payload?: Record<string, unknown>;
        dtos?: DtoMap;
        editMode?: boolean;
        schemaExpanded?: boolean;
        onToggleSchema?: (nodeId: string) => void;
    };
}) => {
    const showSchema = data.schemaExpanded ?? false;
    const schemaLines: SchemaLine[] | null =
        data.payload && !data.copied
            ? flattenSchema(data.payload, data.dtos, 0, new Set())
            : null;

    return (
        <>
            <Handle type="target" position={Position.Left} />
            <div
                style={{
                    display: "flex",
                    flexDirection: "column",
                    alignItems: "center",
                    gap: 4,
                    textAlign: "center",
                    transition: "color 0.4s ease",
                    color: data.copied ? "#4ade80" : "inherit",
                    fontWeight: data.copied ? 600 : "inherit",
                }}
            >
                {/* Method name */}
                <span
                    style={{
                        fontWeight: 700,
                        fontSize: "14px",
                        letterSpacing: "0.01em",
                    }}
                >
                    {data.copied ? "✓ Copied" : data.label}
                </span>

                {/* Parameter schema */}
                {schemaLines && schemaLines.length > 0 && (
                    <div
                        style={{
                            marginTop: 6,
                            fontSize: "12px",
                            fontWeight: 500,
                            opacity: 0.9,
                        }}
                    >
                        <div
                            onClick={(e) => {
                                e.stopPropagation();
                                data.onToggleSchema?.(id);
                            }}
                            title={showSchema ? "Hide parameters" : "Show parameters"}
                            style={{
                                display: "inline-flex",
                                alignItems: "center",
                                gap: 4,
                                cursor: "pointer",
                                background: showSchema
                                    ? "rgba(96,165,250,0.3)"
                                    : "rgba(255,255,255,0.12)",
                                borderRadius: "4px",
                                padding: "2px 8px",
                                fontSize: "11px",
                                fontWeight: 700,
                                letterSpacing: "0.02em",
                                userSelect: "none",
                                transition: "background 0.15s",
                            }}
                        >
                            <span style={{ fontSize: "10px" }}>
                                {showSchema ? "▾" : "▸"}
                            </span>
                            Params
                            <span
                                style={{
                                    fontWeight: 400,
                                    opacity: 0.55,
                                    fontSize: "10px",
                                }}
                            >
                                ({schemaLines.length})
                            </span>
                        </div>
                        {showSchema && (
                            <div
                                style={{
                                    display: "flex",
                                    flexDirection: "column",
                                    background: "rgba(0,0,0,0.22)",
                                    borderRadius: "5px",
                                    padding: "5px 8px",
                                    gap: 1,
                                    marginTop: 6,
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
            <Handle type="source" position={Position.Right} />
        </>
    );
};

export default FactoryNode;
