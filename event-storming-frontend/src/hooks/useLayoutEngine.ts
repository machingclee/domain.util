import { useMemo } from "react";
import {
    type Node,
    type Edge,
    MarkerType,
    Position,
} from "@xyflow/react";
import type {
    Command,
    PolicyData,
    PolicyFlow,
    FlowData,
    EventPayload,
    ContextNode,
    QueryItem,
    QueryContextNode,
    FactoryMethod,
    EntityNode as EntityNodeData,
    EntityMethod,
    EntityRelation,
} from "../types";
import {
    resolveCommandPrincipals,
    countResolvedSchemaLines,
    estimateEventNodeHeight,
    sortPolicyFlows,
    buildContextTree,
    buildQueryContextTree,
    entitiesFromFlowData,
} from "../types";
import {
    VERTICAL_SPACING,
    CONTEXT_GAP,
    NODE_WIDTH,
    ACTOR_NODE_WIDTH,
    POLICY_NODE_WIDTH,
    FONT_SIZE,
    ACTOR_X,
    COMMAND_X,
    EVENT_X,
    POLICY_X,
} from "../constants";

const EST_NODE_HEIGHT = 130;
const EST_EVENT_NODE_HEIGHT = 48;
const EVENT_STACK_GAP = 100;
const COMMAND_BLOCK_GAP = 48;
const ACTOR_EDGE_COLOR = "#ca8a04";
const GROUP_PADDING = 24;
/** Extra top inset so the floating context badge (top: -18px, ~48px tall) does not cover the first command/actor cards when a group has few commands. */
const GROUP_TOP_PADDING = 56;
/** Gap between sub-context groups within a parent context. */
const SUB_CONTEXT_GAP = 36;
/** Horizontal indent per depth level for sub-context groups. */
const SUB_CONTEXT_INDENT = 28;
/** Horizontal gap between query and result columns in query groups. */
const QUERY_COLUMN_GAP = 60;
const NODE_WIDTH_PX = parseInt(NODE_WIDTH, 10);
const ACTOR_NODE_WIDTH_PX = parseInt(ACTOR_NODE_WIDTH, 10);
/** Horizontal fan-out step for actor→command edges (must match edge data.spread). */
const ACTOR_EDGE_SPREAD_STEP = 18;
/**
 * Min clearance between a spread-edge vertical segment and either node edge.
 * Keeps step corners from visually clipping into actor/command cards.
 */
const ACTOR_EDGE_MARGIN = 48;
/** Default handle-to-handle gap when maxActorOffset is 0. */
const BASE_ACTOR_COMMAND_GAP =
    COMMAND_X - ACTOR_X - ACTOR_NODE_WIDTH_PX;
/**
 * Approximate actor card height (padding + icon/label; allows 2-line wrap
 * for names like "Unregistered Customer" in the fixed actor width).
 * Used only to de-collide actors that share the same ideal Y
 * (e.g. two principals on one command).
 */
const EST_ACTOR_NODE_HEIGHT = 64;
/** Vertical gap kept between stacked actor cards. */
const ACTOR_STACK_GAP = 28;

function estimateCommandHeight(
    item: Command,
    expanded: boolean,
    dtos?: Record<string, Record<string, string>>,
): number {
    let height = 52;
    if (item.summary?.trim()) height += 40;
    if (item.httpMethod) height += 30;
    if (expanded) {
        const payload = item.from?.payload as Record<string, unknown> | undefined;
        if (payload && Object.keys(payload).length > 0) {
            const lines = countResolvedSchemaLines(payload, dtos);
            height += 12 + Math.max(1, lines) * 17;
        }
    }
    const entities = item.involvedEntities ?? [];
    if (entities.length > 0) {
        let entityRows = 0;
        for (const ie of entities) {
            entityRows += 1;
            if (ie.childEntity && ie.childEntity.length > 0) entityRows += 1;
        }
        height += 12 + entityRows * 28;
    }
    return Math.max(height, EST_NODE_HEIGHT);
}

function estimateCommandBlockHeight(
    item: Command,
    dtos: Record<string, Record<string, string>> | undefined,
    expandedSet: Set<string>,
): number {
    const eventCount = item.to.length;
    const cmdExpanded = expandedSet.has(`command-${item.from.command}`);
    if (eventCount === 0) return estimateCommandHeight(item, cmdExpanded, dtos) + COMMAND_BLOCK_GAP;
    const eventHeights = item.to.map((ep) => {
        const evExpanded = expandedSet.has(`event-${ep.event}`);
        const lines = evExpanded
            ? countResolvedSchemaLines(ep.payload as Record<string, unknown>, dtos)
            : 0;
        return estimateEventNodeHeight(lines);
    });
    const maxEventHeight = Math.max(...eventHeights, EST_EVENT_NODE_HEIGHT);
    const eventStep = Math.max(EVENT_STACK_GAP, maxEventHeight + 40);
    const eventStackSpan = (eventCount - 1) * eventStep + maxEventHeight;
    const commandHeight = estimateCommandHeight(item, cmdExpanded, dtos);
    const totalEventHeight = (eventCount - 1) * eventStep;
    const commandSpan = totalEventHeight / 2 + commandHeight;
    return Math.max(eventStackSpan, commandSpan) + 60;
}

function resolveEventHeights(
    item: Command,
    dtos: Record<string, Record<string, string>> | undefined,
    expandedSet: Set<string>,
): { heights: number[]; maxHeight: number; step: number } {
    const eventCount = item.to.length;
    if (eventCount === 0)
        return { heights: [], maxHeight: EST_EVENT_NODE_HEIGHT, step: EVENT_STACK_GAP };
    const heights = item.to.map((ep) => {
        const evExpanded = expandedSet.has(`event-${ep.event}`);
        const lines = evExpanded
            ? countResolvedSchemaLines(ep.payload as Record<string, unknown>, dtos)
            : 0;
        return estimateEventNodeHeight(lines);
    });
    const maxHeight = Math.max(...heights, EST_EVENT_NODE_HEIGHT);
    const step = Math.max(EVENT_STACK_GAP, maxHeight + 40);
    return { heights, maxHeight, step };
}

interface LayoutEngineParams {
    flowData: FlowData;
    expandedSchemas: Set<string>;
    setFlowData: React.Dispatch<React.SetStateAction<FlowData>>;
    setAddCmdCtx: React.Dispatch<React.SetStateAction<string | null>>;
    setAddEvtCmd: React.Dispatch<React.SetStateAction<string | null>>;
    setEditingCmd: React.Dispatch<React.SetStateAction<string | null>>;
    setEditingCtx: React.Dispatch<React.SetStateAction<string | null>>;
    setEditingEvt: React.Dispatch<React.SetStateAction<string | null>>;
    activeTab?: "commands" | "queries" | "factories" | "entities";
}

