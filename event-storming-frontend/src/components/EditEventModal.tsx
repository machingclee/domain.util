import { useState, useEffect } from "react";

const EditEventModal = ({
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
        if (!name.trim() || name.trim() === oldName) { onClose(); return; }
        onSave(oldName, name.trim());
    };

    return (
        <div className="modal-overlay" onClick={onClose}>
            <div className="modal-content" onClick={(e) => e.stopPropagation()}>
                <h2>Rename Event</h2>
                <label>
                    Event Name
                    <input
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        autoFocus
                        onKeyDown={(e) => { if (e.key === "Enter") commit(); }}
                    />
                </label>
                <div className="modal-actions">
                    <button className="btn-secondary" onClick={onClose}>Cancel</button>
                    <button className="btn-primary" onClick={commit} disabled={!name.trim()}>Rename</button>
                </div>
            </div>
        </div>
    );
};

export default EditEventModal;
