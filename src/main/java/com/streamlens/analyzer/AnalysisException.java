package com.streamlens.analyzer;

/** Input or aggregation error with the one-based source line where it occurred. */
public final class AnalysisException extends Exception {
    private static final long serialVersionUID = 1L;

    private final long lineNumber;

    public AnalysisException(long lineNumber, String message) {
        super("line " + lineNumber + ": " + message);
        this.lineNumber = lineNumber;
    }

    public AnalysisException(long lineNumber, String message, Throwable cause) {
        super("line " + lineNumber + ": " + message, cause);
        this.lineNumber = lineNumber;
    }

    public long lineNumber() {
        return lineNumber;
    }
}
