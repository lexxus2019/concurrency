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
 * Способ 7 (ветка fix/07-per-lot-lock): лок НА ОДИН ЛОТ + агрегация.
 *
 * Главная идея даже не в локах, а в структуре: товар и его ставки — это
 * одна сущность Lot. Тогда:
 *  - G4 не существует: removeItem = одна операция на одной карте;
 *  - блокировка нужна только на конкретный лот (его монитор). Покупатели
 *    разных товаров вообще не пересекаются — максимальный параллелизм
 *    из всех «локованных» способов;
 *  - сама карта — ConcurrentHashMap: get/put на неё потокобезопасны,
 *    содержимое лота защищено synchronized(lot).
 *
 * Классические вопросы для обсуждения:
 *  - а что если операция затронет ДВА лота? -> два synchronized, нужен
 *    договорённый порядок захвата (по id), иначе deadlock;
 *  - монитор лота виден снаружи (lot — часть map) — в production-коде
 *    вместо него кладут приватный Object lock;
 *  - ставка влетевшая в товар, удалённый за мгновение до этого: лот
 *    возвращается в bids.computeIfAbsent, но GET его уже не покажет.
 *    Это бизнес-решение, не байт-код: «принять или отказать».
 */
@Service
public class Auction {

    /** Агрегат: товар и его ставки живут вместе и защищаются вместе. */
    private static final class Lot {
        final Item item;
        final List<Bid> bids = new ArrayList<>();

        Lot(Item item) {
            this.item = item;
        }
    }

    private final Map<Long, Lot> lots = new ConcurrentHashMap<>();

    private final AtomicLong nextItemId = new AtomicLong(1);
    private final AtomicLong nextBidId = new AtomicLong(1);

    /** Выставить товар на аукцион. */
    public Item addItem(String name, long startPrice) {
        Item item = new Item(nextItemId.getAndIncrement(), name, startPrice);
        lots.put(item.id(), new Lot(item));
        return item;
    }

    /** Снять товар с продажи: одна операция на одной карте. G4 исправлено структурой. */
    public void removeItem(long id) {
        if (lots.remove(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + id + " не найден");
        }
    }

    public List<Item> listItems() {
        List<Item> result = new ArrayList<>();
        for (Lot lot : lots.values()) {
            result.add(lot.item); // Item — неизменяемый record, копия не нужна
        }
        return result;
    }

    /**
     * Сделать ставку: блокируем только конкретный лот.
     * Ставки на разные товары идут параллельно, на один товар — по очереди.
     */
    public Bid addBid(long itemId, String bidder, long amount) {
        Lot lot = lots.get(itemId);
        if (lot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
        }
        synchronized (lot) {
            long best = lot.item.startPrice();
            for (Bid bid : lot.bids) {
                best = Math.max(best, bid.amount());
            }
            if (amount <= best) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ставка должна быть выше " + best);
            }
            Bid bid = new Bid(nextBidId.getAndIncrement(), itemId, bidder, amount);
            lot.bids.add(bid);
            return bid;
        }
    }

    /** Список ставок на товар: копия под монитором лота. */
    public List<Bid> listBids(long itemId) {
        Lot lot = lots.get(itemId);
        if (lot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
        }
        synchronized (lot) {
            return new ArrayList<>(lot.bids);
        }
    }
}
