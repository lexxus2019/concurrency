package com.example.auction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Способ 3 (ветка fix/03-reentrant-lock): явная блокировка ReentrantLock.
 *
 * По смыслу это тот же один большой замок, что и в способах 1–2, но с
 * расширенным API — lock виден в коде как объект, и им можно управлять:
 *  - tryLock(timeout) — не ждать вечно, а ответить 503 под перегрузкой;
 *  - lockInterruptibly() — отпускать поток при отмене запроса;
 *  - new ReentrantLock(true) — честная очередь ожидания (FIFO);
 *  - lock.newCondition() — ждать/будить потоки (например, «торги закрыты»),
 *    на synchronized так не делается.
 *
 * Цена та же: операции сериализуются. finally обязателен — иначе
 * исключение из блока оставит замок захваченным навсегда.
 */
@Service
public class Auction {

    private final ReentrantLock lock = new ReentrantLock();

    private final Map<Long, Item> items = new HashMap<>();
    private final Map<Long, List<Bid>> bids = new HashMap<>();

    private long nextItemId = 1;
    private long nextBidId = 1;

    /** Выставить товар на аукцион. */
    public Item addItem(String name, long startPrice) {
        lock.lock();
        try {
            Item item = new Item(nextItemId++, name, startPrice);
            items.put(item.id(), item);
            return item;
        } finally {
            lock.unlock();
        }
    }

    /** Снять товар с продажи (вместе со ставками). */
    public void removeItem(long id) {
        lock.lock();
        try {
            if (items.remove(id) == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + id + " не найден");
            }
            bids.remove(id);
        } finally {
            lock.unlock();
        }
    }

    /** Список всех товаров. */
    public List<Item> listItems() {
        lock.lock();
        try {
            return new ArrayList<>(items.values());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Сделать ставку. Простое правило: ставка должна быть выше
     * текущей лучшей (или стартовой цены, если ставок ещё нет).
     */
    public Bid addBid(long itemId, String bidder, long amount) {
        lock.lock();
        try {
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
        } finally {
            lock.unlock();
        }
    }

    /** Список ставок на товар. */
    public List<Bid> listBids(long itemId) {
        lock.lock();
        try {
            if (!items.containsKey(itemId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
            }
            return new ArrayList<>(bids.getOrDefault(itemId, List.of()));
        } finally {
            lock.unlock();
        }
    }
}
