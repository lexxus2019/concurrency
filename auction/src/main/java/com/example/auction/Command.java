package com.example.auction;

/**
 * Команда аукциону. Едет в брокер как JSON.
 * Поля, не нужные команде, — null (или itemId = -1).
 */
public record Command(String action, long itemId, String name, Long startPrice,
                      String bidder, Long amount) {
}
