package com.example.executor.service;

import com.example.executor.model.ExecutionConfig;
import com.example.executor.model.ExecutionConfig.FillMode;
import com.example.executor.model.Order;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import quickfix.*;
import quickfix.field.*;
import quickfix.fix44.ExecutionReport;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Service responsible for executing orders and sending execution reports.
 * Supports multiple fill modes for testing different scenarios.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionService {
    
    private final OrderBookService orderBookService;
    private final MarketDataService marketDataService;
    
    @Getter
    private ExecutionConfig config = ExecutionConfig.builder().build();
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final Random random = new Random();
    
    // Track partial fill progress
    private final ConcurrentHashMap<String, Integer> partialFillCount = new ConcurrentHashMap<>();
    
    @PostConstruct
    public void init() {
        log.info("Execution service initialized with mode: {}", config.getFillMode());
    }
    
    /**
     * Update execution configuration
     */
    public void updateConfig(ExecutionConfig newConfig) {
        this.config = newConfig;
        log.info("Execution config updated: mode={}, partialPct={}, delay={}ms",
            config.getFillMode(), config.getPartialFillPercentage(), config.getDelayMs());
    }
    
    /**
     * Process a new order and execute according to configured mode
     */
    public void executeOrder(Order order, SessionID sessionId) {
        log.info("Processing order: {} mode={}", order.getClOrdId(), config.getFillMode());
        
        // Check for random rejection
        if (config.getRejectProbability() > 0 && random.nextDouble() < config.getRejectProbability()) {
            rejectOrder(order, sessionId, config.getRejectReason());
            return;
        }
        
        // Send acknowledgement first
        sendNewAck(order, sessionId);
        
        // Execute based on mode
        switch (config.getFillMode()) {
            case IMMEDIATE_FULL:
                executeImmediateFull(order, sessionId);
                break;
            case IMMEDIATE_PARTIAL:
                executeImmediatePartial(order, sessionId);
                break;
            case DELAYED:
                executeDelayed(order, sessionId);
                break;
            case MARKET_SIMULATION:
                executeMarketSimulation(order, sessionId);
                break;
            case REJECT_ALL:
                rejectOrder(order, sessionId, config.getRejectReason());
                break;
            case MANUAL:
                // Do nothing - wait for manual execution via API
                log.info("Order {} queued for manual execution", order.getClOrdId());
                break;
        }
    }
    
    /**
     * Manually execute an order (for MANUAL mode or testing)
     */
    public void manualExecute(String clOrdId, int quantity, BigDecimal price, SessionID sessionId) {
        Order order = orderBookService.getOrderByClOrdId(clOrdId);
        if (order == null || !order.isOpen()) {
            log.warn("Order not found or not open: {}", clOrdId);
            return;
        }
        
        int fillQty = Math.min(quantity, order.getLeavesQuantity());
        executeFill(order, fillQty, price, sessionId);
    }
    
    /**
     * Execute immediate full fill
     */
    private void executeImmediateFull(Order order, SessionID sessionId) {
        BigDecimal execPrice = getExecutionPrice(order);
        executeFill(order, order.getLeavesQuantity(), execPrice, sessionId);
    }
    
    /**
     * Execute immediate partial fill, then schedule remaining fills
     */
    private void executeImmediatePartial(Order order, SessionID sessionId) {
        int totalQty = order.getLeavesQuantity();
        int partialQty = (int) (totalQty * config.getPartialFillPercentage() / 100.0);
        partialQty = Math.max(partialQty, config.getMinPartialFillQty());
        partialQty = Math.min(partialQty, totalQty);
        
        BigDecimal execPrice = getExecutionPrice(order);
        executeFill(order, partialQty, execPrice, sessionId);
        
        // Schedule remaining fills
        if (order.getLeavesQuantity() > 0) {
            scheduleRemainingFills(order, sessionId);
        }
    }
    
    /**
     * Execute with delay
     */
    private void executeDelayed(Order order, SessionID sessionId) {
        scheduler.schedule(() -> {
            try {
                BigDecimal execPrice = getExecutionPrice(order);
                executeFill(order, order.getLeavesQuantity(), execPrice, sessionId);
            } catch (Exception e) {
                log.error("Error in delayed execution", e);
            }
        }, config.getDelayMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    /**
     * Simulate realistic market matching with random partial fills
     */
    private void executeMarketSimulation(Order order, SessionID sessionId) {
        // Simulate price discovery for limit orders
        if ("LIMIT".equals(order.getOrderType())) {
            BigDecimal marketPrice = marketDataService.getExecutionPrice(
                order.getSymbol(), order.getSide(), null);
            
            // Check if limit is marketable
            boolean marketable = order.isBuy() 
                ? order.getPrice().compareTo(marketPrice) >= 0
                : order.getPrice().compareTo(marketPrice) <= 0;
            
            if (!marketable) {
                // Order rests in book - simulate random partial fills over time
                scheduleMarketFills(order, sessionId);
                return;
            }
        }
        
        // Marketable - execute with random partial fills
        scheduleRandomFills(order, sessionId);
    }
    
    /**
     * Schedule remaining fills for partial execution
     */
    private void scheduleRemainingFills(Order order, SessionID sessionId) {
        int fillCount = partialFillCount.computeIfAbsent(order.getClOrdId(), k -> 1);
        
        if (fillCount >= config.getMaxPartialFills() || order.getLeavesQuantity() == 0) {
            // Fill the rest
            if (order.getLeavesQuantity() > 0) {
                scheduler.schedule(() -> {
                    BigDecimal execPrice = getExecutionPrice(order);
                    executeFill(order, order.getLeavesQuantity(), execPrice, sessionId);
                }, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            partialFillCount.remove(order.getClOrdId());
            return;
        }
        
        // Schedule next partial fill
        scheduler.schedule(() -> {
            try {
                Order currentOrder = orderBookService.getOrderByClOrdId(order.getClOrdId());
                if (currentOrder == null || !currentOrder.isOpen()) return;
                
                int remaining = currentOrder.getLeavesQuantity();
                int nextFillQty = Math.min(
                    (int) (order.getQuantity() * 0.25),  // 25% chunks
                    remaining
                );
                nextFillQty = Math.max(nextFillQty, config.getMinPartialFillQty());
                nextFillQty = Math.min(nextFillQty, remaining);
                
                BigDecimal execPrice = getExecutionPrice(currentOrder);
                executeFill(currentOrder, nextFillQty, execPrice, sessionId);
                
                partialFillCount.compute(order.getClOrdId(), (k, v) -> v == null ? 2 : v + 1);
                
                // Schedule more if needed
                if (currentOrder.getLeavesQuantity() > 0) {
                    scheduleRemainingFills(currentOrder, sessionId);
                }
            } catch (Exception e) {
                log.error("Error in partial fill scheduling", e);
            }
        }, 500 + random.nextInt(1000), java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    /**
     * Schedule random fills for market simulation
     */
    private void scheduleRandomFills(Order order, SessionID sessionId) {
        int totalQty = order.getLeavesQuantity();
        int numFills = 1 + random.nextInt(4); // 1-4 fills
        
        int remaining = totalQty;
        long delay = 100;
        
        for (int i = 0; i < numFills && remaining > 0; i++) {
            final int fillQty;
            if (i == numFills - 1) {
                fillQty = remaining;
            } else {
                fillQty = Math.max(config.getMinPartialFillQty(), 
                    random.nextInt(remaining / 2) + 1);
            }
            final int finalRemaining = remaining;
            
            scheduler.schedule(() -> {
                try {
                    Order currentOrder = orderBookService.getOrderByClOrdId(order.getClOrdId());
                    if (currentOrder != null && currentOrder.isOpen()) {
                        BigDecimal execPrice = getExecutionPrice(currentOrder);
                        // Add small price variation
                        BigDecimal priceVar = execPrice.multiply(
                            BigDecimal.valueOf((random.nextDouble() - 0.5) * 0.001));
                        execPrice = execPrice.add(priceVar).setScale(2, RoundingMode.HALF_UP);
                        
                        executeFill(currentOrder, Math.min(fillQty, currentOrder.getLeavesQuantity()), 
                            execPrice, sessionId);
                    }
                } catch (Exception e) {
                    log.error("Error in market simulation fill", e);
                }
            }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
            
            remaining -= fillQty;
            delay += 100 + random.nextInt(500);
        }
    }
    
    /**
     * Schedule fills for non-marketable limit orders
     */
    private void scheduleMarketFills(Order order, SessionID sessionId) {
        // Simulate order sitting in book, getting random fills
        scheduler.schedule(() -> {
            try {
                Order currentOrder = orderBookService.getOrderByClOrdId(order.getClOrdId());
                if (currentOrder != null && currentOrder.isOpen()) {
                    // 50% chance of getting a fill each check
                    if (random.nextBoolean()) {
                        int fillQty = Math.min(
                            config.getMinPartialFillQty() + random.nextInt(currentOrder.getLeavesQuantity()),
                            currentOrder.getLeavesQuantity()
                        );
                        executeFill(currentOrder, fillQty, currentOrder.getPrice(), sessionId);
                    }
                    
                    // Schedule next check if still open
                    if (currentOrder.isOpen() && currentOrder.getLeavesQuantity() > 0) {
                        scheduleMarketFills(currentOrder, sessionId);
                    }
                }
            } catch (Exception e) {
                log.error("Error in market fill scheduling", e);
            }
        }, 1000 + random.nextInt(3000), java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    
    /**
     * Execute a fill and send execution report
     */
    public void executeFill(Order order, int fillQty, BigDecimal fillPrice, SessionID sessionId) {
        if (fillQty <= 0 || order == null || !order.isOpen()) return;
        
        fillQty = Math.min(fillQty, order.getLeavesQuantity());
        
        int newFilledQty = order.getFilledQuantity() + fillQty;
        int newLeavesQty = order.getQuantity() - newFilledQty;
        
        // Calculate average price
        BigDecimal oldTotal = order.getAvgPrice() != null 
            ? order.getAvgPrice().multiply(BigDecimal.valueOf(order.getFilledQuantity()))
            : BigDecimal.ZERO;
        BigDecimal newTotal = oldTotal.add(fillPrice.multiply(BigDecimal.valueOf(fillQty)));
        BigDecimal avgPrice = newTotal.divide(BigDecimal.valueOf(newFilledQty), 4, RoundingMode.HALF_UP);
        
        String status = newLeavesQty == 0 ? "FILLED" : "PARTIALLY_FILLED";
        char execType = newLeavesQty == 0 ? ExecType.FILL : ExecType.PARTIAL_FILL;
        
        // Update order in book
        orderBookService.updateOrder(order.getClOrdId(), status, newFilledQty, newLeavesQty, avgPrice);
        
        // Send execution report
        sendExecutionReport(order, execType, status, fillQty, fillPrice, newFilledQty, newLeavesQty, avgPrice, sessionId);
        
        if (config.isLogExecutions()) {
            log.info("FILL: {} {} {} {} @ {} (filled={}, leaves={})",
                order.getSide(), fillQty, order.getSymbol(), order.getClOrdId(),
                fillPrice, newFilledQty, newLeavesQty);
        }
    }
    
    /**
     * Send new order acknowledgement
     */
    private void sendNewAck(Order order, SessionID sessionId) {
        sendExecutionReport(order, ExecType.NEW, "NEW", 0, BigDecimal.ZERO, 
            0, order.getQuantity(), BigDecimal.ZERO, sessionId);
    }
    
    /**
     * Reject an order
     */
    public void rejectOrder(Order order, SessionID sessionId, String reason) {
        orderBookService.updateOrder(order.getClOrdId(), "REJECTED", 0, 0, null);
        sendRejectReport(order, reason, sessionId);
        log.info("Order rejected: {} reason={}", order.getClOrdId(), reason);
    }
    
    /**
     * Process order cancel request
     */
    public void cancelOrder(String origClOrdId, String cancelClOrdId, SessionID sessionId) {
        Order order = orderBookService.getOrderByClOrdId(origClOrdId);
        if (order == null) {
            log.warn("Cancel request for unknown order: {}", origClOrdId);
            return;
        }
        
        if (!order.isOpen()) {
            sendCancelReject(origClOrdId, cancelClOrdId, "Order not open", sessionId);
            return;
        }
        
        orderBookService.cancelOrder(origClOrdId);
        sendCancelAck(order, cancelClOrdId, sessionId);
    }
    
    /**
     * Process order replace request
     */
    public void replaceOrder(String origClOrdId, Order newOrder, SessionID sessionId) {
        Order oldOrder = orderBookService.getOrderByClOrdId(origClOrdId);
        if (oldOrder == null) {
            log.warn("Replace request for unknown order: {}", origClOrdId);
            return;
        }
        
        if (!oldOrder.isOpen()) {
            sendCancelReject(origClOrdId, newOrder.getClOrdId(), "Order not open", sessionId);
            return;
        }
        
        // Update new order with old order's filled quantity
        newOrder.setFilledQuantity(oldOrder.getFilledQuantity());
        newOrder.setLeavesQuantity(newOrder.getQuantity() - oldOrder.getFilledQuantity());
        newOrder.setAvgPrice(oldOrder.getAvgPrice());
        newOrder.setStatus("NEW");
        
        orderBookService.replaceOrder(origClOrdId, newOrder);
        sendReplaceAck(oldOrder, newOrder, sessionId);
        
        // Continue execution if there's remaining quantity
        if (newOrder.getLeavesQuantity() > 0 && config.getFillMode() != FillMode.MANUAL) {
            executeOrder(newOrder, sessionId);
        }
    }
    
    /**
     * Get execution price for an order
     */
    private BigDecimal getExecutionPrice(Order order) {
        BigDecimal price = marketDataService.getExecutionPrice(
            order.getSymbol(), order.getSide(), order.getPrice());
        
        // Apply slippage for market orders
        if ("MARKET".equals(order.getOrderType()) && config.getPriceSlippage().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal slippage = order.isBuy() 
                ? config.getPriceSlippage() 
                : config.getPriceSlippage().negate();
            price = price.add(slippage);
        }
        
        return price.setScale(2, RoundingMode.HALF_UP);
    }
    
    /**
     * Send execution report
     */
    private void sendExecutionReport(Order order, char execType, String ordStatus,
                                     int lastQty, BigDecimal lastPx,
                                     int cumQty, int leavesQty, BigDecimal avgPx,
                                     SessionID sessionId) {
        try {
            ExecutionReport report = new ExecutionReport(
                new OrderID(order.getOrderId()),
                new ExecID(orderBookService.generateExecId()),
                new ExecType(execType),
                new OrdStatus(getOrdStatusChar(ordStatus)),
                new Side(order.isBuy() ? Side.BUY : Side.SELL),
                new LeavesQty(leavesQty),
                new CumQty(cumQty),
                new AvgPx(avgPx.doubleValue())
            );
            
            report.set(new ClOrdID(order.getClOrdId()));
            report.set(new Symbol(order.getSymbol()));
            report.set(new OrderQty(order.getQuantity()));
            
            if (lastQty > 0) {
                report.set(new LastQty(lastQty));
                report.set(new LastPx(lastPx.doubleValue()));
            }
            
            if (order.getPrice() != null) {
                report.set(new Price(order.getPrice().doubleValue()));
            }
            
            report.set(new TransactTime(LocalDateTime.now()));
            
            Session.sendToTarget(report, sessionId);
            
        } catch (SessionNotFound e) {
            log.error("Session not found when sending execution report", e);
        }
    }
    
    /**
     * Send reject execution report
     */
    private void sendRejectReport(Order order, String reason, SessionID sessionId) {
        try {
            ExecutionReport report = new ExecutionReport(
                new OrderID(order.getOrderId() != null ? order.getOrderId() : "NONE"),
                new ExecID(orderBookService.generateExecId()),
                new ExecType(ExecType.REJECTED),
                new OrdStatus(OrdStatus.REJECTED),
                new Side(order.isBuy() ? Side.BUY : Side.SELL),
                new LeavesQty(0),
                new CumQty(0),
                new AvgPx(0)
            );
            
            report.set(new ClOrdID(order.getClOrdId()));
            report.set(new Symbol(order.getSymbol()));
            report.set(new OrdRejReason(OrdRejReason.OTHER));
            report.set(new Text(reason));
            report.set(new TransactTime(LocalDateTime.now()));
            
            Session.sendToTarget(report, sessionId);
            
        } catch (SessionNotFound e) {
            log.error("Session not found when sending reject report", e);
        }
    }
    
    /**
     * Send cancel acknowledgement
     */
    private void sendCancelAck(Order order, String cancelClOrdId, SessionID sessionId) {
        try {
            ExecutionReport report = new ExecutionReport(
                new OrderID(order.getOrderId()),
                new ExecID(orderBookService.generateExecId()),
                new ExecType(ExecType.CANCELED),
                new OrdStatus(OrdStatus.CANCELED),
                new Side(order.isBuy() ? Side.BUY : Side.SELL),
                new LeavesQty(0),
                new CumQty(order.getFilledQuantity()),
                new AvgPx(order.getAvgPrice() != null ? order.getAvgPrice().doubleValue() : 0)
            );
            
            report.set(new ClOrdID(cancelClOrdId));
            report.set(new OrigClOrdID(order.getClOrdId()));
            report.set(new Symbol(order.getSymbol()));
            report.set(new TransactTime(LocalDateTime.now()));
            
            Session.sendToTarget(report, sessionId);
            
        } catch (SessionNotFound e) {
            log.error("Session not found when sending cancel ack", e);
        }
    }
    
    /**
     * Send replace acknowledgement
     */
    private void sendReplaceAck(Order oldOrder, Order newOrder, SessionID sessionId) {
        try {
            ExecutionReport report = new ExecutionReport(
                new OrderID(newOrder.getOrderId()),
                new ExecID(orderBookService.generateExecId()),
                new ExecType(ExecType.REPLACED),
                new OrdStatus(OrdStatus.NEW),
                new Side(newOrder.isBuy() ? Side.BUY : Side.SELL),
                new LeavesQty(newOrder.getLeavesQuantity()),
                new CumQty(newOrder.getFilledQuantity()),
                new AvgPx(newOrder.getAvgPrice() != null ? newOrder.getAvgPrice().doubleValue() : 0)
            );
            
            report.set(new ClOrdID(newOrder.getClOrdId()));
            report.set(new OrigClOrdID(oldOrder.getClOrdId()));
            report.set(new Symbol(newOrder.getSymbol()));
            report.set(new OrderQty(newOrder.getQuantity()));
            if (newOrder.getPrice() != null) {
                report.set(new Price(newOrder.getPrice().doubleValue()));
            }
            report.set(new TransactTime(LocalDateTime.now()));
            
            Session.sendToTarget(report, sessionId);
            
        } catch (SessionNotFound e) {
            log.error("Session not found when sending replace ack", e);
        }
    }
    
    /**
     * Send cancel reject
     */
    private void sendCancelReject(String origClOrdId, String clOrdId, String reason, SessionID sessionId) {
        try {
            quickfix.fix44.OrderCancelReject reject = new quickfix.fix44.OrderCancelReject(
                new OrderID("UNKNOWN"),
                new ClOrdID(clOrdId),
                new OrigClOrdID(origClOrdId),
                new OrdStatus(OrdStatus.REJECTED),
                new CxlRejResponseTo(CxlRejResponseTo.ORDER_CANCEL_REQUEST)
            );
            
            reject.set(new CxlRejReason(CxlRejReason.OTHER));
            reject.set(new Text(reason));
            reject.set(new TransactTime(LocalDateTime.now()));
            
            Session.sendToTarget(reject, sessionId);
            
        } catch (SessionNotFound e) {
            log.error("Session not found when sending cancel reject", e);
        }
    }
    
    private char getOrdStatusChar(String status) {
        return switch (status) {
            case "NEW" -> OrdStatus.NEW;
            case "PARTIALLY_FILLED" -> OrdStatus.PARTIALLY_FILLED;
            case "FILLED" -> OrdStatus.FILLED;
            case "CANCELLED" -> OrdStatus.CANCELED;
            case "REJECTED" -> OrdStatus.REJECTED;
            case "PENDING_CANCEL" -> OrdStatus.PENDING_CANCEL;
            case "PENDING_REPLACE" -> OrdStatus.PENDING_REPLACE;
            default -> OrdStatus.NEW;
        };
    }
}
