import { Handle, Position } from "@xyflow/react";
import type { InvolvedEntity, SchemaLine, DtoMap } from "../../types";
import { flattenSchema } from "../../types";

/** Stick-figure person icon — classic event-storming actor symbol. */
const PersonIcon = () => (
    <svg
        viewBox="0 0 24 24"
        width="22"
        height="22"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        style={{ flexShrink: 0 }}
    >
        <circle cx="12" cy="5" r="3" />
        <line x1="12" y1="8" x2="12" y2="16" />
        <line x1="8" y1="11" x2="16" y2="11" />
        <line x1="12" y1="16" x2="8" y2="21" />
        <line x1="12" y1="16" x2="16" y2="21" />
    </svg>
);

/** Cylinder database icon for involved entities. */
const DatabaseIcon = ({ size = 12 }: { size?: number }) => (
    <svg
        viewBox="0 0 24 24"
        width={size}
        height={size}
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        style={{ flexShrink: 0 }}
        aria-hidden
    >
        <ellipse cx="12" cy="5" rx="9" ry="3" />
        <path d="M3 5v14c0 1.66 4.03 3 9 3s9-1.34 9-3V5" />
        <path d="M3 12c0 1.66 4.03 3 9 3s9-1.34 9-3" />
    </svg>
);

const iconMap: Record<string, React.ReactNode> = {
    person: <PersonIcon />,
};

const entityChipStyle = (isChild: boolean): React.CSSProperties => ({
    display: "inline-flex",
    alignItems: "center",
    gap: 5,
    background: isChild ? "rgba(15, 23, 42, 0.18)" : "rgba(15, 23, 42, 0.28)",
    border: isChild
        ? "1px dashed rgba(255,255,255,0.18)"
        : "1px solid rgba(255,255,255,0.22)",
    borderRadius: "999px",
    padding: "3px 9px 3px 7px",
    fontSize: isChild ? "10px" : "11px",
    fontWeight: isChild ? 500 : 600,
    letterSpacing: "0.01em",
    lineHeight: 1.2,
    whiteSpace: "nowrap",
    color: isChild ? "rgba(255,255,255,0.8)" : "rgba(255,255,255,0.95)",
    boxShadow: "inset 0 1px 0 rgba(255,255,255,0.08)",
});

const EntityChip = ({
    name,
    isChild = false,
}: {
    name: string;
    isChild?: boolean;
}) => (
    <span
        title={isChild ? `Related: ${name}` : `Entity: ${name}`}
        style={entityChipStyle(isChild)}
    >
        <span
            style={{
                display: "inline-flex",
                alignItems: "center",
                justifyContent: "center",
                width: isChild ? 14 : 16,
                height: isChild ? 14 : 16,
                borderRadius: "50%",
                background: "rgba(255,255,255,0.16)",
                color: "rgba(255,255,255,0.95)",
            }}
        >
            <DatabaseIcon size={isChild ? 9 : 11} />
        </span>
        {name}
    </span>
);

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

// ── Main component ──

