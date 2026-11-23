package com.specshield.ledgerline.application;

/** The response of a completed idempotent request, replayed byte for byte. */
public record StoredResponse(int status, byte[] body, String contentType, String location) {

    public StoredResponse {
        body = body == null ? new byte[0] : body.clone();
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
