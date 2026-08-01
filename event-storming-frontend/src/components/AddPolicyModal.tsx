import { useState } from "react";

const AddPolicyModal = ({
    onAdd,
    onClose,
}: {
    onAdd: (name: string) => void;
    onClose: () => void;
}) => {
    const [name, setName] = useState("");

    const commit = () => {
        if (!name.trim()) return;
        const value = name.trim();
        // Close after the click fully finishes so RF doesn't keep a stuck
        // pointer-capture / drag state when the overlay unmounts mid-gesture.
        window.setTimeout(() => onAdd(value), 0);
    };

    return (
        <div className="modal-overlay" onClick={onClose}>
            <div
                className="modal-content"
                onClick={(e) => e.stopPropagation()}
            >
                <h2>Add Policy</h2>
                <label>
                    Policy Name
                    <input
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="e.g. BookingPolicy"
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
                        Create Policy
                    </button>
                </div>
            </div>
        </div>
    );
};

export default AddPolicyModal;
