package com.example.auction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Способ 1 (ветка fix/01-synchronized-methods): ключевое слово synchronized
 * на всех методах — «грубый монитор».
 *
 * Одна блокировка на весь сервис делает атомарными сразу всё:
 *   G1 инкремент nextItemId++/nextBidId++,
 *   G2 put/add в HashMap/ArrayList,
 *   G3 проверку «ставка выше текущей» + вставку,
 *   G4 составной removeItem,
 *   G5 видимость ( synchronized = happens-before ).
 *
 * Плюсы: минимум кода, ошибиться почти нельзя.
 * Минусы: сериализует всё — даже GET /items ждёт, пока кто-то делает ставку.
 * Сравните цифры LoadTest с main и с ветками 06/07/08.
 */
@Service
public class Auction {

    private final Map<Long, Item> items = new HashMap<>();
    private final Map<Long, List<Bid>> bids = new HashMap<>();

    private long nextItemId = 1;
    private long nextBidId = 1;

    /** Выставить товар на аукцион. */
    public synchronized Item addItem(String name, long startPrice) {
        Item item = new Item(nextItemId++, name, startPrice);
        items.put(item.id(), item);
        return item;
    }

    /** Снять товар с продажи (вместе со ставками). */
    public synchronized void removeItem(long id) {
        if (items.remove(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + id + " не найден");
        }
        bids.remove(id);
    }

    /** Список всех товаров. */
    public synchronized List<Item> listItems() {
        return new ArrayList<>(items.values());
    }

    /**
     * Сделать ставку. Простое правило: ставка должна быть выше
     * текущей лучшей (или стартовой цены, если ставок ещё нет).
     */
    public synchronized Bid addBid(long itemId, String bidder, long amount) {
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

    /** Список ставок на товар. Копию делаем ПОД блокировкой: иначе сериализация
     * ответа уже без lock увидит параллельный add в тот же ArrayList. */
    public synchronized List<Bid> listBids(long itemId) {
        if (!items.containsKey(itemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
        }
        return new ArrayList<>(bids.getOrDefault(itemId, List.of()));
    }
}
