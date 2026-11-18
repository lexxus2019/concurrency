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
 * Способ 4 (ветка fix/04-atomics-chm): «модный» подход — атомарный счётчик
 * и конкурентная карта ВМЕСТО блокировок.
 *
 * ⚠ ЭТО УЧЕБНАЯ ЛОВУШКА: ветка намеренно остаётся с красным RaceTest.
 *
 * Что чинится «бесплатно»:
 *  - G1: AtomicLong.getAndIncrement() — id больше не дублируются;
 *  - G2/G5: ConcurrentHashMap сам безопасен для put/get из разных потоков.
 *
 * Что НЕ чинится:
 *  - G3: addBid — это check-then-act: сначала читаем список ставок, потом
 *    в него пишем. Каждый отдельный шаг атомен, а пара шагов — нет:
 *    два покупателя одновременно видят одну и ту же «лучшую ставку»,
 *    оба проходят проверку, и в списке ставки идут НЕ по возрастанию;
 *  - обычный ArrayList внутри карты: ConcurrentHashMap не защищает его
 *    содержимое, параллельный add теряет элементы;
 *  - G4: removeItem по-прежнему ходит в две карты.
 *
 * Мораль: атомарные детали ≠ атомарная операция. Гонку убирает атомарность
 * ВСЕЙ операции целиком, а не отдельных шагов. Как это сделать без
 * глобальной блокировки — см. ветки 05, 07, 08, 09.
 */
@Service
public class Auction {

    private final Map<Long, Item> items = new ConcurrentHashMap<>();
    private final Map<Long, List<Bid>> bids = new ConcurrentHashMap<>();

    private final AtomicLong nextItemId = new AtomicLong(1);
    private final AtomicLong nextBidId = new AtomicLong(1);

    /** Выставить товар на аукцион. Здесь всё стало безопасно. */
    public Item addItem(String name, long startPrice) {
        Item item = new Item(nextItemId.getAndIncrement(), name, startPrice);
        items.put(item.id(), item);
        return item;
    }

    /** Снять товар с продажи. G4 (расщепление на две карты) осталась. */
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
     * А вот тут ловушка: getAndIncrement и put атомарны по отдельности,
     * но «прочитали best — проверили — добавили» раскрывается между шагами.
     */
    public Bid addBid(long itemId, String bidder, long amount) {
        Item item = items.get(itemId);
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
        }

        List<Bid> itemBids = bids.computeIfAbsent(itemId, key -> new ArrayList<>());
        long best = item.startPrice();
        for (Bid bid : itemBids) {
            best = Math.max(best, bid.amount());
        }
        if (amount <= best) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ставка должна быть выше " + best);
        }

        Bid bid = new Bid(nextBidId.getAndIncrement(), itemId, bidder, amount);
        itemBids.add(bid);
        return bid;
    }

    public List<Bid> listBids(long itemId) {
        if (!items.containsKey(itemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
        }
        // ArrayList без какой-либо синхронизации: даже читать его параллельно
        // с записью опасно (CME или рваные данные)
        return bids.getOrDefault(itemId, List.of());
    }
}
