package com.example.auction;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Ответ аукциона: либо результат (result), либо ошибка (error) в формате
 * "http-код:причина". result — кусок JSON, контроллер превращает его в Item/Bid.
 */
public record Reply(JsonNode result, String error) {

    public static Reply ok(JsonNode result) {
        return new Reply(result, null);
    }

    public static Reply error(int httpStatus, String reason) {
        return new Reply(null, httpStatus + ":" + reason);
    }
}
