package com.example.auction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * СПОСОБ 10 (ветка fix/10-embedded-artemis): бизнес-логика аукциона.
 *
 * Обратите внимание: тело методов ЗДЕСЬ Байт-в-байт как в сломанном main —
 * тот же HashMap, ArrayList и nextItemId++. Гонки нет не потому, что код
 * защищён, а потому что к этому объекту обращается РОВНО ОДИН поток:
 * единственный потребитель очереди auction.cmd (spring.artemis.listener.concurrency=1).
 *
 * Это «single writer principle»: самый надёжный способ убрать гонку — сделать
 * так, чтобы писателей не было больше одного. Так живут Kafka и однопоточные
 * базы данных, так работал способ 9 — только там исполнитель был потоком внутри
 * процесса, а здесь это брокер сообщений: очередь можно пережить рестарт,
 * смотреть её размером, разнести по машинам.
 */
@Component
public class AuctionState {

    private final Map<Long, Item> items = new HashMap<>();
    private final Map<Long, List<Bid>> bids = new HashMap<>();

    private long nextItemId = 1;
    private long nextBidId = 1;

    /** Выставить товар на аукцион. */
    public Item addItem(String name, long startPrice) {
        Item item = new Item(nextItemId++, name, startPrice);
        items.put(item.id(), item);
        return item;
    }

    /** Снять товар с продажи (вместе со ставками). */
    public void removeItem(long id) {
        if (items.remove(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + id + " не найден");
        }
        bids.remove(id);
    }

    /** Список всех товаров. */
    public List<Item> listItems() {
        return new ArrayList<>(items.values());
    }

    /** Сделать ставку (проверка и вставка идут в одном потоке — гонки нет). */
    public Bid addBid(long itemId, String bidder, long amount) {
        Item item = items.get(itemId);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
        }

        List<Bid> itemBids = bids.getOrDefault(itemId, List.of());
        long best = item.startPrice();
        for (Bid bid : itemBids) {
            best = Math.max(best, bid.amount());
        }
        if (amount <= best) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ставка должна быть выше " + best);
        }

        Bid bid = new Bid(nextBidId++, itemId, bidder, amount);
        bids.computeIfAbsent(itemId, key -> new ArrayList<>()).add(bid);
        return bid;
    }

    /** Список ставок на товар. */
    public List<Bid> listBids(long itemId) {
        if (!items.containsKey(itemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
        }
        return new ArrayList<>(bids.getOrDefault(itemId, List.of()));
    }
}
