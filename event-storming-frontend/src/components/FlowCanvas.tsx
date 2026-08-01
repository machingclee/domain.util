import { useCallback, useEffect, useRef, useState } from "react";
import {
    ReactFlow,
    Background,
    Controls,
    MiniMap,
    useReactFlow,
    useStore,
    type Node,
    type Edge,
    type EdgeChange,
} from "@xyflow/react";
import type { FlowData } from "../types";
import PolicyNode from "./nodes/PolicyNode";
import LabelNode from "./nodes/LabelNode";
import FactoryNode from "./nodes/FactoryNode";
import EntityNode from "./nodes/EntityNode";
import GroupNode from "./nodes/GroupNode";
import QueryGroupNode from "./nodes/QueryGroupNode";
import FactoryGroupNode from "./nodes/FactoryGroupNode";
import EntityGroupNode from "./nodes/EntityGroupNode";
import SpreadStepEdge from "./edges/SpreadStepEdge";
import SearchBar from "./SearchBar";

const nodeTypes = {
    policyNode: PolicyNode,
    labelNode: LabelNode,
    factoryNode: FactoryNode,
    entityNode: EntityNode,
    groupNode: GroupNode,
    queryGroupNode: QueryGroupNode,
    factoryGroupNode: FactoryGroupNode,
    entityGroupNode: EntityGroupNode,
};
const edgeTypes = { spreadStep: SpreadStepEdge };


/** Single initial fitView after first layout measure — never again on +Policy etc. */
function FitViewOnReady({ nonce }: { nonce: number }) {
    const { fitView } = useReactFlow();
    const nodesInitialized = useStore((s) => s.nodesInitialized);
    const didFit = useRef(false);

    useEffect(() => {
        if (didFit.current || !nodesInitialized || nonce < 1) return;
        const id = window.setTimeout(() => {
            if (didFit.current) return;
            didFit.current = true;
            fitView({ padding: 0.15 });
        }, 0);
        return () => clearTimeout(id);
    }, [nodesInitialized, nonce, fitView]);

    return null;
}

/**
 * Space + left-drag  OR  middle-click-drag → pan.
 * Middle-click always pans; left-drag requires Space held.
 * Notifies parent when Space is pressed/released so nodesDraggable can react.
 */
function SpaceOrMiddleDragPan({ onSpaceChange }: { onSpaceChange: (held: boolean) => void }) {
    const { getViewport, setViewport } = useReactFlow();
    const spaceHeld = useRef(false);
    const panning = useRef(false);
    const last = useRef({ x: 0, y: 0 });

    useEffect(() => {
        const onKeyDown = (e: KeyboardEvent) => {
            if (e.code === "Space" && !e.repeat) {
                spaceHeld.current = true;
                onSpaceChange(true);
            }
        };
        const onKeyUp = (e: KeyboardEvent) => {
            if (e.code === "Space") {
                spaceHeld.current = false;
                onSpaceChange(false);
            }
        };
        window.addEventListener("keydown", onKeyDown);
        window.addEventListener("keyup", onKeyUp);
        return () => {
            window.removeEventListener("keydown", onKeyDown);
            window.removeEventListener("keyup", onKeyUp);
        };
    }, [onSpaceChange]);

    useEffect(() => {
        const root = document.querySelector(".react-flow") as HTMLElement | null;
        if (!root) return;

        const onMouseDown = (e: MouseEvent) => {
            const target = e.target as HTMLElement;
            if (target.closest(".react-flow__edge,.react-flow__controls,.nowheel")) return;
            const shouldPan =
                e.button === 1 || e.button === 2 || (e.button === 0 && spaceHeld.current);
            if (!shouldPan) return;
            e.preventDefault();
            panning.current = true;
            last.current = { x: e.clientX, y: e.clientY };
        };

        const onMouseMove = (e: MouseEvent) => {
            if (!panning.current) return;
            const dx = last.current.x - e.clientX;
            const dy = last.current.y - e.clientY;
            last.current = { x: e.clientX, y: e.clientY };
            const vp = getViewport();
            setViewport({ x: vp.x - dx, y: vp.y - dy, zoom: vp.zoom });
        };

        const onMouseUp = () => {
            panning.current = false;
        };

        const onContextMenu = (e: Event) => e.preventDefault();

        root.addEventListener("mousedown", onMouseDown);
        root.addEventListener("contextmenu", onContextMenu);
        window.addEventListener("mousemove", onMouseMove);
        window.addEventListener("mouseup", onMouseUp);
        return () => {
            root.removeEventListener("mousedown", onMouseDown);
            root.removeEventListener("contextmenu", onContextMenu);
            window.removeEventListener("mousemove", onMouseMove);
            window.removeEventListener("mouseup", onMouseUp);
        };
    }, [getViewport, setViewport]);

    return null;
}

