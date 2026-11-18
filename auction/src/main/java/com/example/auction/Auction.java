package com.example.auction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Способ 6 (ветка fix/06-read-write-lock): ReentrantReadWriteLock.
 *
 * Наблюдение: GET /items и GET /bids не меняют состояние. В способах 1–3
 * они стояли в одной очереди со ставками. ReadWriteLock пускает читателей
 * вместе: сколько угодно потоков могут читать параллельно, а запись
 * эксклюзивна — ждёт всех читателей и сама блокирует читателей.
 *
 * Чинит все G тем же приёмом (одна пара локов на весь сервис), но читатель
 * теперь не ждёт других читателей — это видно на LoadTest, где много
 * запросов на чтение.
 *
 * Минусы/подводные камни:
 *  - блокировка читателя дороже обычного synchronized (счётчик читателей),
 *    поэтому при mix 50/50 выгода может уйти в минус — измеряйте, а не
 *    угадывайте (LoadTest!);
 *  - апгрейд read->write недопустим ( deadlock ), вместо этого — отпустил
 *    read, взял write;
 *  - при постоянном потоке читателей запись может голодать (нет записи
 *    writer-preference по умолчанию; на этом локе можно new ReentrantReadWriteLock(true)).
 */
@Service
public class Auction {

    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();

    private final Map<Long, Item> items = new HashMap<>();
    private final Map<Long, List<Bid>> bids = new HashMap<>();

    private long nextItemId = 1;
    private long nextBidId = 1;

    /** Выставить товар на аукцион. */
    public Item addItem(String name, long startPrice) {
        rwLock.writeLock().lock();
        try {
            Item item = new Item(nextItemId++, name, startPrice);
            items.put(item.id(), item);
            return item;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** Снять товар с продажи (вместе со ставками). */
    public void removeItem(long id) {
        rwLock.writeLock().lock();
        try {
            if (items.remove(id) == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + id + " не найден");
            }
            bids.remove(id);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** Список всех товаров — читаем параллельно с другими читателями. */
    public List<Item> listItems() {
        rwLock.readLock().lock();
        try {
            return new ArrayList<>(items.values());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Сделать ставку. Только запись.
     * NB: тут нельзя «пройтись read-локом по списку, потом write-локом
     * добавить» — между этими моментами состояние может измениться.
     * Поэтому вся операция целиком под writeLock.
     */
    public Bid addBid(long itemId, String bidder, long amount) {
        rwLock.writeLock().lock();
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
            rwLock.writeLock().unlock();
        }
    }

    /** Список ставок на товар — тоже параллельные читатели. */
    public List<Bid> listBids(long itemId) {
        rwLock.readLock().lock();
        try {
            if (!items.containsKey(itemId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
            }
            return new ArrayList<>(bids.getOrDefault(itemId, List.of()));
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
