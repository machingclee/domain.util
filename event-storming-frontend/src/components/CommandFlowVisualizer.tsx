import { useMemo, useState, useCallback, useEffect, useRef } from "react";
import {
    ReactFlowProvider,
    type Node,
    type Connection,
    MarkerType,
    addEdge,
    useNodesState,
    useEdgesState,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";

import type { FlowData } from "../types";
import { sortPolicyFlows } from "../types";
import { useLayoutEngine } from "../hooks/useLayoutEngine";
import {
    NODE_WIDTH,
    ACTOR_NODE_WIDTH,
} from "../constants";
import FlowCanvas from "./FlowCanvas";
import AddCommandModal from "./AddCommandModal";
import AddEventModal from "./AddEventModal";
import EditCommandModal from "./EditCommandModal";
import AddPolicyModal from "./AddPolicyModal";
import AddContextModal from "./AddContextModal";
import EditContextModal from "./EditContextModal";
import EditEventModal from "./EditEventModal";

const GROUP_PADDING = 24;
const NODE_WIDTH_PX = parseInt(NODE_WIDTH, 10);
const ACTOR_NODE_WIDTH_PX = parseInt(ACTOR_NODE_WIDTH, 10);
const EST_NODE_HEIGHT = 130;
const EST_EVENT_NODE_HEIGHT = 48;

type ActiveTab = "commands" | "queries" | "factories" | "entities";

const CommandFlowVisualizer = (props: { commands: FlowData }) => {
    const [selectedNode, setSelectedNode] = useState<string | null>(null);
    const [selectedContext, setSelectedContext] = useState<string | null>(null);
    const [editMode, setEditMode] = useState(false);
    const [activeTab, setActiveTab] = useState<ActiveTab>("commands");
    const [flowData, setFlowData] = useState<FlowData>(props.commands);
    const [addCmdCtx, setAddCmdCtx] = useState<string | null>(null);
    const [addEvtCmd, setAddEvtCmd] = useState<string | null>(null);
    const [editingCmd, setEditingCmd] = useState<string | null>(null);
    const [editingEvt, setEditingEvt] = useState<string | null>(null);
    const [showAddPolicy, setShowAddPolicy] = useState(false);
    const [showAddContext, setShowAddContext] = useState(false);
    const [editingCtx, setEditingCtx] = useState<string | null>(null);
    const [showClearConfirm, setShowClearConfirm] = useState(false);
    const [expandedSchemas, setExpandedSchemas] = useState<Set<string>>(new Set());
    /** 0 = not ready; 1 = initial fit once; never bumped again on edits. */
    const [fitViewNonce, setFitViewNonce] = useState(0);
    const didInitialFit = useRef(false);
    const layoutGen = useRef(0);
    /** When true, next layout sync replaces positions (import / clear). */
    const replaceLayoutRef = useRef(false);
    /** Previous drag position — used to detect drag direction for shrink. */
    const prevDragPos = useRef({ x: 0, y: 0 });
    /** Skip shrink on the first drag frame (prevDragPos just seeded). */
    const dragFrame = useRef(0);

    useEffect(() => {
        if (!editMode) {
            setAddCmdCtx(null);
            setAddEvtCmd(null);
            setEditingCmd(null);
            setEditingEvt(null);
            setShowAddPolicy(false);
            setShowAddContext(false);
            setEditingCtx(null);
        }
    }, [editMode]);

    const { initialNodes, initialEdges } = useLayoutEngine({
        flowData,
        expandedSchemas,
        setFlowData,
        setAddCmdCtx,
        setAddEvtCmd,
        setEditingCmd,
        setEditingCtx,
        setEditingEvt,
        activeTab,
    });

    const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes);
    const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges);

    // Sync graph from layout. Keep positions of nodes that still exist so
    // rebuilds (e.g. +Policy) don't thrash React Flow internals mid-interaction.
    useEffect(() => {
        layoutGen.current += 1;
        const replace = replaceLayoutRef.current;
        replaceLayoutRef.current = false;
        setNodes((prev) => {
            if (replace || prev.length === 0) return initialNodes;
            const prevById = new Map(prev.map((n) => [n.id, n]));
            return initialNodes.map((n) => {
                const old = prevById.get(n.id);
                if (!old) return n;
                return {
                    ...n,
                    position: old.position,
                    width: old.width ?? n.width,
                    height: old.height ?? n.height,
                    measured: old.measured,
                    selected: old.selected,
                };
            });
        });
        setEdges(initialEdges);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [initialNodes, initialEdges]);

    const markNodeCopied = useCallback((nodeId: string) => {
        setNodes((nds) =>
            nds.map((n) =>
                n.id === nodeId ? { ...n, data: { ...n.data, copied: true } } : n,
            ),
        );
        setTimeout(() => {
            setNodes((nds) =>
                nds.map((n) =>
                    n.id === nodeId ? { ...n, data: { ...n.data, copied: false } } : n,
                ),
            );
        }, 1500);
    }, []);

    const onNodeClick = useCallback(
        (_event: React.MouseEvent, node: Node) => {
            if (node.id.startsWith("group-") || node.type === "groupNode" || node.type === "queryGroupNode" || node.type === "factoryGroupNode" || node.type === "entityGroupNode") {
                setSelectedContext(selectedContext === node.id ? null : node.id);
                setSelectedNode(null);
                return;
            }
            setSelectedContext(null);
            setSelectedNode(selectedNode === node.id ? null : node.id);
        },
        [selectedNode, selectedContext],
    );

    const onPaneClick = useCallback(() => {
        setSelectedNode(null);
        setSelectedContext(null);
    }, []);

    const onConnect = useCallback(
        (conn: Connection) => {
            if (!editMode) return;
            const srcId = conn.source ?? "";
            const tgtId = conn.target ?? "";
            const srcHandle = conn.sourceHandle ?? "";
            const tgtHandle = conn.targetHandle ?? "";

            const srcType = srcId.startsWith("command-") ? "command"
                : srcId.startsWith("event-") ? "event"
                : srcId.startsWith("policy-") ? "policy" : null;
            const tgtType = tgtId.startsWith("command-") ? "command"
                : tgtId.startsWith("event-") ? "event"
                : tgtId.startsWith("policy-") ? "policy" : null;

            if (!srcType || !tgtType) return;

            setFlowData((prev) => {
                const next = structuredClone(prev);

                if (srcType === "command" && tgtType === "event") {
                    const cmdName = srcId.slice("command-".length);
                    const evtName = tgtId.slice("event-".length);
                    const cmd = next.commands.find((c) => c.from.command === cmdName);
                    if (cmd && !cmd.to.find((e) => e.event === evtName)) {
                        cmd.to.push({ event: evtName, payload: next.schema?.[evtName] ?? {} });
                    }
                }

                if (srcType === "event" && tgtType === "policy") {
                    const evtName = srcId.slice("event-".length);
                    const polName = tgtId.slice("policy-".length);
                    const policy = next.policies[polName];
                    if (policy) {
                        const flowIdx = tgtHandle ? parseInt(tgtHandle.replace("t-", ""), 10) : -1;
                        if (flowIdx >= 0 && flowIdx < policy.flows.length) {
                            const sorted = sortPolicyFlows(policy.flows);
                            const orig = policy.flows.find((f) => f.invariant === sorted[flowIdx].invariant);
                            if (orig) orig.fromEvent = evtName;
                        } else {
                            policy.flows.push({ fromEvent: evtName, toCommand: null, invariant: "" });
                        }
                    }
                }

                if (srcType === "policy" && tgtType === "command") {
                    const polName = srcId.slice("policy-".length);
                    const cmdName = tgtId.slice("command-".length);
                    const policy = next.policies[polName];
                    if (policy) {
                        const flowIdx = srcHandle ? parseInt(srcHandle.replace("s-", ""), 10) : -1;
                        if (flowIdx >= 0 && flowIdx < policy.flows.length) {
                            const sorted = sortPolicyFlows(policy.flows);
                            const orig = policy.flows.find((f) => f.invariant === sorted[flowIdx].invariant);
                            if (orig) orig.toCommand = cmdName;
                        } else {
                            policy.flows.push({ fromEvent: null, toCommand: cmdName, invariant: "" });
                        }
                    }
                }

                return next;
            });

            setEdges((eds) =>
                addEdge(
                    { ...conn, animated: true, style: { stroke: "#64748b", strokeWidth: 2 }, markerEnd: { type: MarkerType.ArrowClosed, color: "#64748b" } },
                    eds,
                ),
            );
        },
        [editMode, setFlowData, setEdges],
    );

    const onNodeDoubleClick = useCallback(
        (_event: React.MouseEvent, node: Node) => {
            if (node.id.startsWith("group-") || node.type === "groupNode" || node.type === "queryGroupNode" || node.type === "factoryGroupNode" || node.type === "entityGroupNode") return;
            const label = node.data.label as string;
            navigator.clipboard.writeText(label).then(() => markNodeCopied(node.id)).catch(() => {});
        },
        [markNodeCopied],
    );

    const onNodeDragStart = useCallback(
        (_event: React.MouseEvent, node: Node) => {
            prevDragPos.current = { x: node.position.x, y: node.position.y };
            dragFrame.current = 0;
        },
        [],
    );

    const onNodeDrag = useCallback(
        (_event: React.MouseEvent, node: Node, _allNodes: Node[]) => {
            if (!node.parentId) return;

            // Detect drag direction so we can safely shrink the group when the
            // child is dragged away from a boundary (shift + drag complement
            // each other) without cancelling drags toward a boundary.
            const dragDx = node.position.x - prevDragPos.current.x;
            const dragDy = node.position.y - prevDragPos.current.y;
            prevDragPos.current = { x: node.position.x, y: node.position.y };
            dragFrame.current += 1;
            // First frame after drag-start: dx/dy are noise — never shrink.
            const canShrink = dragFrame.current > 1;

            setNodes((nds) => {
                let result = nds;

                // Cascade resize up the parent chain so the topmost ancestor
                // always fully encloses the dragged node and all its siblings.
                let currentGroupId: string | undefined = node.parentId;

                while (currentGroupId) {
                    const parent = result.find((n) => n.id === currentGroupId);
                    if (!parent) break;

                    // Collect all direct children of this group (including sub-group nodes).
                    // Prefer the live dragged position so bounds match the cursor frame.
                    const children = result.filter((n) => n.parentId === currentGroupId);
                    if (children.length === 0) break;

                    const curW =
                        parent.width ||
                        ((parent.style as Record<string, unknown>)?.width as number) ||
                        0;
                    const curH =
                        parent.height ||
                        ((parent.style as Record<string, unknown>)?.height as number) ||
                        0;

                    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
                    children.forEach((child) => {
                        const isGroup = child.id.startsWith("group-") || child.id.startsWith("query-group-") || child.id.startsWith("factory-group-") || child.id.startsWith("entity-group-");
                        const style = child.style as Record<string, unknown> | undefined;
                        const childW =
                            child.id.startsWith("actor-")
                                ? ACTOR_NODE_WIDTH_PX
                                : isGroup
                                ? (typeof child.width === "number" && child.width > 0
                                      ? child.width
                                      : typeof style?.width === "number"
                                      ? style.width
                                      : child.measured?.width) || NODE_WIDTH_PX
                                : NODE_WIDTH_PX;
                        const measured = child.measured?.height;
                        const childH =
                            isGroup
                                ? (typeof child.height === "number" && child.height > 0
                                      ? child.height
                                      : typeof style?.height === "number"
                                      ? style.height
                                      : measured) || EST_NODE_HEIGHT
                                : typeof measured === "number" && measured > 0
                                ? measured
                                : child.id.startsWith("actor-")
                                ? 48
                                : child.id.startsWith("event-")
                                ? EST_EVENT_NODE_HEIGHT
                                : EST_NODE_HEIGHT;
                        const pos = child.id === node.id ? node.position : child.position;
                        minX = Math.min(minX, pos.x);
                        minY = Math.min(minY, pos.y);
                        maxX = Math.max(maxX, pos.x + childW);
                        maxY = Math.max(maxY, pos.y + childH);
                    });

                    minX -= GROUP_PADDING;
                    const topPad = (parent.data as { minTopPadding?: number }).minTopPadding ?? GROUP_PADDING;
                    minY -= topPad;
                    maxX += GROUP_PADDING;
                    maxY += GROUP_PADDING;

                    // Expand when a child breaches the left/top boundary.
                    const expandLeft = minX < 0;
                    const expandUp = minY < 0;

                    // Growing the right/bottom edge — never run left/top shrink
                    // on the same frame or children snap left then chase the cursor.
                    const expandingRight = maxX > curW;
                    const expandingDown = maxY > curH;

                    // Shrink when the child is dragged AWAY from a previously
                    // expanded boundary — the shift complements the drag instead
                    // of cancelling it. Only when not expanding the opposite edge.
                    const shrinkLeft =
                        canShrink &&
                        !expandLeft &&
                        !expandingRight &&
                        minX > GROUP_PADDING &&
                        dragDx > 0;
                    const shrinkUp =
                        canShrink &&
                        !expandUp &&
                        !expandingDown &&
                        minY > topPad &&
                        dragDy > 0;

                    const shiftLeft = expandLeft || shrinkLeft;
                    const shiftUp = expandUp || shrinkUp;

                    const newX = shiftLeft ? parent.position.x + minX : parent.position.x;
                    const newY = shiftUp ? parent.position.y + minY : parent.position.y;
                    const newW = Math.max(shiftLeft ? maxX - minX : maxX, 200);
                    const newH = Math.max(shiftUp ? maxY - minY : maxY, 100);

                    const noChange =
                        newX === parent.position.x &&
                        newY === parent.position.y &&
                        newW === curW &&
                        newH === curH;

                    if (!noChange) {
                        const shiftX = shiftLeft ? -minX : 0;
                        const shiftY = shiftUp ? -minY : 0;
                        result = result.map((n) => {
                            if (n.id === parent.id) {
                                return {
                                    ...n,
                                    position: { x: newX, y: newY },
                                    width: newW,
                                    height: newH,
                                    style: { ...n.style, width: newW, height: newH },
                                };
                            }
                            if (
                                n.parentId === currentGroupId &&
                                (shiftX !== 0 || shiftY !== 0)
                            ) {
                                const base =
                                    n.id === node.id ? node.position : n.position;
                                return {
                                    ...n,
                                    position: {
                                        x: base.x + shiftX,
                                        y: base.y + shiftY,
                                    },
                                };
                            }
                            return n;
                        });
                    }

                    // Walk up to grandparent
                    currentGroupId = parent.parentId;
                }

                return result;
            });
        },
        [setNodes],
    );

    // One-shot policy column packing from real DOM heights (initial load only).
    // Never spins forever — that froze pan/zoom after +Policy.
    useEffect(() => {
        if (didInitialFit.current) return;

        let cancelled = false;
        let attempts = 0;
        let raf = 0;
        const gen = layoutGen.current;

        const done = () => {
            if (cancelled || didInitialFit.current) return;
            didInitialFit.current = true;
            setFitViewNonce(1);
        };

        const run = () => {
            if (cancelled || gen !== layoutGen.current) return;

            const expected = initialNodes.filter((n) =>
                n.id.startsWith("policy-"),
            ).length;
            if (expected === 0) {
                done();
                return;
            }

            // Only real nodes — edges also use data-id starting with "policy-..."
            const policyNodeEls = document.querySelectorAll(
                ".react-flow__node[data-id^='policy-']",
            );
            if (policyNodeEls.length < expected && attempts < 60) {
                attempts += 1;
                raf = requestAnimationFrame(run);
                return;
            }
            if (policyNodeEls.length === 0) {
                done();
                return;
            }

            const actualHeights = new Map<string, number>();
            policyNodeEls.forEach((el) => {
                const id = el.getAttribute("data-id");
                if (id) actualHeights.set(id, (el as HTMLElement).offsetHeight);
            });
            const policyWidthPx = 600;
            const gap = 60;
            const total = actualHeights.size;
            let nCols = 1;
            if (total > 3) nCols = 2;
            if (total > 8) nCols = 3;
            let maxFlows = 0;
            const entries: { id: string; height: number }[] = [];
            actualHeights.forEach((h, id) => {
                entries.push({ id, height: h });
                const node = initialNodes.find((n) => n.id === id);
                const flows = (node?.data as { flows?: unknown[] })?.flows;
                if (flows) maxFlows = Math.max(maxFlows, flows.length);
            });
            const baseX = 1500 + Math.max(0, (maxFlows - 6) * 55);
            const cols: { entries: typeof entries; colH: number }[] =
                Array.from({ length: nCols }, () => ({ entries: [], colH: 0 }));
            entries.forEach((e) => {
                let shortest = 0;
                for (let i = 1; i < cols.length; i++)
                    if (cols[i].colH < cols[shortest].colH) shortest = i;
                cols[shortest].entries.push(e);
                cols[shortest].colH += e.height + 170;
            });
            setNodes((nds) =>
                nds.map((n) => {
                    const h = actualHeights.get(n.id);
                    if (!h) return n;
                    let colIdx = 0,
                        rowY = 0;
                    let found = false;
                    for (let ci = 0; ci < cols.length && !found; ci++) {
                        let y = 0;
                        for (const e of cols[ci].entries) {
                            if (e.id === n.id) {
                                colIdx = ci;
                                rowY = y;
                                found = true;
                                break;
                            }
                            y += e.height + 170;
                        }
                    }
                    if (!found) return n;
                    const newX = baseX + colIdx * (policyWidthPx + gap);
                    if (n.position.x === newX && n.position.y === rowY)
                        return n;
                    return { ...n, position: { x: newX, y: rowY } };
                }),
            );
            done();
        };

        raf = requestAnimationFrame(run);
        return () => {
            cancelled = true;
            cancelAnimationFrame(raf);
        };
    }, [initialNodes, setNodes]);

    const allContexts = useMemo(
        () => [...new Set(flowData.commands.map((c) => c.context).filter(Boolean))],
        [flowData.commands],
    );

    const { highlightedNodes, highlightedEdges } = useMemo(() => {
        // Inject editMode + showSchema at render time so toggling them does not rebuild layout
        // or wipe React Flow handleBounds (which drops event→policy edges).
        const withEditMode = (n: Node): Node => ({
            ...n,
            data: {
                ...n.data,
                editMode,
                schemaExpanded: expandedSchemas.has(n.id),
                // Entity cards expand individual method param blocks via keys like entity-Foo::factory::bar
                expandedMethodKeys: [...expandedSchemas].filter((k) => k.startsWith(`${n.id}::`)),
                onToggleSchema: (nodeId: string) => {
                    replaceLayoutRef.current = true;
                    setExpandedSchemas((prev) => {
                        const next = new Set(prev);
                        if (next.has(nodeId)) next.delete(nodeId);
                        else next.add(nodeId);
                        return next;
                    });
                },
            },
        });

        // In view mode, edges must not be selectable/deletable so Delete/Backspace
        // and click-to-select cannot remove connections.
        const edgeInteraction = {
            selectable: editMode,
            focusable: editMode,
            deletable: editMode,
        };

        if (selectedContext) {
            // Core = selected context group + all descendants recursively.
            // This handles nested sub-contexts.
            const coreIds = new Set<string>([selectedContext]);
            const collectDescendants = (groupId: string) => {
                nodes.forEach((n) => {
                    if (n.parentId === groupId) {
                        coreIds.add(n.id);
                        if (n.id.startsWith("group-") || n.id.startsWith("query-group-") || n.id.startsWith("factory-group-") || n.id.startsWith("entity-group-")) collectDescendants(n.id);
                    }
                });
            };
            collectDescendants(selectedContext);
            const relatedIds = new Set(coreIds);
            edges.forEach((e) => {
                if (coreIds.has(e.source)) relatedIds.add(e.target);
                if (coreIds.has(e.target)) relatedIds.add(e.source);
            });
            const hn = nodes.map((n) =>
                withEditMode({
                    ...n,
                    style: { ...n.style, opacity: relatedIds.has(n.id) ? 1 : 0.08 },
                }),
            );
            const he = edges.map((e) => {
                const active =
                    relatedIds.has(e.source) && relatedIds.has(e.target);
                return {
                    ...e,
                    ...edgeInteraction,
                    style: {
                        ...e.style,
                        opacity: active ? 1 : 0.04,
                        strokeWidth: active ? 3 : 1,
                    },
                    animated: active,
                };
            });
            return { highlightedNodes: hn, highlightedEdges: he };
        }
        if (selectedNode) {
            const connectedIds = new Set<string>(); connectedIds.add(selectedNode);
            edges.forEach((e) => { if (e.source === selectedNode) connectedIds.add(e.target); if (e.target === selectedNode) connectedIds.add(e.source); });
            const hn = nodes.map((n) => withEditMode({ ...n, style: { ...n.style, opacity: connectedIds.has(n.id) ? 1 : 0.2 } }));
            const he = edges.map((e) => ({
                ...e,
                ...edgeInteraction,
                style: {
                    ...e.style,
                    opacity: e.source === selectedNode || e.target === selectedNode ? 1 : 0.1,
                    strokeWidth: e.source === selectedNode || e.target === selectedNode ? 3 : 2,
                },
                animated: e.source === selectedNode || e.target === selectedNode,
            }));
            return { highlightedNodes: hn, highlightedEdges: he };
        }
        return {
            highlightedNodes: nodes.map(withEditMode),
            highlightedEdges: edges.map((e) => ({ ...e, ...edgeInteraction })),
        };
    }, [nodes, edges, selectedNode, selectedContext, editMode, expandedSchemas]);

    const tabBtn = (tab: ActiveTab) => {
        const active = activeTab === tab;
        return {
            padding: "8px 22px",
            fontSize: "13px",
            fontWeight: 600 as const,
            letterSpacing: "0.01em",
            border: "none",
            borderRadius: "8px",
            cursor: "pointer",
            background: active ? "#1e40af" : "transparent",
            color: active ? "#ffffff" : "#475569",
            boxShadow: active ? "0 1px 3px rgba(30, 64, 175, 0.35)" : "none",
            transition: "background 0.15s ease, color 0.15s ease, box-shadow 0.15s ease",
        };
    };

    return (
        <ReactFlowProvider>
            {/* Tab bar */}
            <div style={{
                position: "absolute",
                top: 12,
                left: "50%",
                transform: "translateX(-50%)",
                zIndex: 10,
                display: "flex",
                gap: 4,
                padding: 4,
                borderRadius: 12,
                background: "#ffffff",
                border: "1px solid #e2e8f0",
                boxShadow: "0 2px 8px rgba(15, 23, 42, 0.08)",
            }}>
                <button style={tabBtn("commands")} onClick={() => {
                    replaceLayoutRef.current = true;
                    setActiveTab("commands");
                    setSelectedNode(null);
                    setSelectedContext(null);
                }}>
                    Commands
                </button>
                <button style={tabBtn("queries")} onClick={() => {
                    replaceLayoutRef.current = true;
                    setActiveTab("queries");
                    setSelectedNode(null);
                    setSelectedContext(null);
                }}>
                    Queries
                </button>
                <button style={tabBtn("entities")} onClick={() => {
                    replaceLayoutRef.current = true;
                    setActiveTab("entities");
                    setSelectedNode(null);
                    setSelectedContext(null);
                }}>
                    Entities
                </button>
            </div>
            <FlowCanvas
                highlightedNodes={highlightedNodes}
                highlightedEdges={highlightedEdges}
                editMode={editMode}
                fitViewNonce={fitViewNonce}
                flowData={flowData}
                onImportJson={(data: FlowData) => {
                    if (data.commands && data.policies) {
                        replaceLayoutRef.current = true;
                        didInitialFit.current = false;
                        setFitViewNonce(0);
                        setFlowData(data);
                    }
                }}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                onNodeClick={onNodeClick}
                onNodeDoubleClick={onNodeDoubleClick}
                onNodeDrag={onNodeDrag}
                onNodeDragStart={onNodeDragStart}
                onPaneClick={onPaneClick}
                onConnect={onConnect}
                onToggleEditMode={() => setEditMode(!editMode)}
                onSelectNode={(nodeId) => {
                    if (!nodeId) return;
                    if (nodeId.startsWith("group-") || nodeId.startsWith("query-group-") || nodeId.startsWith("factory-group-") || nodeId.startsWith("entity-group-")) {
                        setSelectedNode(null);
                        setSelectedContext(nodeId);
                    } else {
                        setSelectedContext(null);
                        setSelectedNode(nodeId);
                    }
                }}
                onTabChange={(tab) => setActiveTab(tab)}
                onAddContext={() => setShowAddContext(true)}
                onAddPolicy={() => setShowAddPolicy(true)}
                onClearAll={() => setShowClearConfirm(true)}
            />

            {/* ── Modals ── */}
            {addCmdCtx && (
                <AddCommandModal
                    context={addCmdCtx}
                    allContexts={allContexts}
                    onAdd={(cmd) => { setFlowData((prev) => { const next = structuredClone(prev); next.commands.unshift(cmd); return next; }); setAddCmdCtx(null); }}
                    onClose={() => setAddCmdCtx(null)}
                />
            )}
            {addEvtCmd && (
                <AddEventModal
                    commandName={addEvtCmd}
                    onAdd={(eventName, payload) => {
                        setFlowData((prev) => {
                            const next = structuredClone(prev);
                            const cmd = next.commands.find((c) => c.from.command === addEvtCmd);
                            if (cmd) { cmd.to.push({ event: eventName, payload }); if (next.schema) next.schema[eventName] = payload as Record<string, string>; }
                            return next;
                        });
                        setAddEvtCmd(null);
                    }}
                    onClose={() => setAddEvtCmd(null)}
                />
            )}
            {editingCmd && (() => {
                const cmd = flowData.commands.find((c) => c.from.command === editingCmd);
                if (!cmd) return null;
                return (
                    <EditCommandModal
                        command={cmd}
                        allContexts={allContexts}
                        onSave={(oldName, updated) => {
                            setFlowData((prev) => {
                                const next = structuredClone(prev);
                                const idx = next.commands.findIndex((c) => c.from.command === oldName);
                                if (idx >= 0) {
                                    next.commands[idx] = updated;
                                    if (oldName !== updated.from.command) {
                                        for (const pol of Object.values(next.policies))
                                            for (const f of pol.flows)
                                                if (f.toCommand === oldName) f.toCommand = updated.from.command;
                                    }
                                }
                                return next;
                            });
                            setEditingCmd(null);
                        }}
                        onClose={() => setEditingCmd(null)}
                    />
                );
            })()}
            {editingEvt && (
                <EditEventModal
                    oldName={editingEvt}
                    onSave={(oldName, newName) => {
                        setFlowData((prev) => {
                            const next = structuredClone(prev);
                            for (const cmd of next.commands) {
                                for (const t of cmd.to) {
                                    if (t.event === oldName) t.event = newName;
                                }
                            }
                            if (next.schema?.[oldName]) {
                                next.schema[newName] = next.schema[oldName];
                                delete next.schema[oldName];
                            }
                            for (const pol of Object.values(next.policies)) {
                                for (const f of pol.flows) {
                                    if (f.fromEvent === oldName) f.fromEvent = newName;
                                }
                            }
                            return next;
                        });
                        setEditingEvt(null);
                    }}
                    onClose={() => setEditingEvt(null)}
                />
            )}
            {editingCtx && (
                <EditContextModal
                    oldName={editingCtx}
                    onSave={(oldName, newName) => {
                        setFlowData((prev) => {
                            const next = structuredClone(prev);
                            for (const cmd of next.commands) {
                                if (cmd.context === oldName) {
                                    cmd.context = newName;
                                } else if (cmd.context && cmd.context.startsWith(oldName + ".")) {
                                    cmd.context = newName + cmd.context.slice(oldName.length);
                                }
                            }
                            return next;
                        });
                        setEditingCtx(null);
                    }}
                    onClose={() => setEditingCtx(null)}
                />
            )}
            {showAddContext && (
                <AddContextModal
                    onAdd={(name) => {
                        setFlowData((prev) => {
                            const next = structuredClone(prev);
                            next.commands.unshift({ from: { command: "NewCommand", payload: {} }, to: [], context: name, actors: [] });
                            return next;
                        });
                        setShowAddContext(false);
                    }}
                    onClose={() => setShowAddContext(false)}
                />
            )}
            {showAddPolicy && (
                <AddPolicyModal
                    onAdd={(name) => { setFlowData((prev) => { const next = structuredClone(prev); next.policies[name] = { flows: [] }; return next; }); setShowAddPolicy(false); }}
                    onClose={() => setShowAddPolicy(false)}
                />
            )}
            {showClearConfirm && (
                <div className="modal-overlay" onClick={() => setShowClearConfirm(false)}>
                    <div className="modal-content" onClick={(e) => e.stopPropagation()} style={{ minWidth: 380, textAlign: "center" }}>
                        <h2>Clear Everything?</h2>
                        <p style={{ color: "#94a3b8", marginBottom: 20 }}>
                            This will remove all commands, events, policies, and contexts. This action cannot be undone.
                        </p>
                        <div className="modal-actions" style={{ justifyContent: "center" }}>
                            <button className="btn-secondary" onClick={() => setShowClearConfirm(false)}>Cancel</button>
                            <button className="btn-danger" onClick={() => { replaceLayoutRef.current = true; setFlowData({ commands: [], policies: {}, factories: [], entities: [] }); setShowClearConfirm(false); }}>Clear All</button>
                        </div>
                    </div>
                </div>
            )}
        </ReactFlowProvider>
    );
};

export default CommandFlowVisualizer;
