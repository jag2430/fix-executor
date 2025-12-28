package com.example.executor.fix;

import com.example.executor.model.Order;
import com.example.executor.service.ExecutionService;
import com.example.executor.service.OrderBookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.NewOrderSingle;
import quickfix.fix44.OrderCancelReplaceRequest;
import quickfix.fix44.OrderCancelRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * FIX Application handler for the exchange executor.
 * Receives orders from clients and processes them through the execution service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutorFixApplication implements Application {
    
    private final ExecutionService executionService;
    private final OrderBookService orderBookService;
    
    @Override
    public void onCreate(SessionID sessionId) {
        log.info("Session created: {}", sessionId);
    }
    
    @Override
    public void onLogon(SessionID sessionId) {
        log.info("Client logged on: {}", sessionId);
    }
    
    @Override
    public void onLogout(SessionID sessionId) {
        log.info("Client logged out: {}", sessionId);
    }
    
    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            if (MsgType.LOGON.equals(msgType)) {
                log.debug("Sending logon response to {}", sessionId);
            }
        } catch (FieldNotFound e) {
            log.error("Error in toAdmin", e);
        }
    }
    
    @Override
    public void fromAdmin(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            log.debug("Received admin message: {} from {}", msgType, sessionId);
        } catch (FieldNotFound e) {
            log.error("Error in fromAdmin", e);
        }
    }
    
    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        log.debug("Sending application message to {}: {}", sessionId, message);
    }
    
    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        
        log.debug("Received application message from {}: {}", sessionId, message);
        
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            
            switch (msgType) {
                case MsgType.ORDER_SINGLE:
                    handleNewOrder((NewOrderSingle) message, sessionId);
                    break;
                case MsgType.ORDER_CANCEL_REQUEST:
                    handleCancelRequest((OrderCancelRequest) message, sessionId);
                    break;
                case MsgType.ORDER_CANCEL_REPLACE_REQUEST:
                    handleReplaceRequest((OrderCancelReplaceRequest) message, sessionId);
                    break;
                default:
                    log.warn("Received unhandled message type: {}", msgType);
            }
        } catch (Exception e) {
            log.error("Error processing message", e);
        }
    }
    
    /**
     * Handle new order single
     */
    private void handleNewOrder(NewOrderSingle nos, SessionID sessionId) throws FieldNotFound {
        String clOrdId = nos.getClOrdID().getValue();
        String symbol = nos.getSymbol().getValue();
        char sideChar = nos.getSide().getValue();
        char ordTypeChar = nos.getOrdType().getValue();
        double orderQty = nos.getOrderQty().getValue();
        
        BigDecimal price = null;
        if (ordTypeChar == OrdType.LIMIT && nos.isSetPrice()) {
            price = BigDecimal.valueOf(nos.getPrice().getValue());
        }
        
        Order order = Order.builder()
            .clOrdId(clOrdId)
            .symbol(symbol)
            .side(sideChar == Side.BUY ? "BUY" : "SELL")
            .orderType(ordTypeChar == OrdType.MARKET ? "MARKET" : "LIMIT")
            .quantity((int) orderQty)
            .filledQuantity(0)
            .leavesQuantity((int) orderQty)
            .price(price)
            .avgPrice(BigDecimal.ZERO)
            .status("PENDING")
            .senderCompId(sessionId.getSenderCompID())
            .targetCompId(sessionId.getTargetCompID())
            .timestamp(LocalDateTime.now())
            .build();
        
        log.info("Received new order: {} {} {} {} @ {}",
            clOrdId, order.getSide(), order.getQuantity(), symbol, price);
        
        // Add to order book
        orderBookService.addOrder(order);
        
        // Execute the order
        executionService.executeOrder(order, sessionId);
    }
    
    /**
     * Handle order cancel request
     */
    private void handleCancelRequest(OrderCancelRequest ocr, SessionID sessionId) throws FieldNotFound {
        String origClOrdId = ocr.getOrigClOrdID().getValue();
        String clOrdId = ocr.getClOrdID().getValue();
        
        log.info("Received cancel request: {} for original order {}", clOrdId, origClOrdId);
        
        executionService.cancelOrder(origClOrdId, clOrdId, sessionId);
    }
    
    /**
     * Handle order cancel/replace request (amend)
     */
    private void handleReplaceRequest(OrderCancelReplaceRequest ocrr, SessionID sessionId) throws FieldNotFound {
        String origClOrdId = ocrr.getOrigClOrdID().getValue();
        String clOrdId = ocrr.getClOrdID().getValue();
        String symbol = ocrr.getSymbol().getValue();
        char sideChar = ocrr.getSide().getValue();
        char ordTypeChar = ocrr.getOrdType().getValue();
        double orderQty = ocrr.getOrderQty().getValue();
        
        BigDecimal price = null;
        if (ocrr.isSetPrice()) {
            price = BigDecimal.valueOf(ocrr.getPrice().getValue());
        }
        
        Order newOrder = Order.builder()
            .clOrdId(clOrdId)
            .symbol(symbol)
            .side(sideChar == Side.BUY ? "BUY" : "SELL")
            .orderType(ordTypeChar == OrdType.MARKET ? "MARKET" : "LIMIT")
            .quantity((int) orderQty)
            .filledQuantity(0)
            .leavesQuantity((int) orderQty)
            .price(price)
            .avgPrice(BigDecimal.ZERO)
            .status("PENDING")
            .senderCompId(sessionId.getSenderCompID())
            .targetCompId(sessionId.getTargetCompID())
            .timestamp(LocalDateTime.now())
            .build();
        
        log.info("Received replace request: {} -> {} newQty={} newPrice={}",
            origClOrdId, clOrdId, orderQty, price);
        
        executionService.replaceOrder(origClOrdId, newOrder, sessionId);
    }
}
