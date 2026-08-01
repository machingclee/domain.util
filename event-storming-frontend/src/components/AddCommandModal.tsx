import { useState } from "react";
import type { Command } from "../types";

const AddCommandModal = ({
    context,
    allContexts,
    onAdd,
    onClose,
}: {
    context: string;
    allContexts: string[];
    onAdd: (cmd: Command) => void;
    onClose: () => void;
}) => {
    const [name, setName] = useState("");
    const [ctxName, setCtxName] = useState(context);
    const [httpMethod, setHttpMethod] = useState("");
    const [path, setPath] = useState("");
    const [summary, setSummary] = useState("");
    const [actors, setActors] = useState("");
    const [events, setEvents] = useState<
        { event: string; fields: { key: string; val: string }[] }[]
    >([]);

    const addEventRow = () =>
        setEvents([
            ...events,
            { event: "", fields: [{ key: "", val: "" }] },
        ]);

    const commit = () => {
        if (!name.trim()) return;
        const to = events
            .filter((e) => e.event.trim())
            .map((e) => {
                const payload: Record<string, unknown> = {};
                e.fields
                    .filter((f) => f.key.trim())
                    .forEach((f) => {
                        payload[f.key.trim()] = f.val.trim();
                    });
                return { event: e.event.trim(), payload };
            });
        onAdd({
            from: {
                command: name.trim(),
                payload: {},
            },
            to,
            context: ctxName.trim() || "Default",
            httpMethod: httpMethod || undefined,
            path: path.trim() || undefined,
            summary: summary.trim() || undefined,
            actors: actors
                .split(",")
                .map((s) => s.trim())
                .filter(Boolean),
            involvedEntities: [],
        });
    };

    return (
        <div className="modal-overlay" onClick={onClose}>
            <div
                className="modal-content"
                onClick={(e) => e.stopPropagation()}
                style={{ maxHeight: "80vh", overflow: "auto" }}
            >
                <h2>Add Command</h2>
                <label>
                    Context{" "}
                    <span style={{ opacity: 0.5, fontWeight: 400 }}>
                        (use dots for nesting, e.g. Booking.ScheduleLink)
                    </span>
                    <input
                        value={ctxName}
                        onChange={(e) => setCtxName(e.target.value)}
                        placeholder="e.g. Booking or Booking.ScheduleLink"
                        list="add-cmd-context-list"
                        autoFocus
                    />
                    <datalist id="add-cmd-context-list">
                        {allContexts.map((c) => (
                            <option key={c} value={c} />
                        ))}
                    </datalist>
                </label>
                <label>
                    Command Name
                    <input
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="e.g. CreateOrderCommand"
                    />
                </label>
                <div
                    style={{
                        display: "flex",
                        gap: 10,
                    }}
                >
                    <label style={{ flex: 1 }}>
                        HTTP Method <span style={{ opacity: 0.5, fontWeight: 400 }}>(optional)</span>
                        <select
                            value={httpMethod}
                            onChange={(e) =>
                                setHttpMethod(e.target.value)
                            }
                        >
                            <option value="">—</option>
                            <option>GET</option>
                            <option>POST</option>
                            <option>PUT</option>
                            <option>DELETE</option>
                            <option>PATCH</option>
                        </select>
                    </label>
                    <label style={{ flex: 2 }}>
                        Path <span style={{ opacity: 0.5, fontWeight: 400 }}>(optional)</span>
                        <input
                            value={path}
                            onChange={(e) => setPath(e.target.value)}
                            placeholder="/api/..."
                        />
                    </label>
                </div>
                <label>
                    Summary <span style={{ opacity: 0.5, fontWeight: 400 }}>(optional)</span>
                    <input
                        value={summary}
                        onChange={(e) => setSummary(e.target.value)}
                        placeholder="Brief description"
                    />
                </label>
                <label>
                    Actors <span style={{ opacity: 0.5, fontWeight: 400 }}>(optional)</span>
                    <input
                        value={actors}
                        onChange={(e) => setActors(e.target.value)}
                        placeholder="Admin, Customer"
                    />
                </label>

                {/* Events */}
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
                        <span
                            style={{
                                fontSize: 14,
                                fontWeight: 600,
                            }}
                        >
                            Events
                        </span>
                        <button
                            onClick={addEventRow}
                            style={{
                                background:
                                    "rgba(255,255,255,0.1)",
                                border: "1px solid rgba(255,255,255,0.2)",
                                borderRadius: "4px",
                                color: "#fff",
                                padding: "3px 10px",
                                cursor: "pointer",
                                fontSize: 13,
                            }}
                        >
                            + Add Event
                        </button>
                    </div>
                    {events.map((ev, ei) => (
                        <div
                            key={ei}
                            style={{
                                marginBottom: 10,
                                padding: 10,
                                background: "rgba(0,0,0,0.2)",
                                borderRadius: 6,
                            }}
                        >
                            <div
                                style={{
                                    display: "flex",
                                    gap: 8,
                                    alignItems: "center",
                                }}
                            >
                                <input
                                    value={ev.event}
                                    onChange={(e) => {
                                        const next = [...events];
                                        next[ei].event =
                                            e.target.value;
                                        setEvents(next);
                                    }}
                                    placeholder="Event name"
                                    style={{ flex: 1 }}
                                />
                                <button
                                    onClick={() =>
                                        setEvents(
                                            events.filter(
                                                (_, i) =>
                                                    i !== ei,
                                            ),
                                        )
                                    }
                                    style={{
                                        background: "none",
                                        border: "none",
                                        color: "#f87171",
                                        cursor: "pointer",
                                        fontSize: 18,
                                    }}
                                >
                                    ×
                                </button>
                            </div>
                            {ev.fields.map((f, fi) => (
                                <div
                                    key={fi}
                                    style={{
                                        display: "flex",
                                        gap: 6,
                                        marginTop: 4,
                                    }}
                                >
                                    <input
                                        value={f.key}
                                        onChange={(e) => {
                                            const next = [
                                                ...events,
                                            ];
                                            next[ei].fields[
                                                fi
                                            ].key =
                                                e.target.value;
                                            setEvents(next);
                                        }}
                                        placeholder="field"
                                        style={{
                                            width: "45%",
                                            fontSize: 12,
                                        }}
                                    />
                                    <input
                                        value={f.val}
                                        onChange={(e) => {
                                            const next = [
                                                ...events,
                                            ];
                                            next[ei].fields[
                                                fi
                                            ].val =
                                                e.target.value;
                                            setEvents(next);
                                        }}
                                        placeholder="type"
                                        style={{
                                            width: "45%",
                                            fontSize: 12,
                                        }}
                                    />
                                    <button
                                        onClick={() => {
                                            const next = [
                                                ...events,
                                            ];
                                            next[ei].fields =
                                                next[
                                                    ei
                                                ].fields.filter(
                                                    (
                                                        _,
                                                        j,
                                                    ) =>
                                                        j !==
                                                        fi,
                                                );
                                            setEvents(next);
                                        }}
                                        style={{
                                            background: "none",
                                            border: "none",
                                            color: "#f87171",
                                            cursor: "pointer",
                                            fontSize: 14,
                                        }}
                                    >
                                        ×
                                    </button>
                                    {fi ===
                                        ev.fields.length -
                                            1 && (
                                        <button
                                            onClick={() => {
                                                const next =
                                                    [
                                                        ...events,
                                                    ];
                                                next[
                                                    ei
                                                ].fields.push(
                                                    {
                                                        key: "",
                                                        val: "",
                                                    },
                                                );
                                                setEvents(
                                                    next,
                                                );
                                            }}
                                            style={{
                                                background:
                                                    "none",
                                                border: "none",
                                                color: "#60a5fa",
                                                cursor: "pointer",
                                                fontSize: 14,
                                            }}
                                        >
                                            +
                                        </button>
                                    )}
                                </div>
                            ))}
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
                        Create Command
                    </button>
                </div>
            </div>
        </div>
    );
};

export default AddCommandModal;
