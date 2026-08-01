import { useState } from "react";

const AddEventModal = ({
    commandName,
    onAdd,
    onClose,
}: {
    commandName: string;
    onAdd: (eventName: string, payload: Record<string, unknown>) => void;
    onClose: () => void;
}) => {
    const [name, setName] = useState("");
    const [fields, setFields] = useState<{ key: string; val: string }[]>([
        { key: "", val: "" },
    ]);

    const commit = () => {
        if (!name.trim()) return;
        const payload: Record<string, unknown> = {};
        fields
            .filter((f) => f.key.trim())
            .forEach((f) => {
                payload[f.key.trim()] = f.val.trim() || "string";
            });
        onAdd(name.trim(), payload);
    };

    return (
        <div className="modal-overlay" onClick={onClose}>
            <div
                className="modal-content"
                onClick={(e) => e.stopPropagation()}
                style={{ minWidth: 400 }}
            >
                <h2>
                    Add Event to{" "}
                    <span style={{ color: "#93c5fd" }}>
                        {commandName}
                    </span>
                </h2>
                <label>
                    Event Name
                    <input
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="e.g. OrderCreatedEvent"
                        autoFocus
                    />
                </label>
                <div
                    style={{
                        marginTop: 12,
                        borderTop: "1px solid #475569",
                        paddingTop: 12,
                    }}
                >
                    <div
                        style={{
                            display: "flex",
                            justifyContent: "space-between",
                            alignItems: "center",
                            marginBottom: 8,
                        }}
                    >
                        <span style={{ fontSize: 14, fontWeight: 600 }}>
                            Payload Fields <span style={{ opacity: 0.5, fontWeight: 400 }}>(optional)</span>
                        </span>
                        <button
                            onClick={() =>
                                setFields([
                                    ...fields,
                                    { key: "", val: "" },
                                ])
                            }
                            style={{
                                background: "rgba(255,255,255,0.1)",
                                border: "1px solid rgba(255,255,255,0.2)",
                                borderRadius: "4px",
                                color: "#fff",
                                padding: "3px 10px",
                                cursor: "pointer",
                                fontSize: 13,
                            }}
                        >
                            + Add Field
                        </button>
                    </div>
                    {fields.map((f, i) => (
                        <div
                            key={i}
                            style={{
                                display: "flex",
                                gap: 6,
                                marginBottom: 6,
                            }}
                        >
                            <input
                                value={f.key}
                                onChange={(e) => {
                                    const next = [...fields];
                                    next[i].key = e.target.value;
                                    setFields(next);
                                }}
                                placeholder="field name"
                                style={{ width: "45%", fontSize: 12 }}
                            />
                            <input
                                value={f.val}
                                onChange={(e) => {
                                    const next = [...fields];
                                    next[i].val = e.target.value;
                                    setFields(next);
                                }}
                                placeholder="type (e.g. number)"
                                style={{ width: "45%", fontSize: 12 }}
                            />
                            <button
                                onClick={() =>
                                    setFields(
                                        fields.filter(
                                            (_, j) => j !== i,
                                        ),
                                    )
                                }
                                style={{
                                    background: "none",
                                    border: "none",
                                    color: "#f87171",
                                    cursor: "pointer",
                                    fontSize: 16,
                                }}
                            >
                                ×
                            </button>
                        </div>
                    ))}
                </div>
                <div className="modal-actions">
                    <button
                        className="btn-secondary"
                        onClick={onClose}
                    >
                        Cancel
                    </button>
                    <button
                        className="btn-primary"
                        onClick={commit}
                        disabled={!name.trim()}
                    >
                        Add Event
                    </button>
                </div>
            </div>
        </div>
    );
};

export default AddEventModal;
