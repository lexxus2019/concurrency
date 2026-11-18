package com.example.auction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Способ 5 (ветка fix/05-chm-compute): атомарные составные операции самой
 * ConcurrentHashMap — compute().
 *
 * В ветке 04 мы убедились: атомарные шаги не дают атомарной операции.
 * Помогает compute(): блокировка берётся только на ячейку (bin) карты
 * с этим товаром, и внутри неё мы делаем ВСЮ операцию —
 * «прочитать ставки, проверить, добавить». Проверка и вставка больше
 * не раскрываются, при этом разные товары друг другу не мешают.
 *
 * Чинится: G1 (AtomicLong), G2 (CHM), G3 (compute = одна атомарная операция).
 * Остаётся нюансом: G4 — removeItem ходит в две карты и между двумя
 * вызовами конкурент addBid может «принять» ставку на уже удалённый товар.
 * Полностью от этого избавляемся в ветке 07, где лот = одна сущность.
 *
 * Важно: внутри compute нельзя делать долгую работу и нельзя рекурсивно
 * вызывать другие методы этой же карты — документация CHM это прямо запрещает.
 */
@Service
public class Auction {

    private final Map<Long, Item> items = new ConcurrentHashMap<>();
    private final Map<Long, List<Bid>> bids = new ConcurrentHashMap<>();

    private final AtomicLong nextItemId = new AtomicLong(1);
    private final AtomicLong nextBidId = new AtomicLong(1);

    /** Выставить товар на аукцион. */
    public Item addItem(String name, long startPrice) {
        Item item = new Item(nextItemId.getAndIncrement(), name, startPrice);
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

    public List<Item> listItems() {
        return new ArrayList<>(items.values());
    }

    /**
     * Сделать ставку — целиком внутри compute(itemId, ...):
     * атомарно относительно любых других операций над ставками этого товара.
     */
    public Bid addBid(long itemId, String bidder, long amount) {
        Item item = items.get(itemId);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
        }

        Bid made = new Bid(nextBidId.getAndIncrement(), itemId, bidder, amount);

        bids.compute(itemId, (key, currentBids) -> {
            List<Bid> list = (currentBids == null) ? new ArrayList<>() : currentBids;

            long best = item.startPrice();
            for (Bid bid : list) {
                best = Math.max(best, bid.amount());
            }
            if (amount <= best) {
                // исключение из compute: карта остаётся неизменной
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ставка должна быть выше " + best);
            }

            list.add(made);
            return list;
        });
        return made;
    }

    /**
     * Список ставок. Читаем тоже через compute: это та же блокировка ячейки,
     * поэтому снимок списка не «порвётся» на середине чужой вставки.
     */
    public List<Bid> listBids(long itemId) {
        if (!items.containsKey(itemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
        }
        List<Bid> snapshot = new ArrayList<>();
        bids.computeIfPresent(itemId, (key, list) -> {
            snapshot.addAll(list);
            return list; // список не меняем, просто пользуемся блокировкой ячейки
        });
        return snapshot;
    }
}