interface FlowCanvasProps {
    highlightedNodes: Node[];
    highlightedEdges: Edge[];
    editMode: boolean;
    fitViewNonce: number;
    onImportJson: (data: FlowData) => void;
    flowData: FlowData;
    onNodesChange: any;
    onEdgesChange: any;
    onNodeClick: any;
    onNodeDoubleClick: any;
    onNodeDrag: any;
    onNodeDragStart: any;
    onPaneClick: any;
    onConnect: any;
    onToggleEditMode: () => void;
    onSelectNode: (nodeId: string | null) => void;
    onTabChange?: (tab: "commands" | "queries" | "factories" | "entities") => void;
    onAddContext: () => void;
    onAddPolicy: () => void;
    onClearAll: () => void;
}

const FlowCanvas = ({
    highlightedNodes,
    highlightedEdges,
    editMode,
    fitViewNonce,
    flowData,
    onImportJson,
    onNodesChange,
    onEdgesChange,
    onNodeClick,
    onNodeDoubleClick,
    onNodeDrag,
    onNodeDragStart,
    onPaneClick,
    onConnect,
    onToggleEditMode,
    onSelectNode,
    onTabChange,
    onAddContext,
    onAddPolicy,
    onClearAll,
}: FlowCanvasProps) => {
    const [spaceActive, setSpaceActive] = useState(false);
    const { fitView } = useReactFlow();

    const navigateToNode = useCallback(
        (nodeId: string, kind: string) => {
            onSelectNode(nodeId);
            const targetTab =
                kind === "query" ? "queries"
                : kind === "factory" ? "factories"
                : kind === "entity" ? "entities"
                : "commands";
            onTabChange?.(targetTab);
            // Wait for tab switch + layout rebuild before centering.
            setTimeout(() => {
                fitView({ nodes: [{ id: nodeId }], padding: 0.3, duration: 0, maxZoom: 1.5 });
            }, 50);
        },
        [fitView, onSelectNode, onTabChange],
    );

    const importJson = useCallback(() => {
        const input = document.createElement("input");
        input.type = "file";
        input.accept = ".json";
        input.onchange = (e) => {
            const file = (e.target as HTMLInputElement).files?.[0];
            if (!file) return;
            const reader = new FileReader();
            reader.onload = () => {
                try {
                    const data = JSON.parse(reader.result as string);
                    onImportJson(data);
                } catch {
                    alert("Invalid JSON file");
                }
            };
            reader.readAsText(file);
        };
        input.click();
    }, [onImportJson]);


    const exportJson = useCallback(() => {
        const json = JSON.stringify(flowData, null, 2);
        const blob = new Blob([json], { type: "application/json" });
        const url = URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = "commands.json";
        a.click();
        URL.revokeObjectURL(url);
    }, [flowData]);

    // Always attach so RF does not rebind the handler on first edit toggle.
    // Block destructive edge changes outside edit mode.
    const handleEdgesChange = useCallback(
        (changes: EdgeChange[]) => {
            const next = editMode
                ? changes
                : changes.filter((c) => c.type !== "remove");
            if (next.length === 0) return;
            onEdgesChange(next);
        },
        [editMode, onEdgesChange],
    );

    return (
        <div className={`w-screen h-screen ${editMode ? "edit-mode" : ""}`} style={{ position: "relative" }}>
            <div className="flow-toolbar">
                <SearchBar flowData={flowData} onNavigate={navigateToNode} />
                <div className="flow-toolbar-row">
                    <button
                        type="button"
                        className={`flow-btn flow-btn-edit${editMode ? " is-on" : ""}`}
                        onClick={onToggleEditMode}
                    >
                        <span className="flow-btn-edit-dot" aria-hidden />
                        {editMode ? "Edit Mode: ON" : "Edit Mode: OFF"}
                    </button>
                    <button
                        type="button"
                        className="flow-btn flow-btn-ghost"
                        onClick={importJson}
                        disabled={editMode}
                    >
                        Import JSON
                    </button>
                    <button
                        type="button"
                        className="flow-btn flow-btn-ghost"
                        onClick={exportJson}
                        disabled={editMode}
                    >
                        Export JSON
                    </button>
                </div>
                {editMode && (
                    <div className="flow-toolbar-row">
                        <button type="button" className="flow-btn flow-btn-accent" onClick={onAddContext}>
                            + Context
                        </button>
                        <button type="button" className="flow-btn flow-btn-accent" onClick={onAddPolicy}>
                            + Policy
                        </button>
                        <button type="button" className="flow-btn flow-btn-danger-ghost" onClick={onClearAll}>
                            Clear All
                        </button>
                    </div>
                )}
            </div>
            <ReactFlow
                nodeTypes={nodeTypes}
                edgeTypes={edgeTypes}
                nodes={highlightedNodes}
                edges={highlightedEdges}
                minZoom={0.05}
                attributionPosition="bottom-left"
                panOnScroll={true}
                zoomOnScroll={true}
                zoomOnPinch={true}
                zoomOnDoubleClick={true}
                panOnDrag={false}
                nodesDraggable={!spaceActive}
                nodesConnectable={editMode}
                edgesFocusable={editMode}
                edgesReconnectable={editMode}
                // Prevent accidental edge/node deletion outside edit mode
                deleteKeyCode={editMode ? ["Backspace", "Delete"] : null}
                defaultEdgeOptions={{
                    selectable: editMode,
                    focusable: editMode,
                    deletable: editMode,
                }}
                onConnect={editMode ? onConnect : undefined}
                onNodesChange={onNodesChange}
                onEdgesChange={handleEdgesChange}
                onNodeClick={onNodeClick}
                onNodeDoubleClick={onNodeDoubleClick}
                onNodeDrag={onNodeDrag}
                onNodeDragStart={onNodeDragStart}
                onPaneClick={onPaneClick}
            >
                <FitViewOnReady nonce={fitViewNonce} />
                <SpaceOrMiddleDragPan onSpaceChange={setSpaceActive} />
                <Background />
                <Controls />
                <MiniMap
                    nodeColor={(node) => {
                        if (node.type === "queryGroupNode") return "#047857";
                        if (node.type === "factoryGroupNode" || node.type === "entityGroupNode") return "#7c3aed";
                        if (node.id.startsWith("group-") || node.id.startsWith("entity-group-")) return "#334155";
                        if (node.id.startsWith("actor-")) return "#eab308";
                        if (node.id.startsWith("command-")) return "#2563eb";
                        if (node.id.startsWith("event-")) return "#f97316";
                        if (node.id.startsWith("factory-")) return "#10b981";
                        if (node.id.startsWith("entity-")) return "#0d9488";
                        if (node.id.startsWith("policy-"))
                            return getComputedStyle(document.documentElement).getPropertyValue("--policy-bg").trim() || "#a78bba";
                        return "#64748b";
                    }}
                />
            </ReactFlow>
        </div>
    );
};

export default FlowCanvas;
