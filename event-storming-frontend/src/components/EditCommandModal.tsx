import { useState, useEffect } from "react";
import type { Command } from "../types";

const EditCommandModal = ({
    command,
    allContexts,
    onSave,
    onClose,
}: {
    command: Command;
    allContexts: string[];
    onSave: (oldName: string, updated: Command) => void;
    onClose: () => void;
}) => {
    const [name, setName] = useState(command.from.command);
    const [ctxName, setCtxName] = useState(command.context ?? "");
    const [httpMethod, setHttpMethod] = useState(command.httpMethod ?? "");
    const [path, setPath] = useState(command.path ?? "");
    const [summary, setSummary] = useState(command.summary ?? "");
    const [actors, setActors] = useState(
        (command.actors ?? []).join(", "),
    );

    // Reset form when editing a different command
    useEffect(() => {
        setName(command.from.command);
        setCtxName(command.context ?? "");
        setHttpMethod(command.httpMethod ?? "");
        setPath(command.path ?? "");
        setSummary(command.summary ?? "");
        setActors((command.actors ?? []).join(", "));
    }, [command.from.command, command.context]);

    const commit = () => {
        if (!name.trim()) return;
        onSave(command.from.command, {
            ...command,
            context: ctxName.trim() || "Default",
            from: {
                command: name.trim(),
                payload: command.from.payload ?? {},
            },
            httpMethod: httpMethod || undefined,
            path: path.trim() || undefined,
            summary: summary.trim() || undefined,
            actors: actors
                .split(",")
                .map((s) => s.trim())
                .filter(Boolean),
        });
    };

    return (
        <div className="modal-overlay" onClick={onClose}>
            <div
                className="modal-content"
                onClick={(e) => e.stopPropagation()}
            >
                <h2>Edit Command</h2>
                <label>
                    Context{" "}
                    <span style={{ opacity: 0.5, fontWeight: 400 }}>
                        (use dots for nesting, e.g. Booking.ScheduleLink)
                    </span>
                    <input
                        value={ctxName}
                        onChange={(e) => setCtxName(e.target.value)}
                        placeholder="e.g. Booking or Booking.ScheduleLink"
                        list="edit-cmd-context-list"
                    />
                    <datalist id="edit-cmd-context-list">
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
                        autoFocus
                    />
                </label>
                <div style={{ display: "flex", gap: 10 }}>
                    <label style={{ flex: 1 }}>
                        HTTP Method{" "}
                        <span
                            style={{
                                opacity: 0.5,
                                fontWeight: 400,
                            }}
                        >
                            (optional)
                        </span>
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
                        Path{" "}
                        <span
                            style={{
                                opacity: 0.5,
                                fontWeight: 400,
                            }}
                        >
                            (optional)
                        </span>
                        <input
                            value={path}
                            onChange={(e) =>
                                setPath(e.target.value)
                            }
                            placeholder="/api/..."
                        />
                    </label>
                </div>
                <label>
                    Summary{" "}
                    <span
                        style={{ opacity: 0.5, fontWeight: 400 }}
                    >
                        (optional)
                    </span>
                    <input
                        value={summary}
                        onChange={(e) =>
                            setSummary(e.target.value)
                        }
                        placeholder="Brief description"
                    />
                </label>
                <label>
                    Actors{" "}
                    <span
                        style={{ opacity: 0.5, fontWeight: 400 }}
                    >
                        (optional)
                    </span>
                    <input
                        value={actors}
                        onChange={(e) =>
                            setActors(e.target.value)
                        }
                        placeholder="Admin, Customer"
                    />
                </label>
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
                        Save
                    </button>
                </div>
            </div>
        </div>
    );
};

export default EditCommandModal;
