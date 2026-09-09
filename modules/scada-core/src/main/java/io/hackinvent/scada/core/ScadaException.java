package io.hackinvent.scada.core;

public final class ScadaException extends RuntimeException {
    private final String code;
    private final int status;
    public ScadaException(String code, int status, String message) {
        super(message); this.code = code; this.status = status;
    }
    public String getCode() { return code; }
    public int getStatus() { return status; }
}
