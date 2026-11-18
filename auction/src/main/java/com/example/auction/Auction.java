package com.example.auction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Способ 2 (ветка fix/02-sync-blocks): synchronized-БЛОК на приватном
 * lock-объекте вместо synchronized-МЕТОДА.
 *
 * Два учебных момента:
 *  1) Не блокируемся на this: на объекте сервиса может взять блокировку
 *     кто угодно извне (Spring, мониторинг, чужой код) — и мы получим
 *     взаимоблокировку или деградацию. Приватный final Object lock — наш.
 *  2) В критическую секцию берём только работу с общим состоянием,
 *     «подумать» (собрать объект-ответ) можно и без блокировки.
 *
 * По эффекту здесь это всё ещё один большой замок на весь сервис
 * (разные операции блокируются друг о друга) — реальное распараллеливание
 * появится в ветках 05/07.
 */
@Service
public class Auction {

    private final Object lock = new Object();

    private final Map<Long, Item> items = new HashMap<>();
    private final Map<Long, List<Bid>> bids = new HashMap<>();

    private long nextItemId = 1;
    private long nextBidId = 1;

    /** Выставить товар на аукцион. */
    public Item addItem(String name, long startPrice) {
        Item item;
        synchronized (lock) {
            item = new Item(nextItemId++, name, startPrice);
            items.put(item.id(), item);
        }
        return item;
    }

    /** Снять товар с продажи (вместе со ставками). */
    public void removeItem(long id) {
        synchronized (lock) {
            if (items.remove(id) == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + id + " не найден");
            }
            bids.remove(id);
        }
    }

    /** Список всех товаров: копия снимается под блокировкой. */
    public List<Item> listItems() {
        synchronized (lock) {
            return new ArrayList<>(items.values());
        }
    }

    /**
     * Сделать ставку. Проверка «выше ли она» и вставка — ОНА критическая
     * секция (check-then-act): между этими двумя действиям без блокировки
     * влетает соседняя ставка.
     */
    public Bid addBid(long itemId, String bidder, long amount) {
        synchronized (lock) {
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
    }

    /** Список ставок на товар. */
    public List<Bid> listBids(long itemId) {
        synchronized (lock) {
            if (!items.containsKey(itemId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
            }
            return new ArrayList<>(bids.getOrDefault(itemId, List.of()));
        }
    }
}
