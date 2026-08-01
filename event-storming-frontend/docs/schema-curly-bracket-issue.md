# Schema Display — Closing Curly Bracket Indentation

## Status: Fixed

## Target

Fix the closing curly bracket `}` position in the inline schema display so it is visually aligned with the matching opening `{`.

## Solution (Allman-style braces)

Put `{` and `}` on their **own lines at the same `indent`**, separate from the field/type line:

```
prevAssignees : BookingScheduledCarAssignee.DTO[]
{
    id : number
    selectedTimeslotId : number
    userId : number
}
```

Both braces share `paddingLeft: indent * 14`, so they align. Nested fields stay at `indent + 1`.

### Why not other approaches

| Approach | Result |
|---|---|
| `}` at `indent` (original) | `}` flush left of field name; `{` sat after type text → misaligned |
| `}` at `indent + 1` | Aligned with inner fields — user said "indented too much" |
| Pixel/ch offset to sit under same-line `{` | Fragile across fonts/sizes/gaps |

### Code changes

- `src/types.ts` — `flattenSchema()` emits: field line (`isDtoType`) → open-brace line → nested fields → close-brace line. `isOpenBrace` is now `boolean`.
- `src/components/nodes/LabelNode.tsx` — `SchemaLineView` renders open/close braces as standalone lines with shared brace styling; DTO type names stay blue via `isDtoType`.
