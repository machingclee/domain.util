package com.machingclee.domain.util.common.event.specialevent;

import com.machingclee.domain.util.common.interfaces.Command;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToBeArrangedCommand implements Command<Void> {
    private String desc;
}