const LabelNode = ({
    id,
    data,
}: {
    id: string;
    data: {
        label: string;
        copied?: boolean;
        httpMethod?: string;
        path?: string;
        summary?: string;
        icon?: string;
        involvedEntities?: InvolvedEntity[];
        payload?: Record<string, unknown>;
        dtos?: DtoMap;
        onEditCommand?: () => void;
        onAddEvent?: () => void;
        onEditEvent?: () => void;
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

    // Both commands and events can carry a payload schema; distinguish via callbacks.
    const isCommandNode = Boolean(data.onEditCommand || data.onAddEvent);
    const isEventNode = Boolean(data.onEditEvent);

    return (
        <>
            <Handle type="target" position={Position.Left} />
            <div
                style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 6,
                    textAlign: data.icon ? "left" : "center",
                    transition: "color 0.4s ease",
                    color: data.copied ? "#4ade80" : "inherit",
                    fontWeight: data.copied ? 600 : "inherit",
                }}
            >
                {data.icon && iconMap[data.icon] ? (
                    iconMap[data.icon]
                ) : data.icon ? (
                    <span style={{ flexShrink: 0 }}>{data.icon}</span>
                ) : null}
                <div style={data.icon ? undefined : { flex: 1, minWidth: 0 }}>
                    <div
                        style={{
                            display: "flex",
                            alignItems: "center",
                            justifyContent: data.icon
                                ? "flex-start"
                                : "center",
                            gap: 6,
                        }}
                    >
                        <span>
                            {data.copied ? "✓ Copied" : data.label}
                        </span>
                        {data.editMode && !data.icon && isCommandNode && (
                            <>
                                <button
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        data.onEditCommand?.();
                                    }}
                                    title="Edit command"
                                    style={{
                                        background:
                                            "rgba(255,255,255,0.15)",
                                        border: "1px solid rgba(255,255,255,0.25)",
                                        borderRadius: "6px",
                                        color: "#fff",
                                        width: "28px",
                                        height: "28px",
                                        fontSize: "16px",
                                        fontWeight: 700,
                                        cursor: "pointer",
                                        display: "inline-flex",
                                        alignItems: "center",
                                        justifyContent: "center",
                                        lineHeight: 1,
                                        padding: 0,
                                        flexShrink: 0,
                                    }}
                                >
                                    ✎
                                </button>
                                <button
                                    onClick={(e) => {
                                        e.stopPropagation();
                                        data.onAddEvent?.();
                                    }}
                                    title="Add event"
                                    style={{
                                        background:
                                            "rgba(255,255,255,0.2)",
                                        border: "1px solid rgba(255,255,255,0.3)",
                                        borderRadius: "6px",
                                        color: "#fff",
                                        width: "28px",
                                        height: "28px",
                                        fontSize: "18px",
                                        fontWeight: 700,
                                        cursor: "pointer",
                                        display: "inline-flex",
                                        alignItems: "center",
                                        justifyContent: "center",
                                        lineHeight: 1,
                                        padding: 0,
                                        flexShrink: 0,
                                    }}
                                >
                                    +
                                </button>
                            </>
                        )}
                        {data.editMode && !data.icon && isEventNode && (
                            <button
                                onClick={(e) => {
                                    e.stopPropagation();
                                    data.onEditEvent?.();
                                }}
                                title="Edit event"
                                style={{
                                    background:
                                        "rgba(255,255,255,0.15)",
                                    border: "1px solid rgba(255,255,255,0.25)",
                                    borderRadius: "6px",
                                    color: "#fff",
                                    width: "28px",
                                    height: "28px",
                                    fontSize: "16px",
                                    fontWeight: 700,
                                    cursor: "pointer",
                                    display: "inline-flex",
                                    alignItems: "center",
                                    justifyContent: "center",
                                    lineHeight: 1,
                                    padding: 0,
                                    flexShrink: 0,
                                }}
                            >
                                ✎
                            </button>
                        )}
                    </div>
                    {data.summary && !data.copied && (
                        <div
                            style={{
                                marginTop: 6,
                                fontSize: "13px",
                                fontWeight: 500,
                                lineHeight: 1.35,
                                opacity: 0.95,
                                color: "rgba(255,255,255,0.95)",
                            }}
                        >
                            {data.summary}
                        </div>
                    )}
                    {data.httpMethod && !data.copied && (
                        <div
                            style={{
                                marginTop: data.summary ? 8 : 6,
                                fontSize: "12px",
                                fontWeight: 500,
                                opacity: 0.85,
                                display: "flex",
                                alignItems: "center",
                                justifyContent: "center",
                                gap: 6,
                                flexWrap: "wrap",
                            }}
                        >
                            <table
                                style={{
                                    width: "100%",
                                    borderCollapse: "collapse",
                                    tableLayout: "fixed",
                                }}
                            >
                                <tbody>
                                    <tr>
                                        <td
                                            style={{
                                                width: "15%",
                                                verticalAlign: "top",
                                                whiteSpace: "nowrap",
                                            }}
                                        >
                                            <span
                                                style={{
                                                    background:
                                                        "rgba(255,255,255,0.2)",
                                                    borderRadius: "4px",
                                                    padding: "1px 6px",
                                                    fontSize: "11px",
                                                    fontWeight: 700,
                                                    letterSpacing: "0.02em",
                                                }}
                                            >
                                                {data.httpMethod}
                                            </span>
                                        </td>
                                        <td
                                            style={{
                                                width: "85%",
                                                textAlign: "left",
                                                paddingLeft: 6,
                                            }}
                                        >
                                            <span
                                                style={{
                                                    wordBreak: "break-all",
                                                }}
                                            >
                                                {data.path}
                                            </span>
                                        </td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>
                    )}
                    {schemaLines && schemaLines.length > 0 && (
                        <div
                            style={{
                                marginTop:
                                    data.summary || data.httpMethod ? 8 : 6,
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
                                title={showSchema ? "Hide schema" : "Show schema"}
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
                                Schema
                                <span style={{ fontWeight: 400, opacity: 0.55, fontSize: "10px" }}>
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
                                        <SchemaLineView
                                            key={i}
                                            line={line}
                                        />
                                    ))}
                                </div>
                            )}
                        </div>
                    )}
                    {data.involvedEntities &&
                        data.involvedEntities.length > 0 &&
                        !data.copied && (
                            <div
                                style={{
                                    marginTop:
                                        data.httpMethod ||
                                            data.summary ||
                                            (schemaLines && schemaLines.length > 0)
                                            ? 8
                                            : 6,
                                    fontSize: "12px",
                                    fontWeight: 500,
                                    opacity: 0.9,
                                    display: "flex",
                                    flexDirection: "column",
                                    gap: 6,
                                }}
                            >
                                {data.involvedEntities.map((ie) => (
                                    <div
                                        key={ie.entity}
                                        style={{
                                            display: "flex",
                                            flexDirection: "column",
                                            gap: 4,
                                        }}
                                    >
                                        <div
                                            style={{
                                                display: "flex",
                                                flexWrap: "wrap",
                                                gap: 5,
                                            }}
                                        >
                                            <EntityChip name={ie.entity} />
                                        </div>
                                        {ie.childEntity &&
                                            ie.childEntity.length > 0 && (
                                                <div
                                                    style={{
                                                        display: "flex",
                                                        flexWrap: "wrap",
                                                        gap: 4,
                                                        paddingLeft: 12,
                                                        borderLeft:
                                                            "2px solid rgba(255,255,255,0.18)",
                                                        marginLeft: 6,
                                                    }}
                                                >
                                                    {ie.childEntity.map(
                                                        (child) => (
                                                            <EntityChip
                                                                key={child}
                                                                name={child}
                                                                isChild
                                                            />
                                                        ),
                                                    )}
                                                </div>
                                            )}
                                    </div>
                                ))}
                            </div>
                        )}
                </div>
            </div>
            <Handle type="source" position={Position.Right} />
        </>
    );
};

export default LabelNode;
