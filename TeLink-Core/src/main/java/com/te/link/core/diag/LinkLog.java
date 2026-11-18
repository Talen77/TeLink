package com.te.link.core.diag;

public record LinkLog(String logId, String level, int machineId, String message, String exceptionText) {
}
