package com.machingclee.domain.util.common.dto;

public record PolicyFlowEntryDTO(String fromEvent, String toCommand, String invariant) {
    public PolicyFlowEntryDTO() {
        this(null, null, null);
    }
}
