package com.example.executor.service;

import com.example.executor.model.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Service for managing the order book.
 * Tracks all orders received by the exchange.
 */
@Slf4j
@Service
public class OrderBookService {
    
    private final Map<String, Order> ordersByClOrdId = new ConcurrentHashMap<>();
    private final Map<String, Order> ordersByOrderId = new ConcurrentHashMap<>();
    private final Map<String, List<Order>> ordersBySymbol = new ConcurrentHashMap<>();
    
    private final AtomicLong orderIdGenerator = new AtomicLong(1);
    private final AtomicLong execIdGenerator = new AtomicLong(1);
    
    /**
     * Add a new order to the book
     */
    public Order addOrder(Order order) {
        // Generate order ID if not set
        if (order.getOrderId() == null) {
            order.setOrderId(generateOrderId());
        }
        if (order.getTimestamp() == null) {
            order.setTimestamp(LocalDateTime.now());
        }
        order.setLastUpdateTime(LocalDateTime.now());
        
        ordersByClOrdId.put(order.getClOrdId(), order);
        ordersByOrderId.put(order.getOrderId(), order);
        
        ordersBySymbol
            .computeIfAbsent(order.getSymbol(), k -> Collections.synchronizedList(new ArrayList<>()))
            .add(order);
        
        log.info("Order added to book: {} {} {} {} @ {}",
            order.getClOrdId(), order.getSide(), order.getQuantity(),
            order.getSymbol(), order.getPrice());
        
        return order;
    }
    
    /**
     * Get order by client order ID
     */
    public Order getOrderByClOrdId(String clOrdId) {
        return ordersByClOrdId.get(clOrdId);
    }
    
    /**
     * Get order by exchange order ID
     */
    public Order getOrderByOrderId(String orderId) {
        return ordersByOrderId.get(orderId);
    }
    
    /**
     * Update order status and quantities
     */
    public Order updateOrder(String clOrdId, String status, int filledQty, int leavesQty, BigDecimal avgPrice) {
        Order order = ordersByClOrdId.get(clOrdId);
        if (order != null) {
            order.setStatus(status);
            order.setFilledQuantity(filledQty);
            order.setLeavesQuantity(leavesQty);
            if (avgPrice != null) {
                order.setAvgPrice(avgPrice);
            }
            order.setLastUpdateTime(LocalDateTime.now());
        }
        return order;
    }
    
    /**
     * Cancel an order
     */
    public Order cancelOrder(String clOrdId) {
        Order order = ordersByClOrdId.get(clOrdId);
        if (order != null && order.isOpen()) {
            order.setStatus("CANCELLED");
            order.setLeavesQuantity(0);
            order.setLastUpdateTime(LocalDateTime.now());
            log.info("Order cancelled: {}", clOrdId);
        }
        return order;
    }
    
    /**
     * Replace an order (cancel old, create new)
     */
    public Order replaceOrder(String origClOrdId, Order newOrder) {
        Order oldOrder = ordersByClOrdId.get(origClOrdId);
        if (oldOrder != null) {
            oldOrder.setStatus("REPLACED");
            oldOrder.setLastUpdateTime(LocalDateTime.now());
        }
        
        // Add new order
        newOrder.setOrderId(generateOrderId());
        return addOrder(newOrder);
    }
    
    /**
     * Get all open orders
     */
    public List<Order> getOpenOrders() {
        return ordersByClOrdId.values().stream()
            .filter(Order::isOpen)
            .collect(Collectors.toList());
    }
    
    /**
     * Get all open orders for a symbol
     */
    public List<Order> getOpenOrdersForSymbol(String symbol) {
        List<Order> symbolOrders = ordersBySymbol.get(symbol);
        if (symbolOrders == null) {
            return Collections.emptyList();
        }
        return symbolOrders.stream()
            .filter(Order::isOpen)
            .collect(Collectors.toList());
    }
    
    /**
     * Get all orders (for monitoring)
     */
    public List<Order> getAllOrders() {
        return new ArrayList<>(ordersByClOrdId.values());
    }
    
    /**
     * Get order counts by status
     */
    public Map<String, Long> getOrderStats() {
        return ordersByClOrdId.values().stream()
            .collect(Collectors.groupingBy(Order::getStatus, Collectors.counting()));
    }
    
    /**
     * Clear all orders (for testing)
     */
    public void clearAllOrders() {
        ordersByClOrdId.clear();
        ordersByOrderId.clear();
        ordersBySymbol.clear();
        log.info("Order book cleared");
    }
    
    /**
     * Generate unique order ID
     */
    public String generateOrderId() {
        return "ORD" + String.format("%08d", orderIdGenerator.getAndIncrement());
    }
    
    /**
     * Generate unique execution ID
     */
    public String generateExecId() {
        return "EXEC" + String.format("%08d", execIdGenerator.getAndIncrement());
    }
    
    /**
     * Get order count
     */
    public int getOrderCount() {
        return ordersByClOrdId.size();
    }
    
    /**
     * Get open order count
     */
    public int getOpenOrderCount() {
        return (int) ordersByClOrdId.values().stream()
            .filter(Order::isOpen)
            .count();
    }
}
