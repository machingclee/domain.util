import { useState } from "react";

const AddContextModal = ({
    onAdd,
    onClose,
}: {
    onAdd: (name: string) => void;
    onClose: () => void;
}) => {
    const [name, setName] = useState("");

    const commit = () => {
        if (!name.trim()) return;
        onAdd(name.trim());
    };

    return (
        <div className="modal-overlay" onClick={onClose}>
            <div
                className="modal-content"
                onClick={(e) => e.stopPropagation()}
            >
                <h2>Add Context</h2>
                <label>
                    Context Name{" "}
                    <span style={{ opacity: 0.5, fontWeight: 400 }}>
                        (use dots for nesting, e.g. Booking.ScheduleLink)
                    </span>
                    <input
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="e.g. Inventory or Booking.ScheduleLink"
                        autoFocus
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
                        Create Context
                    </button>
                </div>
            </div>
        </div>
    );
};

export default AddContextModal;
