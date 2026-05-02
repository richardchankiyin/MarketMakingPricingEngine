package com.richard.marketmakingpricing.oms;

public enum ExecutionReportStatus {
    FILLED("2"),
    REJECTED("8");

    private final String fixTag39Value;

    ExecutionReportStatus(String fixTag39Value) {
        this.fixTag39Value = fixTag39Value;
    }

    public String getFixTag39Value() {
        return fixTag39Value;
    }
}