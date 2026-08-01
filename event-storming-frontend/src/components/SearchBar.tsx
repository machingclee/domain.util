import { useState, useMemo, useCallback, useRef, useEffect } from "react";
import Markdown from "react-markdown";
import type { FlowData } from "../types";
import { entitiesFromFlowData, flattenInvolvedEntities, resolveCommandPrincipals } from "../types";

const DROPDOWN_WIDTH = 520;

/** Build a case-insensitive regex from a space-separated query.
 *  "ABC Command" → /ABC.*Command/i so you can type loosely and still filter. */
function buildTokenRegex(q: string): RegExp | null {
    const tokens = q.trim().split(/\s+/).filter(Boolean);
    if (tokens.length === 0) return null;
    // Escape each token then join with .*
    const escaped = tokens.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
    return new RegExp(escaped.join(".*"), "i");
}

function highlightText(text: string, query: string): React.ReactNode {
    if (!query) return text;
    // Split into tokens so "b ooking Policy" highlights "b", "ooking", "Policy" independently
    const tokens = query.trim().split(/\s+/).filter(Boolean);
    // Collect every match interval across all tokens
    const intervals: [number, number][] = [];
    const lower = text.toLowerCase();
    for (const token of tokens) {
        const tl = token.toLowerCase();
        let pos = 0;
        while (pos < lower.length) {
            const idx = lower.indexOf(tl, pos);
            if (idx === -1) break;
            intervals.push([idx, idx + tl.length]);
            pos = idx + 1; // allow overlapping matches
        }
    }
    if (intervals.length === 0) return text;
    // Merge overlapping / adjacent intervals
    intervals.sort((a, b) => a[0] - b[0]);
    const merged: [number, number][] = [intervals[0]];
    for (let i = 1; i < intervals.length; i++) {
        const prev = merged[merged.length - 1];
        const cur = intervals[i];
        if (cur[0] <= prev[1]) {
            prev[1] = Math.max(prev[1], cur[1]);
        } else {
            merged.push(cur);
        }
    }
    // Build highlighted result
    const parts: React.ReactNode[] = [];
    let last = 0;
    for (const [start, end] of merged) {
        if (start > last) parts.push(text.slice(last, start));
        parts.push(
            <mark key={start} style={{ background: "#c7d2fe", color: "#3730a3", borderRadius: 2, padding: "0 2px" }}>
                {text.slice(start, end)}
            </mark>,
        );
        last = end;
    }
    if (last < text.length) parts.push(text.slice(last));
    return <>{parts}</>;
}

const DbIcon = () => (
    <svg viewBox="0 0 24 24" width="10" height="10" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ flexShrink: 0, opacity: 0.5 }}>
        <ellipse cx="12" cy="5" rx="9" ry="3" />
        <path d="M3 5v14c0 1.66 4.03 3 9 3s9-1.34 9-3V5" />
        <path d="M3 12c0 1.66 4.03 3 9 3s9-1.34 9-3" />
    </svg>
);

type FilterKind = "commands" | "events" | "policies" | "queries" | "factories" | "paths" | "entities" | "invariants" | "contexts" | "actors";

const FILTERS: { key: FilterKind; label: string }[] = [
    { key: "commands", label: "Commands" },
    { key: "events", label: "Events" },
    { key: "policies", label: "Policies" },
    { key: "queries", label: "Queries" },
    { key: "entities", label: "Entities" },
    { key: "factories", label: "Factories" },
    { key: "paths", label: "Paths (HTTP)" },
    { key: "invariants", label: "Invariants (business rules)" },
    { key: "contexts", label: "Contexts" },
    { key: "actors", label: "Actors" },
];

interface SearchItem {
    label: string;
    sub?: string;
    trigger?: string;
    entities?: string[];
    nodeId: string;
    kind: "command" | "event" | "policy" | "context" | "query" | "factory" | "entity";
    /** Raw path for /paths filter */
    path?: string;
    /** Raw invariants for /invariants filter */
    invariantText?: string;
    /** Actor names for /actors filter */
    actors?: string[];
}

