/**
 * Context group frame for the Entities tab (same purple chrome as factory groups).
 */
const EntityGroupNode = ({
    data,
}: {
    data: {
        label: string;
        fullName?: string;
        depth?: number;
    };
}) => {
    const depth = data.depth ?? 0;
    const isSub = depth > 0;
    const displayName = isSub ? data.label : (data.fullName ?? data.label);

    return (
        <div
            title={data.fullName ?? data.label}
            style={{
                width: "100%",
                height: "100%",
                border: isSub
                    ? "1px dashed rgba(139,92,246,0.25)"
                    : "1px solid rgba(139,92,246,0.3)",
                borderRadius: isSub ? "8px" : "12px",
                backgroundColor: isSub
                    ? "rgba(139,92,246,0.06)"
                    : "rgba(139,92,246,0.12)",
                position: "relative",
                pointerEvents: "none",
            }}
        >
            <span
                style={{
                    position: "absolute",
                    top: isSub ? "-14px" : "-18px",
                    left: isSub ? "20px" : "16px",
                    background: isSub
                        ? "rgba(55,65,81,0.75)"
                        : "rgba(124, 58, 237, 0.85)",
                    padding: isSub ? "4px 12px" : "6px 20px",
                    fontSize: isSub ? "16px" : "24px",
                    color: "#e2e8f0",
                    fontWeight: 700,
                    borderRadius: isSub ? "6px" : "8px",
                    whiteSpace: "nowrap",
                    border: isSub
                        ? "1px solid rgba(139,92,246,0.2)"
                        : "1px solid rgba(139, 92, 246, 0.35)",
                    display: "flex",
                    alignItems: "center",
                    gap: "8px",
                    pointerEvents: "auto",
                }}
            >
                {isSub && (
                    <span style={{ opacity: 0.45, fontSize: "12px", fontWeight: 500 }}>
                        ↳
                    </span>
                )}
                {displayName}
            </span>
        </div>
    );
};

export default EntityGroupNode;
