package com.streamlens.analyzer;

import java.io.IOException;

/** A checked failure while validating or aggregating an input stream. */
public final class AnalysisException extends Exception {
    private static final long serialVersionUID = 1L;

    public AnalysisException(String message) {
        super(message);
    }

    public AnalysisException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Returns the stable contract message without exposing implementation causes. */
    public String detail() {
        return super.getMessage();
    }

    /** Creates the contract's line-numbered wrapper for an input read failure. */
    public static AnalysisException readFailure(int lineNumber, IOException cause) {
        return new AnalysisException(
                "line " + lineNumber + ": read input: " + cause.getMessage(), cause);
    }
}
