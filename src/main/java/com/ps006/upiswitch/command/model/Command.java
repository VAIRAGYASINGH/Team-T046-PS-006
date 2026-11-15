package com.ps006.upiswitch.command.model;

import java.time.LocalDateTime;

/**
 * Fundamental executable command interface for the PS-006 payment switch.
 * Encapsulates the physical file order, raw text, timestamp, parsed arguments, and execution behavior.
 */
public interface Command {

    int getLineNo();

    String getRawText();

    CommandType getType();

    LocalDateTime getTimestamp();

    ParsedCommand getParsedCommand();

    void execute(CommandExecutionContext context);
}

