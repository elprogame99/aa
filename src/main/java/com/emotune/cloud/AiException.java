package com.emotune.cloud;

/** Error controlado de la capa de IA, con código estable y mensaje listo para mostrar al usuario. */
public class AiException extends Exception {
    private final int status;
    private final String code;

    public AiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
