package com.machingclee.domain.util.common.interfaces;


public interface CommandHandler<T extends Command<R>, R> {
    R handle(EventQueue eventQueue, T command) throws Exception;
}
