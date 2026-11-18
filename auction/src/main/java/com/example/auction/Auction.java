package com.example.auction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Способ 9 (ветка fix/09-actor-thread): один поток-актор, локов нет.
 *
 * Принцип «share memory by communicating»: к mutable-состоянию имеет доступ
 * РОВНО ОДИН поток. Все запросы превращаются в задачи и складываются в
 * однопоточную очередь; исполнитель их обрабатывает по одному. Гонки
 * невозможны не потому, что мы правильно залочились, а потому, что
 * конкурентного доступа к состоянию физически нет — даже тело методов
 * осталось байт в байт как в сломанном main!
 *
 * За просрочку очереди и ответ клиенту платим Future.get() в HTTP-потоке.
 *
 * Плюсы: простая рассуждалка «что может пойти не так» (ответ: ничего внутри
 * актора); всякие таймауты/отмены решаются в одном месте (ask()).
 *
 * Минусы и куда это растёт:
 *  - состояние в одном потоке = потолок пропускной способности всего сервиса;
 *  - Long TTL-задача в очереди блокирует все ставки за ней
 *    (отсюда — дедлайн на задачу и приоритеты);
 *  - next-шаг в проде: состояние реплицируют, акторов несколько (sharding),
 *    а вместо Future-ответов — событийная шина (Kafka). См. ветку 10.
 */
@Service
public class Auction {

    private final ExecutorService actor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "auction-actor");
        thread.setDaemon(true);
        return thread;
    });

    // обычное НЕпотокобезопасное состояние — но трогает его только поток actor
    private final Map<Long, Item> items = new HashMap<>();
    private final Map<Long, List<Bid>> bids = new HashMap<>();

    private long nextItemId = 1;
    private long nextBidId = 1;

    /** Отправить задачу актору и дождаться ответа с его потоком. */
    private <T> T ask(Callable<T> task) {
        try {
            return actor.submit(task).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Запрос прерван");
        } catch (ExecutionException e) {
            // бизнес-исключения из тела задачи несём клиенту как есть (404, 400...)
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Аукцион: " + e.getCause());
        }
    }

    /** Выставить товар на аукцион. */
    public Item addItem(String name, long startPrice) {
        return ask(() -> {
            Item item = new Item(nextItemId++, name, startPrice);
            items.put(item.id(), item);
            return item;
        });
    }

    /** Снять товар с продажи (вместе со ставками). */
    public void removeItem(long id) {
        ask(() -> {
            if (items.remove(id) == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + id + " не найден");
            }
            bids.remove(id);
            return null;
        });
    }

    /** Список всех товаров. */
    public List<Item> listItems() {
        return ask(() -> new ArrayList<>(items.values()));
    }

    /** Сделать ставку: проверка и вставка идут в одном потоке — атомарно по построению. */
    public Bid addBid(long itemId, String bidder, long amount) {
        return ask(() -> {
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
        });
    }

    /** Список ставок на товар. */
    public List<Bid> listBids(long itemId) {
        return ask(() -> {
            if (!items.containsKey(itemId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
            }
            return new ArrayList<>(bids.getOrDefault(itemId, List.of()));
        });
    }
}
