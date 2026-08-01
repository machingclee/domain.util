# Nested Subcontext Grouping for Event-Storming Visualizer

## Purpose

Add **hierarchical sub-context grouping** to the command/event/policy flow visualizer. When commands use dotted context names like `"Booking.ScheduleLink"`, they render inside a nested **ScheduleLink** sub-group (inner bounding box) within the outer **Booking** parent box. Sub-groups are draggable, constrained within their parent, and move with the parent when it is dragged.

## Architecture Decisions

| Decision | Rationale |
|----------|-----------|
| Dot-separated context names | Natural hierarchy notation, no schema changes needed |
| React Flow native nested groups via `parentId` | Leverages built-in coordinate transforms, extent constraints, auto child movement |
| `pointerEvents: none` on GroupNode background | Prevents parent's div from capturing clicks meant for child sub-groups |
| `zIndex: depth * 10` | Ensures sub-groups render above parent groups in z-order |
| `extent: "parent"` on sub-groups | React Flow constrains sub-group position within parent bounds |
| Recursive `layoutContextNode` | Clean separation — each context level handles its own commands + sub-contexts |

## Files Changed

### `src/types.ts`
Added `ContextNode` interface and `buildContextTree()` utility:
- `ContextNode`: `name`, `label`, `depth`, `parentPath`, `children`, `commands`
- `buildContextTree(commands)`: splits dot-separated context names into a hierarchy tree. `"Booking.ScheduleLink"` → child of `"Booking"`

### `src/hooks/useLayoutEngine.ts`
Replaced flat `[contextName, Command[]]` grouping with recursive layout:
- `layoutContextNode(contextNode, parentGroupId, parentStartY)` — lays out direct commands, then recursively positions sub-context groups below
- **Critical**: Sub-groups use **relative coordinates** (`posY = groupStartY - parentStartY`) because React Flow interprets `position` as relative when `parentId` is set
- Sub-groups: `parentId`, `extent: "parent"`, `zIndex: depth * 10`, `draggable: true`
- New constants: `SUB_CONTEXT_GAP = 36`, `SUB_CONTEXT_INDENT = 28`

### `src/components/nodes/GroupNode.tsx`
- Background div: `pointerEvents: "none"` — clicks fall through to React Flow wrappers
- Badge: `pointerEvents: "auto"` — serves as drag handle, shows edit/add buttons
- Sub-groups (depth > 0): dashed border, grey badge, smaller font, `↳` indent marker
- Parent groups (depth 0): solid border, blue badge, larger font

### `src/components/AddCommandModal.tsx`
- Context is now an editable field with autocomplete datalist of existing contexts
- Accepts dotted names for sub-context creation

### `src/components/EditCommandModal.tsx`
- Added Context field for moving commands between contexts/subcontexts
- Autocomplete datalist for existing contexts

### `src/components/CommandFlowVisualizer.tsx`
- **Drag cascade**: `onNodeDrag` walks up parent chain and resizes each ancestor group
- **Context selection**: Recursive descendant collection via `collectDescendants()`
- **Context rename**: Prefix-match cascading — renaming `"Booking"` → `"Reservations"` also renames `"Booking.ScheduleLink"` → `"Reservations.ScheduleLink"`
- Passes `allContexts` to modals for autocomplete

## Behavior

- **Dragging sub-group by badge**: Badge acts as drag handle. Sub-group stays within parent bounds (`extent: "parent"`).
- **Dragging parent group**: Entire group including sub-contexts moves with it (React Flow handles via relative coordinates).
- **Dragging a command inside a sub-group**: Cascades resize upward (sub-group → parent group → ...).
- **Empty area of groups**: Not used for dragging (pointer-events: none) — avoids parent capturing clicks meant for sub-groups.

## Current State

- **Test data**: `commands.json` has 3 commands with `context: "Booking.ScheduleLink"` and ~10 with `context: "Booking"`. Other contexts: `"Car Catelog"`, `"Sales"`.
- **Build**: `npm run build` → `dist/` with hashed assets
- **Deploy**: Built assets copied to `domain.util/src/main/resources/META-INF/resources/command-visualization/`, then `mvn install -DskipTests` rebuilds the JAR
- **Serving path**: `/command-visualization/index.html?url=/docs/commands`

## Deploy Paths

```
Frontend source: /Users/chingcheonglee/Repos/hkev/echarge-event-storming-component
Target module:   /Users/chingcheonglee/Repos/hkev/java-modules/domain.util
Install dest:    domain.util/src/main/resources/META-INF/resources/command-visualization/
```
