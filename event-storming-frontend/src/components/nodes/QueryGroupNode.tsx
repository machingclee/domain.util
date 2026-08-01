const QueryGroupNode = ({
    data,
}: {
    data: {
        label: string;
        fullName?: string;
        depth?: number;
        editMode?: boolean;
        onAddCommand?: () => void;
        onEditContext?: () => void;
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
                    ? "1px dashed rgba(148,163,184,0.25)"
                    : "1px solid rgba(148,163,184,0.3)",
                borderRadius: isSub ? "8px" : "12px",
                backgroundColor: isSub
                    ? "rgba(52,211,153,0.06)"
                    : "rgba(52,211,153,0.12)",
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
                        : "rgba(4, 120, 87, 0.85)",
                    padding: isSub ? "4px 12px" : "6px 20px",
                    fontSize: isSub ? "16px" : "24px",
                    color: "#e2e8f0",
                    fontWeight: 700,
                    borderRadius: isSub ? "6px" : "8px",
                    whiteSpace: "nowrap",
                    border: isSub
                        ? "1px solid rgba(148,163,184,0.2)"
                        : "1px solid rgba(52, 211, 153, 0.35)",
                    cursor: "pointer",
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
                {data.editMode && (
                    <>
                        <button
                            onClick={(e) => {
                                e.stopPropagation();
                                data.onEditContext?.();
                            }}
                            title="Rename context"
                            style={{
                                background: "rgba(255,255,255,0.15)",
                                border: "1px solid rgba(255,255,255,0.25)",
                                borderRadius: "6px",
                                color: "#fff",
                                width: isSub ? "24px" : "28px",
                                height: isSub ? "24px" : "28px",
                                fontSize: isSub ? "14px" : "16px",
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
                                data.onAddCommand?.();
                            }}
                            title="Add query"
                            style={{
                                background: "rgba(255,255,255,0.15)",
                                border: "1px solid rgba(255,255,255,0.25)",
                                borderRadius: "6px",
                                color: "#fff",
                                width: isSub ? "24px" : "28px",
                                height: isSub ? "24px" : "28px",
                                fontSize: isSub ? "16px" : "20px",
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
            </span>
        </div>
    );
};

export default QueryGroupNode;
