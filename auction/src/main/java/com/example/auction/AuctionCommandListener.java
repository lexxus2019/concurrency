package com.example.auction;

import java.io.IOException;

import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Единственный поток, который трогает состояние аукциона, — потребитель
 * очереди auction.cmd. @JmsListener при concurrency=1 работает в одном треде,
 * команды обрабатываются по одной, в порядке поступления.
 *
 * Возвращённое значение JMS отправляет в очередь ответа (temporary queue
 * отправителя — так устроен JmsTemplate.sendAndReceive), поэтому здесь
 * никаких забот о корреляции запроса и ответа.
 */
@Component
public class AuctionCommandListener {

    private final AuctionState state;
    private final ObjectMapper mapper;

    AuctionCommandListener(AuctionState state, ObjectMapper mapper) {
        this.state = state;
        this.mapper = mapper;
    }

    @JmsListener(destination = "auction.cmd")
    public String handle(String json) throws IOException {
        Command command = mapper.readValue(json, Command.class);
        try {
            Object result = switch (command.action()) {
                case "addItem" -> state.addItem(command.name(), command.startPrice());
                case "removeItem" -> {
                    state.removeItem(command.itemId());
                    yield null;
                }
                case "listItems" -> state.listItems();
                case "listBids" -> state.listBids(command.itemId());
                case "addBid" -> state.addBid(command.itemId(), command.bidder(), command.amount());
                default -> throw new IllegalStateException("Неизвестная команда: " + command.action());
            };
            return mapper.writeValueAsString(Reply.ok(mapper.valueToTree(result)));
        } catch (ResponseStatusException e) {
            return mapper.writeValueAsString(
                    Reply.error(e.getStatusCode().value(), e.getReason() == null ? "error" : e.getReason()));
        }
    }
}
