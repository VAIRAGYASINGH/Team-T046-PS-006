package com.ps006.upiswitch.command.parser;

import com.ps006.upiswitch.common.exception.SwitchException;

public class CommandParseException extends SwitchException {

    private final int lineNo;
    private final String rawLine;

    public CommandParseException(int lineNo, String rawLine, String message) {
        super(String.format("Parse error at line %d: %s [Line: '%s']", lineNo, message, rawLine));
        this.lineNo = lineNo;
        this.rawLine = rawLine;
    }

    public int getLineNo() { return lineNo; }
    public String getRawLine() { return rawLine; }
}