const kindColor: Record<SearchItem["kind"], string> = {
    command: "#3b82f6",
    event: "#f97316",
    policy: "#a78bfa",
    context: "#0d9488",
    query: "#06b6d4",
    factory: "#10b981",
    entity: "#14b8a6",
};

export default function SearchBar({ flowData, onNavigate }: { flowData: FlowData; onNavigate: (nodeId: string, kind: string) => void }) {
    const [query, setQuery] = useState("");
    const [debouncedQuery, setDebouncedQuery] = useState("");
    const [open, setOpen] = useState(false);
    const debounceRef = useRef(0);
    const [selectedIdx, setSelectedIdx] = useState(0);
    const inputRef = useRef<HTMLInputElement>(null);
    const listRef = useRef<HTMLDivElement>(null);

    // Ctrl+P / Cmd+P to focus search
    useEffect(() => {
        const onKeyDown = (e: KeyboardEvent) => {
            if ((e.ctrlKey || e.metaKey) && e.key === "p") {
                e.preventDefault();
                inputRef.current?.focus();
                inputRef.current?.select();
            }
        };
        window.addEventListener("keydown", onKeyDown);
        return () => window.removeEventListener("keydown", onKeyDown);
    }, []);

    const items = useMemo((): SearchItem[] => {
        const out: SearchItem[] = [];
        for (const cmd of flowData.commands) {
            const entities = flattenInvolvedEntities(cmd.involvedEntities);
            const http = cmd.httpMethod && cmd.path ? `${cmd.httpMethod} ${cmd.path}` : undefined;
            const entityList = entities.length > 0 ? entities : undefined;
            const actorList = resolveCommandPrincipals(cmd);
            out.push({
                label: cmd.from.command,
                sub: http,
                entities: entityList,
                actors: actorList.length > 0 ? actorList : undefined,
                nodeId: `command-${cmd.from.command}`,
                kind: "command",
                path: cmd.path,
            });
            for (const ev of cmd.to) {
                if (!out.find((i) => i.nodeId === `event-${ev.event}`)) {
                    out.push({ label: ev.event, nodeId: `event-${ev.event}`, kind: "event" });
                }
            }
        }
        for (const q of (flowData.queries ?? [])) {
            const http = q.httpMethod && q.path ? `${q.httpMethod} ${q.path}` : undefined;
            out.push({
                label: q.from.query,
                sub: http,
                nodeId: `query-${q.from.query}`,
                kind: "query",
                path: q.path,
            });
        }
        for (const ent of entitiesFromFlowData(flowData)) {
            out.push({
                label: ent.entityName,
                sub: ent.context || "default",
                nodeId: `entity-${ent.entityName}`,
                kind: "entity",
            });
            for (const f of ent.factories) {
                out.push({
                    label: `${ent.entityName}.${f.methodName}`,
                    sub: ent.entityName,
                    nodeId: `entity-${ent.entityName}`,
                    kind: "factory",
                });
            }
        }
        // Legacy flat factories (when entities absent)
        if (!(flowData.entities && flowData.entities.length > 0)) {
            for (const f of (flowData.factories ?? [])) {
                const paramSig = Object.values(f.parameters).join(",");
                const nodeId = paramSig
                    ? `factory-${f.entityName}-${f.methodName}(${paramSig})`
                    : `factory-${f.entityName}-${f.methodName}`;
                out.push({
                    label: `${f.entityName}.${f.methodName}`,
                    sub: f.entityName,
                    nodeId,
                    kind: "factory",
                });
            }
        }
        for (const polName of Object.keys(flowData.policies)) {
            const pol = flowData.policies[polName];
            out.push({ label: polName, nodeId: `policy-${polName}`, kind: "policy" });
            // Each invariant as a separate search item
            for (const flow of pol.flows) {
                if (!flow.invariant.trim()) continue;
                const preview = flow.invariant.length > 80 ? flow.invariant.slice(0, 80) + "…" : flow.invariant;
                const parts: string[] = [];
                if (flow.fromEvent) { parts.push(flow.fromEvent); parts.push("→"); }
                parts.push(polName);
                if (flow.toCommand) { parts.push("→"); parts.push(flow.toCommand); }
                const trigger = parts.join(" ");
                out.push({
                    label: preview,
                    sub: polName,
                    trigger,
                    nodeId: `policy-${polName}`,
                    kind: "policy",
                    invariantText: flow.invariant,
                });
            }
        }
        const ctxSet = new Set(flowData.commands.map((c) => c.context).filter(Boolean));
        for (const ctx of ctxSet) {
            out.push({ label: ctx, nodeId: `group-${ctx}`, kind: "context" });
        }
        return out;
    }, [flowData]);

    // Parse slash-command: /filter rest-of-query
    const { filterKey, searchTerm, showFilters, filterPartial } = useMemo(() => {
        if (!debouncedQuery.startsWith("/")) return { filterKey: null, searchTerm: debouncedQuery, showFilters: false, filterPartial: "" };
        const space = debouncedQuery.indexOf(" ");
        if (space === -1) {
            const partial = debouncedQuery.slice(1).toLowerCase();
            return { filterKey: null, searchTerm: debouncedQuery, showFilters: true, filterPartial: partial };
        }
        const key = debouncedQuery.slice(1, space).toLowerCase() as FilterKind;
        const term = debouncedQuery.slice(space + 1);
        if (term.startsWith("/")) {
            return { filterKey: key, searchTerm: term, showFilters: true, filterPartial: term.slice(1).toLowerCase() };
        }
        return { filterKey: key, searchTerm: term, showFilters: false, filterPartial: "" };
    }, [debouncedQuery]);

    const matchingFilters = useMemo(() => {
        if (!showFilters) return [];
        if (!filterPartial) return FILTERS;
        return FILTERS.filter((f) => f.key.includes(filterPartial) || f.label.toLowerCase().includes(filterPartial));
    }, [filterPartial, showFilters]);

    // For /actors with no search term: show distinct actor names.
    const actorSuggestions = useMemo(() => {
        if (filterKey !== "actors" || searchTerm) return null;
        const allActors = new Set<string>();
        for (const it of items) {
            if (it.actors) for (const a of it.actors) allActors.add(a);
        }
        return [...allActors].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: "base" }));
    }, [items, filterKey, searchTerm]);

    const filtered = useMemo(() => {
        if (!debouncedQuery.trim() || (filterKey === "actors" && !searchTerm)) return [];
        const q = searchTerm.toLowerCase();

        let pool = items;
        if (filterKey) {
            switch (filterKey) {
                case "commands":
                    pool = items.filter((it) => it.kind === "command" || it.kind === "query");
                    break;
                case "events":
                    pool = items.filter((it) => it.kind === "event");
                    break;
                case "policies":
                    pool = items.filter((it) => it.kind === "policy");
                    break;
                case "queries":
                    pool = items.filter((it) => it.kind === "query");
                    break;
                case "factories":
                    pool = items.filter((it) => it.kind === "factory");
                    break;
                case "contexts":
                    pool = items.filter((it) => it.kind === "context");
                    break;
                case "paths":
                    pool = items.filter((it) => (it.kind === "command" || it.kind === "query") && it.path);
                    break;
                case "entities":
                    pool = items.filter(
                        (it) =>
                            it.kind === "entity"
                            || (it.kind === "command" && it.entities && it.entities.length > 0),
                    );
                    break;
                case "invariants":
                    pool = items.filter((it) => it.invariantText);
                    break;
                case "actors":
                    pool = items.filter((it) => it.kind === "command" && it.actors && it.actors.length > 0);
                    break;
                default:
                    return [];
            }
        }

        const sorted = pool.sort((a, b) =>
            a.label.localeCompare(b.label, undefined, { sensitivity: "base" }),
        );

        if (!q) return sorted;

        // Build a token regex so "ABC Command" matches "ABC.*Command"
        const tokenRe = buildTokenRegex(q);
        const matches = (text: string) => tokenRe ? tokenRe.test(text) : false;
        const anyMatches = (texts: string[]) => texts.some(matches);

        return sorted
            .filter((it) => {
                if (matches(it.label) || it.label.toLowerCase().includes(q)) return true;
                if (matches(it.sub ?? "") || (it.sub ?? "").toLowerCase().includes(q)) return true;
                if ((it.kind === "command" || it.kind === "query") && filterKey === "paths" && (matches(it.path ?? "") || (it.path ?? "").toLowerCase().includes(q))) return true;
                if (it.kind === "command" && filterKey === "entities" && (anyMatches(it.entities ?? []) || (it.entities ?? []).some((e) => e.toLowerCase().includes(q)))) return true;
                if (filterKey === "invariants" && (matches((it.invariantText ?? "") + (it.trigger ?? "")) || ((it.invariantText ?? "") + (it.trigger ?? "")).toLowerCase().includes(q))) return true;
                if (filterKey === "actors" && (anyMatches(it.actors ?? []) || (it.actors ?? []).some((a) => a.toLowerCase().includes(q)))) return true;
                if (!filterKey) {
                    if (matches(it.sub ?? "") || (it.sub ?? "").toLowerCase().includes(q)) return true;
                    if ((it.kind === "command" || it.kind === "query") && (matches(it.path ?? "") || (it.path ?? "").toLowerCase().includes(q))) return true;
                    if (anyMatches(it.entities ?? []) || (it.entities ?? []).some((e) => e.toLowerCase().includes(q))) return true;
                    if (matches(it.invariantText ?? "") || (it.invariantText ?? "").toLowerCase().includes(q)) return true;
                    if (anyMatches(it.actors ?? []) || (it.actors ?? []).some((a) => a.toLowerCase().includes(q))) return true;
                }
                return false;
            })
            .slice(0, 12);
    }, [items, debouncedQuery, filterKey, searchTerm, showFilters]);

    useEffect(() => { setSelectedIdx(0); }, [debouncedQuery]);

    const onChange = (value: string) => {
        setQuery(value);
        window.clearTimeout(debounceRef.current);
        debounceRef.current = window.setTimeout(() => setDebouncedQuery(value), 300);
    };

    const navigate = useCallback(
        (item: SearchItem) => {
            onNavigate(item.nodeId, item.kind);
            setOpen(false);
            setQuery("");
        },
        [onNavigate],
    );

    const onKeyDown = (e: React.KeyboardEvent) => {
        const list = actorSuggestions ? actorSuggestions : showFilters ? matchingFilters : filtered;
        const listLen = Array.isArray(list) ? list.length : 0;
        if (e.key === "Escape") { setOpen(false); inputRef.current?.blur(); }
        else if (e.key === "Tab" && showFilters && matchingFilters[selectedIdx]) {
            e.preventDefault();
            onChange(`/${matchingFilters[selectedIdx].key} `);
            inputRef.current?.focus();
        }
        else if (e.key === "ArrowDown") { e.preventDefault(); setSelectedIdx((i) => Math.min(i + 1, listLen - 1)); }
        else if (e.key === "ArrowUp") { e.preventDefault(); setSelectedIdx((i) => Math.max(i - 1, 0)); }
        else if (e.key === "Enter") {
            if (actorSuggestions && actorSuggestions[selectedIdx]) {
                onChange(`/actors ${actorSuggestions[selectedIdx]}`);
                inputRef.current?.focus();
            } else if (showFilters && matchingFilters[selectedIdx]) {
                onChange(`/${matchingFilters[selectedIdx].key} `);
                inputRef.current?.focus();
            } else if (filtered[selectedIdx]) {
                navigate(filtered[selectedIdx]);
            }
        }
    };

    // Close on outside click
    useEffect(() => {
        const onClick = (e: MouseEvent) => {
            if (listRef.current && !listRef.current.contains(e.target as Node)) setOpen(false);
        };
        if (open) document.addEventListener("mousedown", onClick);
        return () => document.removeEventListener("mousedown", onClick);
    }, [open]);

    const activeFilter = filterKey && FILTERS.find((f) => f.key === filterKey);

    return (
        <div className="flow-search" ref={listRef}>
            <style>{`.search-dropdown::-webkit-scrollbar{display:none}`}</style>
            {activeFilter && (
                <div className="flow-search-filter">
                    /{activeFilter.key}
                    <span className="flow-search-filter-label">{activeFilter.label}</span>
                    <span
                        className="flow-search-filter-clear"
                        onClick={() => { setQuery(""); setDebouncedQuery(""); inputRef.current?.focus(); }}
                    >
                        ✕
                    </span>
                </div>
            )}
            <input
                ref={inputRef}
                className="flow-search-input"
                value={query}
                onChange={(e) => { onChange(e.target.value); setOpen(true); }}
                onFocus={() => { if (debouncedQuery.trim()) setOpen(true); }}
                onKeyDown={onKeyDown}
                placeholder="Type / for filters — Search commands, events, policies…"
            />
            {open && actorSuggestions && (
                <div
                    className="search-dropdown"
                    style={{
                        position: "absolute", top: "100%", right: 0, width: DROPDOWN_WIDTH, marginTop: 4,
                        background: "#fff", border: "1px solid #cbd5e1", borderRadius: "6px",
                        maxHeight: 520, overflow: "auto", scrollbarWidth: "none", zIndex: 20,
                        boxShadow: "0 8px 24px rgba(0,0,0,0.12)",
                    }}
                >
                    {actorSuggestions.map((actor, idx) => (
                        <div
                            key={actor}
                            onClick={() => { onChange(`/actors ${actor}`); inputRef.current?.focus(); }}
                            onMouseEnter={() => setSelectedIdx(idx)}
                            style={{
                                display: "flex", alignItems: "center", gap: 8, padding: "8px 12px", cursor: "pointer",
                                background: idx === selectedIdx ? "#f1f5f9" : "transparent", color: "#1e293b",
                                fontSize: "13px", borderBottom: "1px solid #e2e8f0",
                            }}
                        >
                            <span style={{ fontSize: "14px" }}>&#128100;</span>
                            <span style={{ flex: 1 }}>{actor}</span>
                            <span style={{ fontSize: "10px", color: "#94a3b8", textTransform: "uppercase" }}>actor</span>
                        </div>
                    ))}
                </div>
            )}
            {open && showFilters && matchingFilters.length > 0 && (
                <div
                    className="search-dropdown"
                    style={{
                        position: "absolute",
                        top: "100%",
                        right: 0,
                        width: DROPDOWN_WIDTH,
                        marginTop: 4,
                        background: "#fff",
                        border: "1px solid #cbd5e1",
                        borderRadius: "6px",
                        maxHeight: 520,
                        overflow: "auto",
                        scrollbarWidth: "none",
                        zIndex: 20,
                        boxShadow: "0 8px 24px rgba(0,0,0,0.12)",
                    }}
                >
                    {matchingFilters.map((f, idx) => (
                        <div
                            key={f.key}
                            onClick={() => {
                                onChange(filterKey ? `/${f.key} ` : `/${f.key} `);
                                inputRef.current?.focus();
                            }}
                            onMouseEnter={() => setSelectedIdx(idx)}
                            style={{
                                display: "flex",
                                alignItems: "center",
                                gap: 8,
                                padding: "8px 12px",
                                cursor: "pointer",
                                background: idx === selectedIdx ? "#f1f5f9" : "transparent",
                                color: "#1e293b",
                                fontSize: "13px",
                                borderBottom: "1px solid #e2e8f0",
                            }}
                        >
                            <span style={{ fontFamily: "ui-monospace, monospace", fontWeight: 600, color: "#6366f1" }}>/{f.key}</span>
                            <span style={{ color: "#94a3b8", fontSize: "12px" }}>{f.label}</span>
                        </div>
                    ))}
                </div>
            )}
            {open && !showFilters && filtered.length > 0 && (
                <div
                    className="search-dropdown"
                    style={{
                        position: "absolute",
                        top: "100%",
                        right: 0,
                        width: DROPDOWN_WIDTH,
                        marginTop: 4,
                        background: "#fff",
                        border: "1px solid #cbd5e1",
                        borderRadius: "6px",
                        maxHeight: 520,
                        overflow: "auto",
                        scrollbarWidth: "none",
                        zIndex: 20,
                        boxShadow: "0 8px 24px rgba(0,0,0,0.12)",
                    }}
                >
                    {filtered.map((item, idx) => (
                        <div
                            key={item.nodeId}
                            onClick={() => navigate(item)}
                            onMouseEnter={() => setSelectedIdx(idx)}
                            style={{
                                display: "flex",
                                alignItems: "flex-start",
                                gap: 8,
                                padding: "8px 12px",
                                cursor: "pointer",
                                background: idx === selectedIdx ? "#f1f5f9" : "transparent",
                                color: "#1e293b",
                                fontSize: "13px",
                                borderBottom: "1px solid #e2e8f0",
                            }}
                        >
                            <span
                                style={{
                                    width: 8,
                                    height: 8,
                                    borderRadius: "50%",
                                    background: kindColor[item.kind],
                                    flexShrink: 0,
                                    marginTop: 5,
                                }}
                            />
                            <span style={{ flex: 1, overflow: "hidden", minWidth: 0 }}>
                                <div style={{ wordBreak: "break-word" }}>
                                    {searchTerm ? highlightText(item.label, searchTerm) : item.label}
                                </div>
                                {!item.invariantText && (item.sub || item.path) && (
                                    <div style={{ fontSize: "11px", color: "#64748b", fontFamily: "ui-monospace, monospace", wordBreak: "break-word" }}>
                                        {searchTerm
                                            ? highlightText(item.sub || item.path || "", searchTerm)
                                            : (item.sub || item.path)}
                                    </div>
                                )}
                                {item.trigger && (
                                    <div style={{
                                        fontSize: "10px",
                                        color: "#6366f1",
                                        wordBreak: "break-word",
                                        marginTop: 4,
                                        fontFamily: "ui-monospace, monospace",
                                        background: "#eef2ff",
                                        border: "1px solid #c7d2fe",
                                        borderRadius: "5px",
                                        padding: "2px 6px",
                                        display: "inline-block",
                                    }}>
                                        {item.trigger}
                                    </div>
                                )}
                                {item.invariantText && (
                                    <div style={{ fontSize: "11px", color: "#475569", wordBreak: "break-word", marginTop: 1, lineHeight: 1.35 }}>
                                        <Markdown
                                            allowedElements={["p", "strong", "em", "code", "a", "br"]}
                                            unwrapDisallowed
                                        >
                                            {item.invariantText}
                                        </Markdown>
                                    </div>
                                )}
                                {item.entities && item.entities.length > 0 && (
                                    <div style={{ fontSize: "10px", color: "#64748b", wordBreak: "break-word", marginTop: 1, display: "flex", alignItems: "center", gap: 3 }}>
                                        <DbIcon />
                                        {item.entities.join(" · ")}
                                    </div>
                                )}
                                {item.actors && item.actors.length > 0 && (
                                    <div style={{ fontSize: "10px", color: "#b45309", wordBreak: "break-word", marginTop: 1, display: "flex", alignItems: "center", gap: 3 }}>
                                        &#128100; {item.actors.join(", ")}
                                    </div>
                                )}
                            </span>
                            <span style={{ fontSize: "10px", color: "#94a3b8", textTransform: "uppercase", flexShrink: 0 }}>
                                {item.kind}
                            </span>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
