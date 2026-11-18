package com.example.auction;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jms.JmsException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * СПОСОБ 10 (ветка fix/10-embedded-artemis): очередь сообщений ВМЕСТО блокировок.
 *
 * Контраллер не изменился: он по-прежнему дергает методы Auction. Но теперь
 * каждый вызов — это не заход в synchronized, а ОТПРАВКА КОМАНДЫ в брокер
 * (embedded ActiveMQ Artemis) и ожидание ответа. Состояние меняет единственный
 * потребитель очереди (AuctionCommandListener), значит конкурентной записи нет
 * по построению — см. AuctionState.
 *
 * Что появляется «бесплатно», чего не даёт ни один вид synchronized:
 *  - очередь можно измерить (размер = сколько торгов стоит в ожидании);
 *  - запрос переживает рассинхронизацию пиков;
 *  - это же решение, вынесенное за пределы процесса, = микросервисная архитектура.
 *
 * Цена: round-trip через брокер на каждый запрос (см. LoadTest этой ветки),
 * и зависимость от живости брокера (что если брокер лёг, пока ждём ответ?
 * — таймаут в ask()).
 */
@Service
public class Auction {

    private static final String COMMAND_QUEUE = "auction.cmd";

    private final JmsTemplate jms;
    private final ObjectMapper mapper;

    Auction(JmsTemplate jms, ObjectMapper mapper) {
        this.jms = jms;
        this.mapper = mapper;
    }

    /** Выставить товар на аукцион. */
    public Item addItem(String name, long startPrice) {
        JsonNode result = ask(new Command("addItem", -1, name, startPrice, null, null));
        return mapper.convertValue(result, Item.class);
    }

    /** Снять товар с продажи (вместе со ставками). */
    public void removeItem(long id) {
        ask(new Command("removeItem", id, null, null, null, null));
    }

    /** Список всех товаров. */
    public List<Item> listItems() {
        JsonNode result = ask(new Command("listItems", -1, null, null, null, null));
        return mapper.convertValue(result, new TypeReference<List<Item>>() {
        });
    }

    /** Сделать ставку. */
    public Bid addBid(long itemId, String bidder, long amount) {
        JsonNode result = ask(new Command("addBid", itemId, null, null, bidder, amount));
        return mapper.convertValue(result, Bid.class);
    }

    /** Список ставок на товар. */
    public List<Bid> listBids(long itemId) {
        JsonNode result = ask(new Command("listBids", itemId, null, null, null, null));
        return mapper.convertValue(result, new TypeReference<List<Bid>>() {
        });
    }

    /** Отправить команду в очередь и дождаться ответа (не больше receive-timeout). */
    private JsonNode ask(Command command) {
        try {
            String json = mapper.writeValueAsString(command);
            var replyMessage = jms.sendAndReceive(COMMAND_QUEUE,
                    session -> session.createTextMessage(json));
            if (!(replyMessage instanceof jakarta.jms.TextMessage reply)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Аукцион прислал непонятный ответ");
            }
            Reply envelope = mapper.readValue(reply.getText(), Reply.class);
            if (envelope.error() != null) {
                String[] codeAndReason = envelope.error().split(":", 2);
                throw new ResponseStatusException(
                        HttpStatus.valueOf(Integer.parseInt(codeAndReason[0])), codeAndReason[1]);
            }
            return envelope.result();
        } catch (JmsException | jakarta.jms.JMSException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Брокер сообщений недоступен: " + e.getMessage());
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Сбой JSON на стороне аукциона", e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Сбой при чтении ответа брокера", e);
        }
    }
}
