import { useState, useEffect } from "react";

const EditContextModal = ({
    oldName,
    onSave,
    onClose,
}: {
    oldName: string;
    onSave: (oldName: string, newName: string) => void;
    onClose: () => void;
}) => {
    const [name, setName] = useState(oldName);
    useEffect(() => setName(oldName), [oldName]);

    const commit = () => {
        if (!name.trim() || name.trim() === oldName) {
            onClose();
            return;
        }
        onSave(oldName, name.trim());
    };

    return (
        <div className="modal-overlay" onClick={onClose}>
            <div
                className="modal-content"
                onClick={(e) => e.stopPropagation()}
            >
                <h2>Rename Context</h2>
                <label>
                    Context Name{" "}
                    <span style={{ opacity: 0.5, fontWeight: 400 }}>
                        (use dots for nesting; children rename by prefix)
                    </span>
                    <input
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="e.g. Booking or Booking.ScheduleLink"
                        autoFocus
                        onKeyDown={(e) => {
                            if (e.key === "Enter") commit();
                        }}
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
                        Rename
                    </button>
                </div>
            </div>
        </div>
    );
};

export default EditContextModal;
