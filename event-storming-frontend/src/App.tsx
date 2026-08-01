import { useEffect, useState } from "react";
import CommandFlowVisualizer from "./components/CommandFlowVisualizer";
import "./App.css";
import devCommands from "./commands.json";
import {
    normalizeCommandFrom,
    normalizeInvolvedEntities,
    type FlowData,
    type QueryPayload,
} from "./types";

/** Coerce mixed string | QueryPayload into QueryPayload. */
function normalizeQueryFrom(
    raw: string | QueryPayload | undefined | null,
): QueryPayload {
    if (raw == null) return { query: "", payload: {} };
    if (typeof raw === "string") return { query: raw, payload: {} };
    return {
        query: raw.query ?? "",
        payload: (raw.payload as Record<string, unknown>) ?? {},
    };
}

/** Normalize command.from, query.from, and involvedEntities from backend / sample data. */
function normalizeFlowData(data: FlowData): FlowData {
    return {
        ...data,
        commands: data.commands.map((cmd) => ({
            ...cmd,
            from: normalizeCommandFrom(cmd.from as any),
            involvedEntities: normalizeInvolvedEntities(
                cmd.involvedEntities as any,
            ),
        })),
        queries: (data.queries ?? []).map((q) => ({
            ...q,
            from: normalizeQueryFrom(q.from as any),
            result: normalizeQueryFrom(q.result as any),
        })),
    };
}

function App() {
    const [commands, setCommands] = useState(
        import.meta.env.DEV
            ? normalizeFlowData(devCommands as unknown as FlowData)
            : null,
    );

    useEffect(() => {
        if (import.meta.env.DEV) return;
        const prefix =
            window.location.pathname.split("/command-visualization/")[0];
        const params = new URLSearchParams(window.location.search);
        const rawUrl = params.get("url") ?? "/docs/commands";
        const endpoint = rawUrl.startsWith(prefix + "/")
            ? rawUrl
            : prefix + rawUrl;
        fetch(endpoint)
            .then((res) => res.json())
            .then((data) => {
                console.log("Setting data into diagram", data?.result);
                setCommands(normalizeFlowData(data?.result as FlowData));
            })
            .catch((err) =>
                console.error("Failed to fetch commands flow:", err),
            );
    }, []);

    if (!commands) return null;

    return (
        <div className="w-screen h-screen">
            <CommandFlowVisualizer commands={commands} />
        </div>
    );
}

export default App;
