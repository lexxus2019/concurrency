package com.example.auction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Способ 8 (ветка fix/08-cas-snapshot): единое неизменяемое состояние + CAS.
 *
 * Всё состояние аукциона — в одном immutable-объекте State. Меняем его атомарной
 * операцией compareAndSet: «считал старый снапшот — построил новый — ставлю,
 * если никто не успел впереди; не поставил — повторяю».
 *
 * Что это даёт:
 *  - G1 решена конструктивно: счётчики id живут ВНУТРИ State, id «выдаёт»
 *    сам снапшот — дубли в принципе невозможны;
 *  - G2/G3/G4: любое изменение — это ЦЕЛОЕ состояние целиком, промежуточных
 *    («рваных») состояний снаружи не видно;
 *  - G5 + чтение вообще БЕЗ блокировок: state.get() — это консистентный
 *    снимок, его можно перебирать спокойно.
 *
 * Цена (обсудить на занятии):
 *  - каждая запись копирует все словари — O(n); при тысячах товаров
 *    LoadTest это заметит;
 *  - под сильным конкурентным потоком ретраев много (проигравшие повторяют),
 *    это «lock-free, но не wait-free»;
 *  - сравнение по ссылке объекта: ABA здесь не возникает, т.к. каждый снапшот —
 *    новый объект, но вообще о ABA надо помнить.
 */
@Service
public class Auction {

    /** Неизменяемый снимок всего аукциона. */
    private record State(long nextItemId, long nextBidId,
                         Map<Long, Item> items,
                         Map<Long, List<Bid>> bids) {

        static final State EMPTY = new State(1, 1, Map.of(), Map.of());
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.EMPTY);

    /**
     * Атомарно применить изменение ко всему состоянию; при проигрыше гонки —
     * повторить на свежем снапшоте. Функция change обязана быть без побочных
     * эффектов (кроме записи в captured-массив — её делаем последней).
     */
    private State update(UnaryOperator<State> change) {
        while (true) {
            State current = state.get();
            State next = change.apply(current);
            if (state.compareAndSet(current, next)) {
                return next;
            }
            // кто-то поставил своё состояние раньше нас — повторяем
        }
    }

    /** Выставить товар на аукцион. */
    public Item addItem(String name, long startPrice) {
        final Item[] made = new Item[1];
        update(current -> {
            Item item = new Item(current.nextItemId(), name, startPrice);
            Map<Long, Item> items = new HashMap<>(current.items());
            items.put(item.id(), item);
            made[0] = item;
            return new State(current.nextItemId() + 1, current.nextBidId(),
                    Map.copyOf(items), current.bids());
        });
        return made[0];
    }

    /** Снять товар с продажи (вместе со ставками) — ОДНА атомарная операция. */
    public void removeItem(long id) {
        update(current -> {
            if (!current.items().containsKey(id)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + id + " не найден");
            }
            Map<Long, Item> items = new HashMap<>(current.items());
            items.remove(id);
            Map<Long, List<Bid>> bids = new HashMap<>(current.bids());
            bids.remove(id);
            return new State(current.nextItemId(), current.nextBidId(),
                    Map.copyOf(items), Map.copyOf(bids));
        });
    }

    /** Список всех товаров: чтение без блокировок, снимок сам по себе целостен. */
    public List<Item> listItems() {
        return List.copyOf(state.get().items().values());
    }

    /** Сделать ставку: проверка и вставка внутри одного атомарного обновления. */
    public Bid addBid(long itemId, String bidder, long amount) {
        final Bid[] made = new Bid[1];
        update(current -> {
            Item item = current.items().get(itemId);
            if (item == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
            }

            List<Bid> itemBids = current.bids().getOrDefault(itemId, List.of());
            long best = item.startPrice();
            for (Bid bid : itemBids) {
                best = Math.max(best, bid.amount());
            }
            if (amount <= best) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ставка должна быть выше " + best);
            }

            Bid bid = new Bid(current.nextBidId(), itemId, bidder, amount);
            List<Bid> newBids = new ArrayList<>(itemBids);
            newBids.add(bid);
            Map<Long, List<Bid>> bids = new HashMap<>(current.bids());
            bids.put(itemId, List.copyOf(newBids));
            made[0] = bid;
            return new State(current.nextItemId(), current.nextBidId() + 1,
                    current.items(), Map.copyOf(bids));
        });
        return made[0];
    }

    /** Список ставок на товар — из одного консистентного снимка. */
    public List<Bid> listBids(long itemId) {
        State snapshot = state.get();
        if (!snapshot.items().containsKey(itemId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Товар " + itemId + " не найден");
        }
        return snapshot.bids().getOrDefault(itemId, List.of());
    }
}
