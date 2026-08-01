import { useState, useEffect } from "react";
import Markdown from "react-markdown";
import { Handle, Position, useNodeId, useUpdateNodeInternals } from "@xyflow/react";
import type { PolicyFlow } from "../../types";
import { sortPolicyFlows } from "../../types";
import { POLICY_NODE_WIDTH } from "../../constants";

const stripCommonIndent = (s: string | undefined | null): string => {
    if (!s) return s ?? "";
    const lines = s.split("\n");
    // find min indent across non-blank lines
    let minIndent = Infinity;
    for (const line of lines) {
        if (line.trim().length > 0) {
            const indent = line.match(/^ */)![0].length;
            if (indent < minIndent) minIndent = indent;
        }
    }
    if (minIndent === Infinity || minIndent === 0) return s;
    // strip exactly minIndent spaces from each non-blank line
    return lines
        .map((line) => {
            if (line.trim().length === 0) return "";
            const skip = Math.min(minIndent, line.length);
            return line.substring(skip);
        })
        .join("\n");
};

const PolicyNode = ({
    data,
}: {
    data: {
        label: string;
        flows: PolicyFlow[];
        copied?: boolean;
        editMode?: boolean;
        onUpdateFlows?: (flows: PolicyFlow[]) => void;
    };
}) => {
    const nodeId = useNodeId();
    const updateNodeInternals = useUpdateNodeInternals();
    const [editingFlowIdx, setEditingFlowIdx] = useState<number | null>(null);
    const [editingField, setEditingField] = useState<{
        idx: number;
        field: "fromEvent" | "toCommand";
    } | null>(null);
    const [showAddModal, setShowAddModal] = useState(false);
    const [newFlow, setNewFlow] = useState<PolicyFlow>({
        fromEvent: "",
        toCommand: "",
        invariant: "",
    });

    // Close add-flow modal when edit mode is turned off
    useEffect(() => {
        if (!data.editMode) {
            setShowAddModal(false);
            setEditingFlowIdx(null);
            setEditingField(null);
        }
    }, [data.editMode]);

    const sortedFlows = sortPolicyFlows(data.flows);

    // Re-measure handles after edit chrome / flow list changes size.
    useEffect(() => {
        if (!nodeId) return;
        updateNodeInternals(nodeId);
    }, [nodeId, data.editMode, data.flows, sortedFlows.length, updateNodeInternals]);

    const updateFlow = (idx: number, patch: Partial<PolicyFlow>) => {
        const next = [...data.flows];
        const target = next.find(
            (f) => f.invariant === sortedFlows[idx].invariant,
        );
        if (target) Object.assign(target, patch);
        data.onUpdateFlows?.(next);
    };

    const openAddModal = () => {
        setNewFlow({ fromEvent: "", toCommand: "", invariant: "" });
        setShowAddModal(true);
    };

    const commitAddFlow = () => {
        if (!newFlow.invariant.trim()) return;
        const next = [
            ...data.flows,
            {
                fromEvent: newFlow.fromEvent?.trim() || null,
                toCommand: newFlow.toCommand?.trim() || null,
                invariant: newFlow.invariant,
            },
        ];
        data.onUpdateFlows?.(next);
        setShowAddModal(false);
    };

    return (
    <div
        style={{
            background: "var(--policy-bg)",
            border: "2px solid var(--policy-border)",
            borderRadius: "8px",
            padding: "10px 14px",
            color: "#fff",
            width: POLICY_NODE_WIDTH,
        }}
    >
        <div
            style={{
                display: "flex",
                justifyContent: "center",
                alignItems: "center",
                letterSpacing: "0.05em",
                opacity: 0.9,
                fontSize: 30,
                marginBottom: "8px",
                paddingBottom: "6px",
                borderBottom: "1px solid rgba(255,255,255,0.45)",
                transition: "color 0.4s ease",
                color: data.copied ? "#4ade80" : "inherit",
                fontWeight: data.copied ? 600 : "inherit",
            }}
        >
            <span>{data.copied ? "✓ Copied" : data.label}</span>
            {data.editMode && (
                <button
                    onClick={openAddModal}
                    title="Add flow"
                    style={{
                        marginLeft: "12px",
                        background: "rgba(255,255,255,0.15)",
                        border: "1px solid rgba(255,255,255,0.3)",
                        borderRadius: "6px",
                        color: "#fff",
                        width: "32px",
                        height: "32px",
                        fontSize: "22px",
                        fontWeight: 700,
                        cursor: "pointer",
                        display: "flex",
                        alignItems: "center",
                        justifyContent: "center",
                        lineHeight: 1,
                        flexShrink: 0,
                    }}
                >
                    +
                </button>
            )}
        </div>
        <div
            style={{
                display: "flex",
                justifyContent: "center",
                fontSize: "24px",
                letterSpacing: "0.05em",
                opacity: 0.85,
                marginBottom: "6px",
            }}
        >
            Invariants
        </div>
        <ul style={{ margin: 0, paddingLeft: "16px", listStyleType: "none" }}>
            {sortedFlows.map((flow, i) => (
                    <li
                        key={i}
                        style={{
                            position: "relative",
                            marginBottom: "10px",
                            borderRadius: "6px",
                            padding: "8px 10px",
                        }}
                    >
                        <Handle type="target" position={Position.Left} id={`t-${i}`} style={{ left: -30 }} />
                        <Handle type="source" position={Position.Right} id={`s-${i}`} style={{ right: -10 }} />
                        <div
                            style={{ fontSize: "20px", lineHeight: 1.2, marginBottom: "4px" }}
                            className="markdown-invariant"
                        >
                            <div style={{ display: "flex" }}>
                                <div
                                    style={{
                                        marginRight: "8px",
                                        opacity: 0.6,
                                        fontSize: "18px",
                                        fontStyle: "italic",
                                    }}
                                >
                                    <div
                                        style={{
                                            background: "rgba(255,255,255,0.2)",
                                            borderRadius: "4px",
                                            padding: "4px 6px",
                                        }}
                                    >
                                        {String(i + 1).padStart(2, "0")}.
                                    </div>
                                </div>
                                <div style={{ flex: 1 }}>
                                    <div style={{ marginBottom: 10 }}>
                                        {data.editMode &&
                                        editingFlowIdx === i ? (
                                            <textarea
                                                className="policy-edit-textarea"
                                                value={flow.invariant}
                                                onChange={(e) =>
                                                    updateFlow(i, {
                                                        invariant:
                                                            e.target.value,
                                                    })
                                                }
                                                onBlur={() =>
                                                    setEditingFlowIdx(
                                                        null,
                                                    )
                                                }
                                                autoFocus
                                            />
                                        ) : (
                                            <div
                                                onClick={() =>
                                                    data.editMode &&
                                                    setEditingFlowIdx(
                                                        i,
                                                    )
                                                }
                                                style={{
                                                    cursor: data.editMode
                                                        ? "text"
                                                        : "default",
                                                }}
                                            >
                                                <Markdown>
                                                    {stripCommonIndent(
                                                        flow.invariant ||
                                                            " ",
                                                    )}
                                                </Markdown>
                                            </div>
                                        )}
                                    </div>
                                    <div
                                        style={{
                                            display: "flex",
                                            gap: "6px",
                                            flexWrap: "wrap",
                                            alignItems: "center",
                                        }}
                                    >
                                        {/* fromEvent tag — editable in edit mode */}
                                        {data.editMode &&
                                        editingField?.idx === i &&
                                        editingField?.field === "fromEvent" ? (
                                            <input
                                                value={flow.fromEvent ?? ""}
                                                onChange={(e) =>
                                                    updateFlow(i, {
                                                        fromEvent:
                                                            e.target.value ||
                                                            null,
                                                    })
                                                }
                                                onBlur={() =>
                                                    setEditingField(null)
                                                }
                                                onKeyDown={(e) => {
                                                    if (e.key === "Enter")
                                                        setEditingField(
                                                            null,
                                                        );
                                                }}
                                                autoFocus
                                                style={{
                                                    width: "180px",
                                                    padding: "2px 6px",
                                                    background:
                                                        "rgba(249,115,22,0.3)",
                                                    border: "1px solid rgba(249,115,22,0.6)",
                                                    borderRadius: "4px",
                                                    color: "#fff",
                                                    fontSize: "14px",
                                                }}
                                            />
                                        ) : flow.fromEvent ? (
                                            <span
                                                onClick={() =>
                                                    data.editMode &&
                                                    setEditingField({
                                                        idx: i,
                                                        field: "fromEvent",
                                                    })
                                                }
                                                style={{
                                                    display: "inline-block",
                                                    background:
                                                        "rgba(249,115,22,0.85)",
                                                    border: "1px solid rgba(255,255,255,0.4)",
                                                    borderRadius: "4px",
                                                    padding: "1px 7px",
                                                    fontSize: "16px",
                                                    letterSpacing: "0.02em",
                                                    cursor: data.editMode
                                                        ? "pointer"
                                                        : "default",
                                                }}
                                                title={
                                                    data.editMode
                                                        ? "Click to edit"
                                                        : undefined
                                                }
                                            >
                                                {flow.fromEvent}
                                            </span>
                                        ) : (
                                            <span
                                                onClick={() =>
                                                    data.editMode &&
                                                    setEditingField({
                                                        idx: i,
                                                        field: "fromEvent",
                                                    })
                                                }
                                                style={{
                                                    display: "inline-block",
                                                    background:
                                                        "rgba(0,0,0,0.25)",
                                                    border: "1px solid rgba(255,255,255,0.2)",
                                                    borderRadius: "4px",
                                                    padding: "1px 7px",
                                                    fontSize: "14px",
                                                    fontStyle: "italic",
                                                    opacity: 0.7,
                                                    cursor: data.editMode
                                                        ? "pointer"
                                                        : "default",
                                                }}
                                                title={
                                                    data.editMode
                                                        ? "Click to set event"
                                                        : undefined
                                                }
                                            >
                                                No Event
                                            </span>
                                        )}
                                        <span style={{ fontSize: "11px", opacity: 0.7 }}>
                                            →
                                        </span>
                                        {/* toCommand tag — editable in edit mode */}
                                        {data.editMode &&
                                        editingField?.idx === i &&
                                        editingField?.field === "toCommand" ? (
                                            <input
                                                value={flow.toCommand ?? ""}
                                                onChange={(e) =>
                                                    updateFlow(i, {
                                                        toCommand:
                                                            e.target.value ||
                                                            null,
                                                    })
                                                }
                                                onBlur={() =>
                                                    setEditingField(null)
                                                }
                                                onKeyDown={(e) => {
                                                    if (e.key === "Enter")
                                                        setEditingField(
                                                            null,
                                                        );
                                                }}
                                                autoFocus
                                                style={{
                                                    width: "180px",
                                                    padding: "2px 6px",
                                                    background:
                                                        "rgba(37,99,235,0.3)",
                                                    border: "1px solid rgba(37,99,235,0.6)",
                                                    borderRadius: "4px",
                                                    color: "#fff",
                                                    fontSize: "14px",
                                                }}
                                            />
                                        ) : flow.toCommand ? (
                                            <span
                                                onClick={() =>
                                                    data.editMode &&
                                                    setEditingField({
                                                        idx: i,
                                                        field: "toCommand",
                                                    })
                                                }
                                                style={{
                                                    display: "inline-block",
                                                    background:
                                                        "rgba(37,99,235,0.85)",
                                                    border: "1px solid rgba(255,255,255,0.4)",
                                                    borderRadius: "4px",
                                                    padding: "1px 7px",
                                                    fontSize: "16px",
                                                    letterSpacing: "0.02em",
                                                    cursor: data.editMode
                                                        ? "pointer"
                                                        : "default",
                                                }}
                                                title={
                                                    data.editMode
                                                        ? "Click to edit"
                                                        : undefined
                                                }
                                            >
                                                {flow.toCommand}
                                            </span>
                                        ) : (
                                            <span
                                                onClick={() =>
                                                    data.editMode &&
                                                    setEditingField({
                                                        idx: i,
                                                        field: "toCommand",
                                                    })
                                                }
                                                style={{
                                                    display: "inline-block",
                                                    background:
                                                        "rgba(0,0,0,0.25)",
                                                    border: "1px solid rgba(255,255,255,0.2)",
                                                    borderRadius: "4px",
                                                    padding: "1px 7px",
                                                    fontSize: "14px",
                                                    fontStyle: "italic",
                                                    opacity: 0.7,
                                                    cursor: data.editMode
                                                        ? "pointer"
                                                        : "default",
                                                }}
                                                title={
                                                    data.editMode
                                                        ? "Click to set command"
                                                        : undefined
                                                }
                                            >
                                                No Command
                                            </span>
                                        )}
                                    </div>
                                </div>
                            </div>
                        </div>
                    </li>
                ))}
        </ul>
        {data.editMode && sortedFlows.length > 0 && (
            <div
                style={{
                    marginTop: 4,
                    marginBottom: 8,
                    textAlign: "right",
                    fontSize: "13px",
                    opacity: 0.7,
                }}
            >
                Click invariant text to edit · drag handles to connect
            </div>
        )}
        {showAddModal && (
            <div className="modal-overlay" onClick={() => setShowAddModal(false)}>
                <div
                    className="modal-content"
                    onClick={(e) => e.stopPropagation()}
                    style={{ minWidth: 440 }}
                >
                    <h2>Add Flow</h2>
                    <label>
                        Invariant
                        <textarea
                            rows={4}
                            value={newFlow.invariant}
                            onChange={(e) =>
                                setNewFlow((f) => ({
                                    ...f,
                                    invariant: e.target.value,
                                }))
                            }
                            placeholder="Describe the business rule..."
                            autoFocus
                        />
                    </label>
                    <label>
                        From Event (optional)
                        <input
                            value={newFlow.fromEvent ?? ""}
                            onChange={(e) =>
                                setNewFlow((f) => ({
                                    ...f,
                                    fromEvent: e.target.value,
                                }))
                            }
                            placeholder="e.g. OrderCreatedEvent"
                        />
                    </label>
                    <label>
                        To Command (optional)
                        <input
                            value={newFlow.toCommand ?? ""}
                            onChange={(e) =>
                                setNewFlow((f) => ({
                                    ...f,
                                    toCommand: e.target.value,
                                }))
                            }
                            placeholder="e.g. CancelOrderCommand"
                        />
                    </label>
                    <div className="modal-actions">
                        <button
                            className="btn-secondary"
                            onClick={() => setShowAddModal(false)}
                        >
                            Cancel
                        </button>
                        <button
                            className="btn-primary"
                            onClick={commitAddFlow}
                            disabled={!newFlow.invariant.trim()}
                        >
                            Add
                        </button>
                    </div>
                </div>
            </div>
        )}
    </div>
    );
};

export default PolicyNode;
