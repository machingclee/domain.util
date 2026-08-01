import { BaseEdge, getSmoothStepPath, Position, type EdgeProps } from "@xyflow/react";

/**
 * A smooth-step edge whose vertical segment can be horizontally offset
 * via `data.spread` (in pixels).
 *
 * Forward edges (source left of target, e.g. Event→Policy):
 *   vertical segment sits between the two nodes, spread by `data.spread`.
 *
 * Backward edges (source right of target, e.g. Policy→Command):
 *   vertical segment is anchored to the RIGHT of the source node so it
 *   stays on the far side of the policy column, away from incoming edges.
 *   `data.spread` fans multiple outgoing edges apart.
 */

const BORDER_RADIUS = 8;

/** Distance from the source handle that the vertical segment is placed
 *  for backward (right-to-left) edges. */
/** Fixed distance to the right of the source handle where the vertical
 *  segment sits for backward edges.  Mirrors the ~300 px gap that
 *  forward edges naturally get from the column midpoint. */
const BACKWARD_BASE_OFFSET = 250;

/** Vertical offset for the final approach to the target node.
 *  The edge enters the target from slightly above (or below, if
 *  target is above source) so the line does not appear to punch
 *  straight through the node. */
const TARGET_APPROACH_OFFSET = 40;

/** Build an SVG path for a backward edge whose vertical segment sits
 *  to the RIGHT of the source (centerX >= sourceX). */
function backwardStepPathRight(
    sourceX: number,
    sourceY: number,
    targetX: number,
    targetY: number,
    centerX: number,
): string {
    const r = Math.min(
        BORDER_RADIUS,
        Math.abs(centerX - sourceX) / 2,
        Math.abs(centerX - targetX) / 2,
        Math.abs(targetY - sourceY) / 2,
    );

    // Approach the target from slightly above/below so the final
    // segment enters at a shallow angle instead of horizontally.
    const ySign = targetY >= sourceY ? 1 : -1;
    const approachY = targetY - TARGET_APPROACH_OFFSET * ySign;

    if (r <= 1) {
        return [
            `M ${sourceX},${sourceY}`,
            `L ${centerX},${sourceY}`,
            `L ${centerX},${approachY}`,
            `L ${targetX},${targetY}`,
        ].join(" ");
    }

    // Path: source → right to near centerX → curve → vertical →
    //       curve → left to past the target → curve right+down
    //       into target (arrow points RIGHT, entering the node)
    // prettier-ignore
    return [
        `M ${sourceX},${sourceY}`,
        `L ${centerX - r},${sourceY}`,
        `Q ${centerX},${sourceY} ${centerX},${sourceY + ySign * r}`,
        `L ${centerX},${approachY - ySign * r}`,
        `Q ${centerX},${approachY} ${centerX - r},${approachY}`,
        `L ${targetX - 3 * r},${approachY}`,
        `L ${targetX - 3 * r},${targetY}`,
        `L ${targetX},${targetY}`,
    ].join(" ");
}

const SpreadStepEdge = ({
    id,
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition = Position.Right,
    targetPosition = Position.Left,
    data,
    style,
    markerEnd,
}: EdgeProps) => {
    // Guard against NaN / Infinity from unmeasured nodes.
    if (!isFinite(sourceX) || !isFinite(sourceY) || !isFinite(targetX) || !isFinite(targetY)) {
        return null;
    }
    const spread = (data as { spread?: number } | undefined)?.spread ?? 0;

    let edgePath: string;
    if (sourceX <= targetX) {
        // Forward — vertical segment centred between nodes
        const centerX = (sourceX + targetX) / 2 + spread;
        [edgePath] = getSmoothStepPath({
            sourceX,
            sourceY,
            targetX,
            targetY,
            sourcePosition,
            targetPosition,
            centerX,
            borderRadius: BORDER_RADIUS,
        });
    } else {
        // Backward — vertical segment on the RIGHT of the source
        const centerX = sourceX + BACKWARD_BASE_OFFSET + spread;
        edgePath = backwardStepPathRight(sourceX, sourceY, targetX, targetY, centerX);
    }

    return <BaseEdge id={id} path={edgePath} style={style} markerEnd={markerEnd} />;
};

export default SpreadStepEdge;