export function useLayoutEngine({
    flowData,
    expandedSchemas,
    setFlowData,
    setAddCmdCtx,
    setAddEvtCmd,
    setEditingCmd,
    setEditingCtx,
    setEditingEvt,
    activeTab = "commands",
}: LayoutEngineParams) {
    return useMemo(() => {
        const dtos = flowData.dtos;
        const queryDtos = flowData.queryDtos ?? flowData.dtos;
        const nodeMap = new Map<string, Node>();
        const edgeList: Edge[] = [];

        // ── Query layout (recursive context tree, mirrors commands) ──
        if (activeTab === "queries") {
            const queries = flowData.queries ?? [];
            if (queries.length === 0) return { initialNodes: [], initialEdges: [] };

            /** Estimate vertical span of a single query → result block. */
            const estimateQueryBlockHeight = (
                item: QueryItem,
                expSet: Set<string>,
            ): number => {
                const qId = `query-${item.from.query}`;
                const resultId = `result-${item.result.query}`;
                const qExpanded = expSet.has(qId);
                const rExpanded = expSet.has(resultId);

                let qHeight = 52; // base label
                if (item.summary?.trim()) qHeight += 40;
                if (item.httpMethod) qHeight += 30;
                if (qExpanded) {
                    const qPayload = item.from?.payload as Record<string, unknown> | undefined;
                    if (qPayload && Object.keys(qPayload).length > 0) {
                        const lines = countResolvedSchemaLines(qPayload, queryDtos);
                        qHeight += 12 + Math.max(1, lines) * 17;
                    }
                }

                let rHeight = 52; // base label
                if (rExpanded) {
                    const rPayload = item.result?.payload as Record<string, unknown> | undefined;
                    if (rPayload && Object.keys(rPayload).length > 0) {
                        const lines = countResolvedSchemaLines(rPayload, queryDtos);
                        rHeight += 12 + Math.max(1, lines) * 17;
                    }
                }

                return Math.max(qHeight, rHeight, EST_NODE_HEIGHT) + COMMAND_BLOCK_GAP;
            };

            // Query column positions — no actor column, so query sits at the left edge.
            const relQueryX = GROUP_PADDING;
            const relResultX = GROUP_PADDING + NODE_WIDTH_PX + QUERY_COLUMN_GAP;
            const queryGroupContentWidth =
                2 * GROUP_PADDING + 2 * NODE_WIDTH_PX + QUERY_COLUMN_GAP;

            // Build context tree from all query contexts (mirrors buildContextTree)
            const [qContextRoots] = buildQueryContextTree(queries);

            // Sort queries within each context node
            const sortQueries = (qItems: QueryItem[]) => {
                qItems.sort((a, b) =>
                    a.from.query.localeCompare(b.from.query, undefined, {
                        sensitivity: "base",
                    }),
                );
            };

            const sortQueryContextTree = (node: QueryContextNode) => {
                sortQueries(node.queries);
                node.children.forEach(sortQueryContextTree);
            };
            qContextRoots.forEach(sortQueryContextTree);

            let yOffset = 0;

            /**
             * Lay out a single query context node's direct queries + result nodes.
             * Mirrors layoutContextCommands but without actors or events.
             */
            const layoutQueryContextItems = (
                contextNode: QueryContextNode,
                groupId: string,
                groupStartY: number,
            ): { groupHeight: number } => {
                const qItems = contextNode.queries;
                const itemPlacements: {
                    item: QueryItem;
                    qId: string;
                    relY: number;
                }[] = [];

                let innerY = groupStartY + GROUP_TOP_PADDING;

                qItems.forEach((item) => {
                    const qId = `query-${item.from.query}`;
                    const relY = innerY - groupStartY;
                    itemPlacements.push({ item, qId, relY });
                    innerY += estimateQueryBlockHeight(item, expandedSchemas);
                });

                const directContentEndY = innerY;

                // Create query + result nodes
                itemPlacements.forEach(({ item, qId, relY }) => {
                    if (!nodeMap.has(qId)) {
                        nodeMap.set(qId, {
                            id: qId,
                            type: "labelNode",
                            data: {
                                label: item.from.query,
                                summary: item.summary?.trim() || undefined,
                                httpMethod: item.httpMethod,
                                path: item.path,
                                payload: item.from.payload,
                                dtos: queryDtos,
                            },
                            position: { x: relQueryX, y: relY },
                            parentId: groupId,
                            style: {
                                background: "rgba(37,99,235,0.8)",
                                color: "#fff",
                                border: "2px solid #1d4ed8",
                                borderRadius: "8px",
                                padding: "10px",
                                fontSize: FONT_SIZE,
                                width: NODE_WIDTH,
                            },
                            sourcePosition: Position.Right,
                            targetPosition: Position.Left,
                        });
                    }

                    const resultId = `result-${item.result.query}`;
                    if (!nodeMap.has(resultId)) {
                        nodeMap.set(resultId, {
                            id: resultId,
                            type: "labelNode",
                            data: {
                                label: item.result.query || "Result",
                                payload: item.result.payload,
                                dtos: queryDtos,
                            },
                            position: { x: relResultX, y: relY },
                            parentId: groupId,
                            style: {
                                background: "rgba(249,115,22,0.8)",
                                color: "#fff",
                                border: "2px solid #ea580c",
                                borderRadius: "8px",
                                padding: "10px",
                                fontSize: FONT_SIZE,
                                width: NODE_WIDTH,
                            },
                            sourcePosition: Position.Right,
                            targetPosition: Position.Left,
                        });
                    }

                    edgeList.push({
                        id: `${qId}-${resultId}`,
                        source: qId,
                        target: resultId,
                        animated: true,
                        style: { stroke: "#64748b", strokeWidth: 2 },
                        markerEnd: {
                            type: MarkerType.ArrowClosed,
                            color: "#64748b",
                        },
                    });
                });

                const directHeight =
                    directContentEndY - groupStartY + GROUP_PADDING;
                return { groupHeight: directHeight };
            };

            /**
             * Recursively lay out a query context node and its sub-contexts.
             * Mirrors layoutContextNode.
             */
            const layoutQueryContextNode = (
                contextNode: QueryContextNode,
                parentGroupId: string | null,
                parentStartY: number,
            ): { height: number; width: number } => {
                const contextName = contextNode.name;
                const depth = contextNode.depth;
                const groupId = `query-group-${contextName}`;
                const groupStartY = yOffset;
                const isSub = parentGroupId !== null;

                // Lay out this context's direct queries
                const { groupHeight: directHeight } =
                    layoutQueryContextItems(contextNode, groupId, groupStartY);

                let groupEndY = groupStartY + directHeight;
                let maxChildWidth = 0;

                // Recursively lay out sub-contexts below the direct queries
                if (contextNode.children.length > 0) {
                    // If this node has no direct queries, still leave badge clearance
                    if (contextNode.queries.length === 0) {
                        groupEndY = groupStartY + GROUP_TOP_PADDING;
                    }
                    yOffset = groupEndY;

                    contextNode.children.forEach((childNode) => {
                        yOffset += SUB_CONTEXT_GAP;
                        const childResult = layoutQueryContextNode(
                            childNode,
                            groupId,
                            groupStartY,
                        );
                        maxChildWidth = Math.max(maxChildWidth, childResult.width);
                    });

                    groupEndY = yOffset + GROUP_PADDING;
                }

                const groupHeight = groupEndY - groupStartY;
                const ownWidth = queryGroupContentWidth;
                const groupWidth = Math.max(
                    ownWidth,
                    maxChildWidth > 0 ? maxChildWidth + GROUP_PADDING * 2 : 0,
                );

                // Top-level groups use absolute canvas position.
                // Sub-groups use position RELATIVE to their parent.
                const posX = isSub ? GROUP_PADDING : ACTOR_X - GROUP_PADDING;
                const posY = isSub ? groupStartY - parentStartY : groupStartY;

                nodeMap.set(groupId, {
                    id: groupId,
                    type: "queryGroupNode",
                    data: {
                        label: contextNode.label,
                        fullName: contextName,
                        depth,
                        minTopPadding: GROUP_TOP_PADDING,
                        onAddCommand: () => setAddCmdCtx(contextName),
                        onEditContext: () => setEditingCtx(contextName),
                    },
                    position: { x: posX, y: posY },
                    width: groupWidth,
                    height: groupHeight,
                    parentId: parentGroupId ?? undefined,
                    extent: isSub ? ("parent" as const) : undefined,
                    zIndex: depth * 10,
                    selectable: true,
                    focusable: true,
                    draggable: true,
                    style: {
                        width: groupWidth,
                        height: groupHeight,
                    },
                });

                yOffset = groupEndY + (depth === 0 ? CONTEXT_GAP : 0);
                return { height: groupHeight, width: groupWidth };
            };

            // Lay out all top-level query contexts
            qContextRoots.forEach((rootNode) => {
                layoutQueryContextNode(rootNode, null, 0);
            });

            const groups = Array.from(nodeMap.values())
                .filter((n) => n.id.startsWith("query-group-"))
                .sort((a, b) => {
                    const depthA =
                        (a.data as { depth?: number }).depth ?? 0;
                    const depthB =
                        (b.data as { depth?: number }).depth ?? 0;
                    if (depthA !== depthB) return depthA - depthB;
                    return a.id.localeCompare(b.id);
                });
            const rest = Array.from(nodeMap.values()).filter(
                (n) => !n.id.startsWith("query-group-"),
            );
            return { initialNodes: [...groups, ...rest], initialEdges: edgeList };
        }

        // ── Legacy factory layout (kept if something still requests factories tab) ──
        if (activeTab === "factories") {
            const factories = flowData.factories ?? [];
            if (factories.length === 0) return { initialNodes: [], initialEdges: [] };

            const facDtos = flowData.factoryDtos ?? flowData.dtos;

            const FACTORY_BG = "rgba(139,92,246,0.8)";
            const FACTORY_BORDER = "#7c3aed";
            const byEntity = new Map<string, FactoryMethod[]>();
            factories.forEach((f) => {
                const entity = f.entityName || "Unknown";
                if (!byEntity.has(entity)) byEntity.set(entity, []);
                byEntity.get(entity)!.push(f);
            });

            let fYOffset = 0;
            const fEntries = [...byEntity.entries()];
            const FACTORY_METHOD_GAP = 16;
            const estFactoryHeight = (item: FactoryMethod, expanded: boolean): number => {
                let h = 40;
                const p = item.parameters as Record<string, unknown> | undefined;
                if (p && Object.keys(p).length > 0) {
                    h += 28;
                    if (expanded) {
                        const lines = countResolvedSchemaLines(p, facDtos);
                        h += 12 + Math.max(1, lines) * 17;
                    }
                }
                return h;
            };

            fEntries.forEach(([entityName, items]) => {
                const groupStartY = fYOffset;
                const groupId = `factory-group-${entityName}`;
                fYOffset += GROUP_TOP_PADDING;
                const relNodeX = GROUP_PADDING;
                const groupWidth = NODE_WIDTH_PX + 2 * GROUP_PADDING;
                const placements: { item: FactoryMethod; nodeId: string; relY: number }[] = [];

                items.forEach((item) => {
                    const paramSig = Object.values(item.parameters).join(",");
                    const nodeId = paramSig
                        ? `factory-${item.entityName}-${item.methodName}(${paramSig})`
                        : `factory-${item.entityName}-${item.methodName}`;
                    const expanded = expandedSchemas.has(nodeId);
                    const blockHeight = estFactoryHeight(item, expanded);
                    const relY = fYOffset - groupStartY;
                    placements.push({ item, nodeId, relY });
                    fYOffset += blockHeight + FACTORY_METHOD_GAP;
                });

                fYOffset += GROUP_PADDING;
                const groupEndY = fYOffset;

                placements.forEach(({ item, nodeId, relY }) => {
                    if (!nodeMap.has(nodeId)) {
                        nodeMap.set(nodeId, {
                            id: nodeId,
                            type: "factoryNode",
                            data: {
                                label: item.methodName,
                                summary: item.entityName,
                                payload: item.parameters,
                                dtos: facDtos,
                            },
                            position: { x: relNodeX, y: relY },
                            parentId: groupId,
                            style: {
                                background: FACTORY_BG,
                                color: "#fff",
                                border: `2px solid ${FACTORY_BORDER}`,
                                borderRadius: "8px",
                                padding: "10px",
                                fontSize: FONT_SIZE,
                                width: NODE_WIDTH,
                            },
                            sourcePosition: Position.Right,
                            targetPosition: Position.Left,
                        });
                    }
                });

                nodeMap.set(groupId, {
                    id: groupId,
                    type: "factoryGroupNode",
                    data: {
                        label: entityName,
                        fullName: entityName,
                        depth: 0,
                        minTopPadding: GROUP_TOP_PADDING,
                    },
                    position: { x: ACTOR_X - GROUP_PADDING, y: groupStartY },
                    width: groupWidth,
                    height: groupEndY - groupStartY,
                    style: { width: groupWidth, height: groupEndY - groupStartY },
                });

                fYOffset = groupEndY + CONTEXT_GAP;
            });

            const fGroups = Array.from(nodeMap.values())
                .filter((n) => n.id.startsWith("factory-group-"))
                .sort((a, b) => a.id.localeCompare(b.id));
            const fRest = Array.from(nodeMap.values())
                .filter((n) => !n.id.startsWith("factory-group-"));
            return { initialNodes: [...fGroups, ...fRest], initialEdges: edgeList };
        }

        // ── Entity graph layout (context groups → entity cards + relation edges) ──
        if (activeTab === "entities") {
            const entities = entitiesFromFlowData(flowData);
            if (entities.length === 0) return { initialNodes: [], initialEdges: [] };

            const entDtos = flowData.entityDtos ?? flowData.factoryDtos ?? flowData.dtos;
            const ENTITY_BG = "rgba(37,99,235,0.8)";
            const ENTITY_BORDER = "#1d4ed8";
            const ENTITY_WIDTH = Math.max(NODE_WIDTH_PX, 280);
            const ENTITY_COL_GAP = 100;
            const ENTITY_ROW_GAP = 36;
            const RELATION_COLORS: Record<string, string> = {
                ONE_TO_ONE: "#38bdf8",
                ONE_TO_MANY: "#a78bfa",
                MANY_TO_ONE: "#fbbf24",
                MANY_TO_MANY: "#f472b6",
            };

            const estMethodBlock = (m: EntityMethod, expanded: boolean): number => {
                let h = 28; // name + return
                const p = m.parameters as Record<string, unknown> | undefined;
                if (p && Object.keys(p).length > 0) {
                    h += 18;
                    if (expanded) {
                        const lines = countResolvedSchemaLines(p, entDtos);
                        h += 8 + Math.max(1, lines) * 16;
                    }
                }
                return h + 10; // padding/border
            };

            const estEntityHeight = (ent: EntityNodeData, nodeId: string): number => {
                let h = 52; // title + padding
                if (ent.context && ent.context !== "default") h += 16;
                const methodKey = (kind: string, m: EntityMethod) => {
                    const paramSig = Object.values(m.parameters ?? {}).join(",");
                    return paramSig
                        ? `${nodeId}::${kind}::${m.methodName}(${paramSig})`
                        : `${nodeId}::${kind}::${m.methodName}`;
                };
                if (ent.factories.length > 0) {
                    h += 20; // section label
                    for (const m of ent.factories) {
                        h += estMethodBlock(m, expandedSchemas.has(methodKey("factory", m))) + 4;
                    }
                }
                if (ent.domainMethods.length > 0) {
                    h += 20;
                    for (const m of ent.domainMethods) {
                        h += estMethodBlock(m, expandedSchemas.has(methodKey("domain", m))) + 4;
                    }
                }
                if (ent.factories.length === 0 && ent.domainMethods.length === 0) h += 24;
                return Math.max(h, 72);
            };

            // Group by bounded context
            const byContext = new Map<string, EntityNodeData[]>();
            for (const ent of entities) {
                const ctx = ent.context?.trim() || "default";
                if (!byContext.has(ctx)) byContext.set(ctx, []);
                byContext.get(ctx)!.push(ent);
            }

            // Topological-ish rank for left→right placement within a context:
            // aggregate parents leftmost; 1–1 extension children to the right.
            // Ranking edges (parent → child only):
            //  - extensionChild on owning side: parent = targetEntity
            //  - inverse mappedBy OneToOne/OneToMany: parent = declaring entity
            //  - owning OneToMany / non-extension OneToOne: parent → target
            //  - ManyToOne: always inverted → parent = targetEntity (one), child = declaring (many)
            //  - ManyToMany: skipped (reference, not containment)
            const rankInGroup = (group: EntityNodeData[]): Map<string, number> => {
                const names = new Set(group.map((e) => e.entityName));
                const outDeg = new Map<string, number>();
                const inDeg = new Map<string, number>();
                const childrenOf = new Map<string, Set<string>>();
                for (const e of group) {
                    outDeg.set(e.entityName, 0);
                    inDeg.set(e.entityName, 0);
                    childrenOf.set(e.entityName, new Set());
                }

                const addEdge = (parent: string, child: string) => {
                    if (!names.has(parent) || !names.has(child) || parent === child) return;
                    const set = childrenOf.get(parent)!;
                    if (set.has(child)) return;
                    set.add(child);
                    outDeg.set(parent, (outDeg.get(parent) ?? 0) + 1);
                    inDeg.set(child, (inDeg.get(child) ?? 0) + 1);
                };

                const isExtension = (r: EntityRelation): boolean => {
                    if (r.extensionChild === true) return true;
                    // Older payloads without extensionChild: read-only JoinColumn 1–1
                    return (
                        r.type === "ONE_TO_ONE" &&
                        r.owningSide !== false &&
                        !r.mappedBy &&
                        (r.insertable === false || r.updatable === false)
                    );
                };

                for (const e of group) {
                    for (const r of e.relations) {
                        if (!names.has(r.targetEntity)) continue;

                        if (isExtension(r) && r.owningSide !== false) {
                            // Child entity e extends parent r.targetEntity
                            addEdge(r.targetEntity, e.entityName);
                            continue;
                        }

                        // Always present ManyToOne as parent(one) → child(many)
                        if (r.type === "MANY_TO_ONE") {
                            addEdge(r.targetEntity, e.entityName);
                            continue;
                        }

                        if (r.owningSide === false) {
                            // Inverse side on parent: parent e → child r.targetEntity
                            if (r.type === "ONE_TO_ONE" || r.type === "ONE_TO_MANY") {
                                addEdge(e.entityName, r.targetEntity);
                            }
                            continue;
                        }

                        if (r.type === "MANY_TO_MANY") {
                            continue;
                        }
                        // Owning OneToMany / non-extension OneToOne
                        addEdge(e.entityName, r.targetEntity);
                    }
                }

                // Longest-path depth from roots (inDeg 0); roots column 0 (leftmost).
                const depth = new Map<string, number>();
                const roots = group
                    .map((e) => e.entityName)
                    .filter((n) => (inDeg.get(n) ?? 0) === 0);
                for (const n of roots) depth.set(n, 0);
                // If no pure roots (cycle / fully connected), seed all at 0 then refine
                if (roots.length === 0) {
                    for (const e of group) depth.set(e.entityName, 0);
                }

                // Relax parent → child: depth[child] = max(depth[child], depth[parent]+1)
                let changed = true;
                let guard = 0;
                while (changed && guard < group.length + 2) {
                    changed = false;
                    guard += 1;
                    for (const e of group) {
                        const p = e.entityName;
                        const pd = depth.get(p) ?? 0;
                        for (const c of childrenOf.get(p) ?? []) {
                            const want = pd + 1;
                            const cur = depth.get(c);
                            if (cur === undefined || want > cur) {
                                depth.set(c, want);
                                changed = true;
                            }
                        }
                    }
                }
                for (const e of group) {
                    if (!depth.has(e.entityName)) depth.set(e.entityName, 0);
                }

                // rank = depth (integer columns); slight bias so same-depth entities
                // with more outgoing edges sort first within the column group.
                const ranks = new Map<string, number>();
                for (const e of group) {
                    const d = depth.get(e.entityName) ?? 0;
                    const o = outDeg.get(e.entityName) ?? 0;
                    ranks.set(e.entityName, d - o * 0.001);
                }
                return ranks;
            };

            let globalY = 0;
            const entityAbsPos = new Map<string, { x: number; y: number; h: number }>();

            const ctxEntries = [...byContext.entries()].sort((a, b) =>
                a[0].localeCompare(b[0], undefined, { sensitivity: "base" }),
            );

            for (const [ctxName, group] of ctxEntries) {
                const groupStartY = globalY;
                const groupId = `entity-group-${ctxName}`;
                globalY += GROUP_TOP_PADDING;

                const ranks = rankInGroup(group);
                // Columns by rounded rank buckets
                const sorted = [...group].sort((a, b) => {
                    const ra = ranks.get(a.entityName) ?? 0;
                    const rb = ranks.get(b.entityName) ?? 0;
                    if (ra !== rb) return ra - rb;
                    return a.entityName.localeCompare(b.entityName, undefined, {
                        sensitivity: "base",
                    });
                });

                // Place in columns: 0..N by rank order (unique column per rank step)
                const colOf = new Map<string, number>();
                let col = 0;
                let lastRank: number | null = null;
                for (const e of sorted) {
                    const r = ranks.get(e.entityName) ?? 0;
                    if (lastRank !== null && r > lastRank + 0.001) col += 1;
                    colOf.set(e.entityName, col);
                    lastRank = r;
                }
                const maxCol = Math.max(0, ...colOf.values());

                // Stack Y within each column
                const colY = new Map<number, number>();
                for (let c = 0; c <= maxCol; c++) colY.set(c, globalY);

                const placements: {
                    ent: EntityNodeData;
                    nodeId: string;
                    absX: number;
                    absY: number;
                    h: number;
                }[] = [];

                for (const ent of sorted) {
                    const c = colOf.get(ent.entityName) ?? 0;
                    const nodeId = `entity-${ent.entityName}`;
                    const h = estEntityHeight(ent, nodeId);
                    const absX =
                        ACTOR_X - GROUP_PADDING + GROUP_PADDING + c * (ENTITY_WIDTH + ENTITY_COL_GAP);
                    const absY = colY.get(c) ?? globalY;
                    placements.push({ ent, nodeId, absX, absY, h });
                    entityAbsPos.set(ent.entityName, { x: absX, y: absY, h });
                    colY.set(c, absY + h + ENTITY_ROW_GAP);
                }

                const maxBottom = Math.max(
                    ...[...colY.values()],
                    globalY + GROUP_TOP_PADDING,
                );
                const groupEndY = maxBottom + GROUP_PADDING;
                const groupWidth =
                    GROUP_PADDING * 2 +
                    (maxCol + 1) * ENTITY_WIDTH +
                    maxCol * ENTITY_COL_GAP;
                const groupHeight = groupEndY - groupStartY;

                for (const { ent, nodeId, absX, absY, h } of placements) {
                    const expandedMethodKeys = [...expandedSchemas].filter((k) =>
                        k.startsWith(`${nodeId}::`),
                    );
                    nodeMap.set(nodeId, {
                        id: nodeId,
                        type: "entityNode",
                        data: {
                            label: ent.entityName,
                            context: ent.context,
                            factories: ent.factories,
                            domainMethods: ent.domainMethods,
                            dtos: entDtos,
                            expandedMethodKeys,
                        },
                        position: {
                            x: absX - (ACTOR_X - GROUP_PADDING),
                            y: absY - groupStartY,
                        },
                        parentId: groupId,
                        style: {
                            background: ENTITY_BG,
                            color: "#fff",
                            border: `2px solid ${ENTITY_BORDER}`,
                            borderRadius: "10px",
                            padding: "12px",
                            fontSize: FONT_SIZE,
                            width: ENTITY_WIDTH,
                            minHeight: h,
                        },
                        sourcePosition: Position.Right,
                        targetPosition: Position.Left,
                    });
                }

                nodeMap.set(groupId, {
                    id: groupId,
                    type: "entityGroupNode",
                    data: {
                        label: ctxName,
                        fullName: ctxName,
                        depth: 0,
                        minTopPadding: GROUP_TOP_PADDING,
                    },
                    position: { x: ACTOR_X - GROUP_PADDING, y: groupStartY },
                    width: groupWidth,
                    height: groupHeight,
                    style: { width: groupWidth, height: groupHeight },
                });

                globalY = groupEndY + CONTEXT_GAP;
            }

            // Relation edges:
            //  - extensionChild: keep natural direction (declaring child → parent); no "(ext)" label
            //  - ManyToOne: always reverse → display as OneToMany (parent one → child many)
            //  - prefer parent inverse mappedBy / OneToMany over child owning ManyToOne
            //  - skip duplicate undirected pairs
            const edgeKeys = new Set<string>();
            const nameToNode = (name: string) => `entity-${name}`;
            const entityNameSet = new Set(entities.map((e) => e.entityName));
            const entityByName = new Map(entities.map((e) => [e.entityName, e]));

            const isExtensionRel = (r: EntityRelation): boolean => {
                if (r.extensionChild === true) return true;
                return (
                    r.type === "ONE_TO_ONE" &&
                    r.owningSide !== false &&
                    !r.mappedBy &&
                    (r.insertable === false || r.updatable === false)
                );
            };

            const relationLabel = (fieldName: string, displayType: string): string => {
                const short = displayType.replace(/_/g, " ");
                return fieldName ? `${fieldName}: ${short}` : short;
            };

            for (const ent of entities) {
                for (const r of ent.relations as EntityRelation[]) {
                    if (!entityNameSet.has(r.targetEntity)) continue;

                    // Prefer reciprocal ONE_TO_MANY on the "one" side over inverted MANY_TO_ONE
                    // so the collection field name is used for the label.
                    if (r.type === "MANY_TO_ONE") {
                        const target = entityByName.get(r.targetEntity);
                        const hasOneToManyBack = target?.relations?.some(
                            (tr) =>
                                tr.targetEntity === ent.entityName &&
                                tr.type === "ONE_TO_MANY",
                        );
                        if (hasOneToManyBack) continue;
                    }

                    // Skip inverse when the child already declares extensionChild toward us
                    // (draw natural child → parent from that owning side instead).
                    // Also skip inverse when owning reciprocal exists and is not extension
                    // (except owning ManyToOne — those are inverted into OneToMany below,
                    // so the parent OneToMany inverse should still be drawn for field name).
                    if (!r.owningSide && r.mappedBy) {
                        const target = entityByName.get(r.targetEntity);
                        const childDeclaresExtension = target?.relations?.some(
                            (tr) =>
                                tr.targetEntity === ent.entityName &&
                                isExtensionRel(tr) &&
                                tr.owningSide !== false,
                        );
                        if (childDeclaresExtension) continue;

                        const hasOwningBack = target?.relations?.some(
                            (tr) =>
                                tr.targetEntity === ent.entityName &&
                                tr.owningSide &&
                                tr.type !== "MANY_TO_ONE" &&
                                !isExtensionRel(tr),
                        );
                        if (hasOwningBack) continue;
                    }

                    // Natural direction: declaring entity → targetEntity.
                    // ManyToOne is inverted and shown as OneToMany (parent → child).
                    // Extension edges are NOT reversed (child → parent).
                    let sourceName = ent.entityName;
                    let targetName = r.targetEntity;
                    let displayType = r.type;
                    let labelField = r.fieldName;
                    if (r.type === "MANY_TO_ONE") {
                        // many → one becomes one → many
                        sourceName = r.targetEntity;
                        targetName = ent.entityName;
                        displayType = "ONE_TO_MANY";
                    }

                    if (sourceName === targetName) continue;
                    const sourceId = nameToNode(sourceName);
                    const targetId = nameToNode(targetName);
                    // Dedup undirected pair + display type (ManyToOne collapses into OneToMany)
                    const pairKey = `${[sourceName, targetName].sort().join("|")}:${displayType}`;
                    if (edgeKeys.has(pairKey)) continue;
                    edgeKeys.add(pairKey);

                    const color = RELATION_COLORS[displayType] ?? "#94a3b8";
                    edgeList.push({
                        id: `rel-${sourceId}->${targetId}:${displayType}:${labelField}`,
                        source: sourceId,
                        target: targetId,
                        sourceHandle: "out",
                        targetHandle: "in",
                        type: "smoothstep",
                        animated: displayType === "MANY_TO_MANY",
                        label: relationLabel(labelField, displayType),
                        labelStyle: {
                            fill: color,
                            fontWeight: 700,
                            fontSize: 10,
                        },
                        labelBgStyle: {
                            fill: "#ffffff",
                            fillOpacity: 1,
                            stroke: color,
                            strokeWidth: 1.5,
                        },
                        labelBgPadding: [12, 6] as [number, number],
                        labelBgBorderRadius: 4,
                        style: { stroke: color, strokeWidth: 2 },
                        markerEnd: {
                            type: MarkerType.ArrowClosed,
                            color,
                            width: 16,
                            height: 16,
                        },
                    });
                }
            }

            const eGroups = Array.from(nodeMap.values())
                .filter((n) => n.id.startsWith("entity-group-"))
                .sort((a, b) => a.id.localeCompare(b.id));
            const eRest = Array.from(nodeMap.values()).filter(
                (n) => !n.id.startsWith("entity-group-"),
            );
            return { initialNodes: [...eGroups, ...eRest], initialEdges: edgeList };
        }

        // ── Command layout ──
        // Sort key
        const principalSortKey = (cmd: Command) => {
            const principals = resolveCommandPrincipals(cmd);
            if (principals.length === 0) return "";
            return [...principals]
                .sort((a, b) =>
                    a.localeCompare(b, undefined, { sensitivity: "base" }),
                )
                .join("\0");
        };

        // Build context tree from all command contexts
        const [contextRoots] = buildContextTree(flowData.commands);

        // Sort commands within each context node
        const sortCommands = (cmds: Command[]) => {
            cmds.sort((a, b) => {
                const byPrincipal = principalSortKey(a).localeCompare(
                    principalSortKey(b),
                    undefined,
                    { sensitivity: "base" },
                );
                if (byPrincipal !== 0) return byPrincipal;
                return a.from.command.localeCompare(b.from.command, undefined, {
                    sensitivity: "base",
                });
            });
        };

        const sortContextTree = (node: ContextNode) => {
            sortCommands(node.commands);
            node.children.forEach(sortContextTree);
        };
        contextRoots.forEach(sortContextTree);

        let yOffset = 0;

        /**
         * Lay out a single context node's direct commands + event nodes + actor nodes.
         * Returns the total vertical span (including GROUP_TOP_PADDING at top and
         * GROUP_PADDING at bottom) so the caller can size the group container.
         */
        const layoutContextCommands = (
            contextNode: ContextNode,
            groupId: string,
            groupStartY: number,
            depth: number,
        ): {
            groupHeight: number;
            maxActorOffset: number;
        } => {
            const commands = contextNode.commands;
            const contextName = contextNode.name;
            const indentX = depth * SUB_CONTEXT_INDENT;

            // Pass 1: compute placements
            const principalLinks = new Map<
                string,
                { commandId: string; relY: number }[]
            >();
            const cmdPlacements: {
                item: Command;
                commandId: string;
                commandRelY: number;
            }[] = [];

            let innerY = groupStartY + GROUP_TOP_PADDING;

            commands.forEach((item) => {
                const commandId = `command-${item.from.command}`;
                const eventCount = item.to.length;
                const { step: evStep } = resolveEventHeights(item, dtos, expandedSchemas);
                const totalEventHeight =
                    Math.max(0, eventCount - 1) * evStep;
                const commandY = innerY + totalEventHeight / 2;
                const commandRelY = commandY - groupStartY;

                cmdPlacements.push({ item, commandId, commandRelY });

                const principals = resolveCommandPrincipals(item);
                principals.forEach((principal) => {
                    if (!principalLinks.has(principal))
                        principalLinks.set(principal, []);
                    principalLinks.get(principal)!.push({
                        commandId,
                        relY: commandRelY,
                    });
                });

                innerY += estimateCommandBlockHeight(item, dtos, expandedSchemas);
            });

            const directContentEndY = innerY;

            // Actor offset calculation
            let maxActorOffset = 0;
            principalLinks.forEach((links) => {
                if (links.length <= 1) return;
                const maxSpread =
                    ((links.length - 1) / 2) * ACTOR_EDGE_SPREAD_STEP;
                const ys = links.map((l) => l.relY);
                const verticalSpread = Math.max(...ys) - Math.min(...ys);
                const verticalPad = Math.min(80, verticalSpread * 0.08);
                const requiredGap =
                    2 * (maxSpread + ACTOR_EDGE_MARGIN) + verticalPad;
                const offset = Math.max(0, requiredGap - BASE_ACTOR_COMMAND_GAP);
                maxActorOffset = Math.max(maxActorOffset, offset);
            });

            const relActorX = GROUP_PADDING + indentX;
            const relCommandX =
                COMMAND_X - ACTOR_X + GROUP_PADDING + maxActorOffset + indentX;
            const relEventX =
                EVENT_X - ACTOR_X + GROUP_PADDING + maxActorOffset + indentX;

            // Pass 2: create command + event nodes + edges
            cmdPlacements.forEach(({ item, commandId, commandRelY }) => {
                const eventCount = item.to.length;

                if (!nodeMap.has(commandId)) {
                    nodeMap.set(commandId, {
                        id: commandId,
                        type: "labelNode",
                        data: {
                            label: item.from.command,
                            summary:
                                item.summary?.trim() || undefined,
                            httpMethod: item.httpMethod,
                            path: item.path,
                            payload: item.from.payload,
                            dtos,
                            involvedEntities: item.involvedEntities,
                            onAddEvent: () =>
                                setAddEvtCmd(item.from.command),
                            onEditCommand: () =>
                                setEditingCmd(item.from.command),
                        },
                        position: {
                            x: relCommandX,
                            y: commandRelY,
                        },
                        parentId: groupId,
                        style: {
                            background: "rgba(37,99,235,0.8)",
                            color: "#fff",
                            border: "2px solid #1d4ed8",
                            borderRadius: "8px",
                            padding: "10px",
                            fontSize: FONT_SIZE,
                            width: NODE_WIDTH,
                        },
                        sourcePosition: Position.Right,
                        targetPosition: Position.Left,
                    });
                }

                const { step: eventStep } = resolveEventHeights(
                    item,
                    dtos,
                    expandedSchemas,
                );
                const cmdBaseY = groupStartY + GROUP_TOP_PADDING;
                // find the absolute Y of this command's block
                let absCommandBlockY = cmdBaseY;
                for (const p of cmdPlacements) {
                    if (p.commandId === commandId) break;
                    absCommandBlockY += estimateCommandBlockHeight(p.item, dtos, expandedSchemas);
                }
                let eventYAccum = 0;
                item.to.forEach(
                    (ep: EventPayload, eventIndex: number) => {
                        const eventY = absCommandBlockY + eventYAccum;
                        eventYAccum += eventStep;
                        const eventId = `event-${ep.event}`;

                        if (!nodeMap.has(eventId)) {
                            nodeMap.set(eventId, {
                                id: eventId,
                                type: "labelNode",
                                data: {
                                    label: ep.event,
                                    payload: ep.payload,
                                    dtos,
                                    onEditEvent: () =>
                                        setEditingEvt(
                                            ep.event,
                                        ),
                                },
                                position: {
                                    x: relEventX,
                                    y: eventY - groupStartY,
                                },
                                parentId: groupId,
                                style: {
                                    background:
                                        "rgba(249,115,22,0.8)",
                                    color: "#fff",
                                    border: "2px solid #ea580c",
                                    borderRadius: "8px",
                                    padding: "10px",
                                    fontSize: FONT_SIZE,
                                    width: NODE_WIDTH,
                                },
                                sourcePosition: Position.Right,
                                targetPosition: Position.Left,
                            });
                        }

                        edgeList.push({
                            id: `${commandId}-${eventId}`,
                            source: commandId,
                            target: eventId,
                            type: "spreadStep",
                            data: {
                                spread:
                                    (eventIndex -
                                        (eventCount - 1) / 2) *
                                    25,
                            },
                            animated: true,
                            style: {
                                stroke: "#64748b",
                                strokeWidth: 2,
                            },
                            markerEnd: {
                                type: MarkerType.ArrowClosed,
                                color: "#64748b",
                            },
                        });
                    },
                );
            });

            // Actor nodes — place each at the average Y of its commands,
            // then de-collide so multiple principals on the same command
            // (e.g. Registered + Unregistered Customer) do not stack.
            // Overlapping clusters are fanned vertically and re-centered
            // on their shared ideal Y so they stay aligned with the command.
            type ActorPlacement = {
                principal: string;
                links: { commandId: string; relY: number }[];
                idealRelY: number;
                relY: number;
            };
            const actorPlacements: ActorPlacement[] = [];
            principalLinks.forEach((links, principal) => {
                const idealRelY =
                    links.reduce((sum, link) => sum + link.relY, 0) /
                    links.length;
                actorPlacements.push({
                    principal,
                    links,
                    idealRelY,
                    relY: idealRelY,
                });
            });

            // Stable order: ideal Y, then name — so de-collision is deterministic.
            actorPlacements.sort((a, b) => {
                if (a.idealRelY !== b.idealRelY)
                    return a.idealRelY - b.idealRelY;
                return a.principal.localeCompare(b.principal);
            });

            const minActorStep = EST_ACTOR_NODE_HEIGHT + ACTOR_STACK_GAP;
            // Cluster actors whose ideal positions would overlap (same command
            // or very close commands), fan them, and keep the cluster centered.
            let clusterStart = 0;
            while (clusterStart < actorPlacements.length) {
                let clusterEnd = clusterStart + 1;
                while (
                    clusterEnd < actorPlacements.length &&
                    actorPlacements[clusterEnd].idealRelY -
                        actorPlacements[clusterEnd - 1].idealRelY <
                        minActorStep
                ) {
                    clusterEnd++;
                }
                const cluster = actorPlacements.slice(
                    clusterStart,
                    clusterEnd,
                );
                if (cluster.length === 1) {
                    clusterStart = clusterEnd;
                    continue;
                }
                const centerY =
                    cluster.reduce((s, p) => s + p.idealRelY, 0) /
                    cluster.length;
                const span = (cluster.length - 1) * minActorStep;
                cluster.forEach((p, i) => {
                    p.relY = centerY - span / 2 + i * minActorStep;
                });
                clusterStart = clusterEnd;
            }

            // Final sweep: if adjacent clusters still collide after fanning,
            // push later ones down so nothing overlaps. Also keep the first
            // actor below the floating context badge inset.
            const minActorY = GROUP_TOP_PADDING * 0.35;
            if (actorPlacements.length > 0 && actorPlacements[0].relY < minActorY) {
                const shift = minActorY - actorPlacements[0].relY;
                actorPlacements.forEach((p) => {
                    p.relY += shift;
                });
            }
            for (let i = 1; i < actorPlacements.length; i++) {
                const prev = actorPlacements[i - 1];
                const curr = actorPlacements[i];
                const minY = prev.relY + minActorStep;
                if (curr.relY < minY) curr.relY = minY;
            }

            actorPlacements.forEach(({ principal, links, relY }) => {
                const actorId = `actor-${contextName}-${principal}`;

                if (!nodeMap.has(actorId)) {
                    nodeMap.set(actorId, {
                        id: actorId,
                        type: "labelNode",
                        data: { label: principal, icon: "person" },
                        position: { x: relActorX, y: relY },
                        parentId: groupId,
                        style: {
                            background:
                                "rgba(250,204,21,0.9)",
                            color: "#1c1917",
                            border: "2px solid #ca8a04",
                            borderRadius: "8px",
                            padding: "10px",
                            fontSize: FONT_SIZE,
                            width: ACTOR_NODE_WIDTH,
                            fontWeight: 700,
                        },
                        sourcePosition: Position.Right,
                        targetPosition: Position.Left,
                    });
                }

                links.forEach((link, linkIdx) => {
                    const edgeId = `${actorId}-${link.commandId}`;
                    if (edgeList.find((e) => e.id === edgeId))
                        return;
                    edgeList.push({
                        id: edgeId,
                        source: actorId,
                        target: link.commandId,
                        type: "spreadStep",
                        data: {
                            spread:
                                (linkIdx -
                                    (links.length - 1) / 2) *
                                ACTOR_EDGE_SPREAD_STEP,
                        },
                        animated: false,
                        style: {
                            stroke: ACTOR_EDGE_COLOR,
                            strokeWidth: 2,
                        },
                        markerEnd: {
                            type: MarkerType.ArrowClosed,
                            color: ACTOR_EDGE_COLOR,
                        },
                    });
                });
            });

            // Include stacked actors in group height so fanned multi-actor
            // clusters are not clipped at the bottom of the context group.
            let actorBottom = 0;
            actorPlacements.forEach((p) => {
                actorBottom = Math.max(
                    actorBottom,
                    p.relY + EST_ACTOR_NODE_HEIGHT,
                );
            });
            const contentBottom = directContentEndY - groupStartY;
            const directHeight =
                Math.max(contentBottom, actorBottom) + GROUP_PADDING;

            return { groupHeight: directHeight, maxActorOffset };
        };

        /**
         * Recursively lay out a context node and its sub-contexts.
         * Returns { height, width } so parents can enclose children.
         *
         * @param parentStartY — the parent group's groupStartY (for computing
         *   relative y of sub-groups).  Pass the SAME value as this node's own
         *   groupStartY when calling for sub-contexts.
         */
        const layoutContextNode = (
            contextNode: ContextNode,
            parentGroupId: string | null,
            parentStartY: number,
        ): { height: number; width: number } => {
            const contextName = contextNode.name;
            const depth = contextNode.depth;
            const groupId = `group-${contextName}`;
            const groupStartY = yOffset;
            const isSub = parentGroupId !== null;

            // Lay out this context's direct commands
            const { groupHeight: directHeight, maxActorOffset } =
                layoutContextCommands(contextNode, groupId, groupStartY, depth);

            let groupEndY = groupStartY + directHeight;
            let maxChildWidth = 0;
            const indentX = depth * SUB_CONTEXT_INDENT;

            // Recursively lay out sub-contexts below the direct commands
            if (contextNode.children.length > 0) {
                // If this node has no direct commands, still leave badge clearance
                // before the first sub-group.
                if (contextNode.commands.length === 0) {
                    groupEndY = groupStartY + GROUP_TOP_PADDING;
                }
                yOffset = groupEndY;

                contextNode.children.forEach((childNode) => {
                    yOffset += SUB_CONTEXT_GAP;
                    const childResult = layoutContextNode(childNode, groupId, groupStartY);
                    maxChildWidth = Math.max(maxChildWidth, childResult.width);
                });

                groupEndY = yOffset + GROUP_PADDING;
            }

            const groupHeight = groupEndY - groupStartY;
            const ownWidth =
                EVENT_X +
                NODE_WIDTH_PX -
                ACTOR_X +
                2 * GROUP_PADDING +
                maxActorOffset +
                indentX;
            // Parent must be wide enough for indented sub-groups (+ their own padding).
            const groupWidth = Math.max(
                ownWidth,
                maxChildWidth > 0
                    ? maxChildWidth + GROUP_PADDING * 2
                    : 0,
            );

            // Group container node.
            // Top-level groups use absolute canvas position.
            // Sub-groups use position RELATIVE to their parent (React Flow
            // interprets position as relative when parentId is set).
            const posX = isSub
                ? GROUP_PADDING
                : ACTOR_X - GROUP_PADDING - maxActorOffset;
            const posY = isSub
                ? groupStartY - parentStartY
                : groupStartY;

            nodeMap.set(groupId, {
                id: groupId,
                type: "groupNode",
                data: {
                    label: contextNode.label,
                    fullName: contextName,
                    depth,
                    minTopPadding: GROUP_TOP_PADDING,
                    onAddCommand: () =>
                        setAddCmdCtx(contextName),
                    onEditContext: () =>
                        setEditingCtx(contextName),
                },
                position: { x: posX, y: posY },
                width: groupWidth,
                height: groupHeight,
                parentId: parentGroupId ?? undefined,
                extent: isSub ? "parent" as const : undefined,
                zIndex: depth * 10,
                selectable: true,
                focusable: true,
                draggable: true,
                style: {
                    width: groupWidth,
                    height: groupHeight,
                },
            });

            yOffset = groupEndY + (depth === 0 ? CONTEXT_GAP : 0);
            return { height: groupHeight, width: groupWidth };
        };

        // Lay out all top-level contexts
        contextRoots.forEach((rootNode) => {
            layoutContextNode(rootNode, null, 0);
        });

        // ── Policy layout ──
        const POLICY_EDGE_COLOR =
            (typeof document !== "undefined"
                ? getComputedStyle(document.documentElement)
                      .getPropertyValue("--policy-border")
                      .trim()
                : "") || "#8b6fa3";

        const policyWidth = parseInt(POLICY_NODE_WIDTH, 10);
        const POLICY_COLUMN_GAP = 60;

        const estimatePolicyHeight = (data: PolicyData): number => {
            const headerHeight = 150;
            const flowHeights = data.flows.map((f) => {
                const chars = f.invariant.length;
                const lines = Math.max(1, Math.ceil(chars / 55));
                return Math.max(140, lines * 28 + 120);
            });
            return (
                headerHeight +
                flowHeights.reduce((s, h) => s + h, 0)
            );
        };

        const policyEntries = Object.entries(
            flowData.policies,
        ).map(([name, data]) => ({
            name,
            data,
            estimatedHeight: estimatePolicyHeight(data),
        }));

        let numColumns = 1;
        if (policyEntries.length > 3) numColumns = 2;
        if (policyEntries.length > 8) numColumns = 3;

        const columns: {
            entries: typeof policyEntries;
            colHeight: number;
        }[] = Array.from({ length: numColumns }, () => ({
            entries: [],
            colHeight: 0,
        }));
        policyEntries.forEach((entry) => {
            let shortest = 0;
            for (let i = 1; i < columns.length; i++) {
                if (
                    columns[i].colHeight <
                    columns[shortest].colHeight
                )
                    shortest = i;
            }
            columns[shortest].entries.push(entry);
            columns[shortest].colHeight +=
                entry.estimatedHeight + VERTICAL_SPACING;
        });

        let maxPolicyFlows = 0;
        policyEntries.forEach((p) => {
            maxPolicyFlows = Math.max(
                maxPolicyFlows,
                p.data.flows.length,
            );
        });
        const POLICY_X_BASE =
            POLICY_X + Math.max(0, (maxPolicyFlows - 6) * 55);

        columns.forEach((col, colIdx) => {
            let colY = 0;
            const colX =
                POLICY_X_BASE +
                colIdx * (policyWidth + POLICY_COLUMN_GAP);

            col.entries.forEach(({ name, data }) => {
                const policyId = `policy-${name}`;
                nodeMap.set(policyId, {
                    id: policyId,
                    type: "policyNode",
                    data: {
                        label: name,
                        flows: data.flows,
                        onUpdateFlows: (
                            newFlows: PolicyFlow[],
                        ) => {
                            setFlowData((prev) => {
                                const next =
                                    structuredClone(prev);
                                next.policies[name].flows =
                                    newFlows;
                                return next;
                            });
                        },
                    },
                    position: { x: colX, y: colY },
                    style: {},
                    sourcePosition: Position.Right,
                    targetPosition: Position.Left,
                });

                colY +=
                    estimatePolicyHeight(data) +
                    VERTICAL_SPACING;

                const sortedFlows = sortPolicyFlows(data.flows);

                const incomingFlows = sortedFlows.filter(
                    (f) => f.fromEvent !== null,
                );
                const outgoingFlows = sortedFlows.filter(
                    (f) => f.toCommand !== null,
                );

                sortedFlows.forEach((flow, flowIdx) => {
                    if (
                        flow.fromEvent === null &&
                        flow.toCommand === null
                    )
                        return;

                    if (flow.fromEvent !== null) {
                        const targetIdx =
                            incomingFlows.indexOf(flow);
                        const eventId = `event-${flow.fromEvent}`;

                        if (!nodeMap.has(eventId)) {
                            nodeMap.set(eventId, {
                                id: eventId,
                                type: "labelNode",
                                data: {
                                    label: flow.fromEvent,
                                    payload:
                                        flowData.schema?.[
                                            flow.fromEvent
                                        ],
                                    dtos,
                                    onEditEvent: () =>
                                        setEditingEvt(
                                            flow.fromEvent,
                                        ),
                                },
                                position: {
                                    x: EVENT_X,
                                    y: yOffset,
                                },
                                style: {
                                    background:
                                        "rgba(249,115,22,0.8)",
                                    color: "#fff",
                                    border: "2px solid #ea580c",
                                    borderRadius: "8px",
                                    padding: "10px",
                                    fontSize: FONT_SIZE,
                                    width: NODE_WIDTH,
                                },
                                sourcePosition:
                                    Position.Right,
                                targetPosition:
                                    Position.Left,
                            });
                            yOffset += VERTICAL_SPACING;
                        }

                        const ep = `${eventId}-${policyId}-t${flowIdx}`;
                        if (
                            !edgeList.find(
                                (e) => e.id === ep,
                            )
                        ) {
                            edgeList.push({
                                id: ep,
                                source: eventId,
                                target: policyId,
                                targetHandle: `t-${flowIdx}`,
                                type: "spreadStep",
                                data: {
                                    spread:
                                        (targetIdx -
                                            (incomingFlows.length -
                                                1) /
                                                2) *
                                        25,
                                },
                                animated: true,
                                style: {
                                    stroke: POLICY_EDGE_COLOR,
                                    strokeWidth: 2,
                                },
                                markerEnd: {
                                    type: MarkerType.ArrowClosed,
                                    color: POLICY_EDGE_COLOR,
                                },
                            });
                        }
                    }

                    if (flow.toCommand !== null) {
                        const commandId = `command-${flow.toCommand}`;
                        const sourceIdx =
                            outgoingFlows.indexOf(flow);
                        if (nodeMap.has(commandId)) {
                            const pc = `${policyId}-${commandId}-s${flowIdx}`;
                            if (
                                !edgeList.find(
                                    (e) => e.id === pc,
                                )
                            ) {
                                edgeList.push({
                                    id: pc,
                                    source: policyId,
                                    target: commandId,
                                    sourceHandle: `s-${flowIdx}`,
                                    type: "spreadStep",
                                    data: {
                                        spread:
                                            (sourceIdx -
                                                (outgoingFlows.length -
                                                    1) /
                                                    2) *
                                            25,
                                    },
                                    animated: true,
                                    style: {
                                        stroke: POLICY_EDGE_COLOR,
                                        strokeWidth: 2,
                                    },
                                    markerEnd: {
                                        type: MarkerType.ArrowClosed,
                                        color: POLICY_EDGE_COLOR,
                                    },
                                });
                            }
                        }
                    }
                });
            });
        });

        const allNodes = Array.from(nodeMap.values());
        // React Flow requires parents before children in the nodes array.
        // layoutContextNode inserts sub-groups into the map before their
        // parents, so we must re-order: groups by depth ascending, then
        // non-group nodes (all of whose parents are already present).
        const groups = allNodes
            .filter((n) => n.id.startsWith("group-"))
            .sort((a, b) => {
                const depthA =
                    (a.data as { depth?: number }).depth ?? 0;
                const depthB =
                    (b.data as { depth?: number }).depth ?? 0;
                if (depthA !== depthB) return depthA - depthB;
                return a.id.localeCompare(b.id);
            });
        const rest = allNodes.filter(
            (n) => !n.id.startsWith("group-"),
        );
        return {
            initialNodes: [...groups, ...rest],
            initialEdges: edgeList,
        };
    }, [flowData, expandedSchemas, activeTab, setFlowData, setAddCmdCtx, setAddEvtCmd, setEditingCmd, setEditingCtx, setEditingEvt]);
}
