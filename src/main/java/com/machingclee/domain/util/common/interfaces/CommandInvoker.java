package com.machingclee.domain.util.common.interfaces;


import com.machingclee.domain.util.common.dto.FlowResponseDTO;

public interface CommandInvoker {
    <T extends Command<R>, R> R invoke(CommandHandler<T, R> handler, T command) throws Exception;

    <R> R invoke(Command<R> command) throws Exception;

    FlowResponseDTO getFlow();
}
