package com.example.executor.controller;

import com.example.executor.model.ExecutionConfig;
import com.example.executor.model.MarketPrice;
import com.example.executor.model.Order;
import com.example.executor.service.ExecutionService;
import com.example.executor.service.MarketDataService;
import com.example.executor.service.OrderBookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SocketAcceptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST controller for managing the FIX executor.
 * Provides endpoints for configuring execution behavior, viewing orders,
 * and manually executing trades.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ExecutorController {
    
    private final ExecutionService executionService;
    private final OrderBookService orderBookService;
    private final MarketDataService marketDataService;
    private final SocketAcceptor acceptor;
    
    // =========================================================================
    // Configuration Endpoints
    // =========================================================================
    
    @GetMapping("/config")
    public ResponseEntity<ExecutionConfig> getConfig() {
        return ResponseEntity.ok(executionService.getConfig());
    }
    
    @PutMapping("/config")
    public ResponseEntity<ExecutionConfig> updateConfig(@RequestBody ExecutionConfig config) {
        executionService.updateConfig(config);
        log.info("Configuration updated: {}", config);
        return ResponseEntity.ok(executionService.getConfig());
    }
    
    @PostMapping("/config/mode/{mode}")
    public ResponseEntity<ExecutionConfig> setFillMode(@PathVariable String mode) {
        ExecutionConfig config = executionService.getConfig();
        try {
            ExecutionConfig.FillMode fillMode = ExecutionConfig.FillMode.valueOf(mode.toUpperCase());
            config.setFillMode(fillMode);
            executionService.updateConfig(config);
            log.info("Fill mode set to: {}", fillMode);
            return ResponseEntity.ok(config);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }
    
    // =========================================================================
    // Order Book Endpoints
    // =========================================================================
    
    @GetMapping("/orders")
    public ResponseEntity<List<Order>> getAllOrders(
            @RequestParam(required = false, defaultValue = "false") boolean openOnly) {
        if (openOnly) {
            return ResponseEntity.ok(orderBookService.getOpenOrders());
        }
        return ResponseEntity.ok(orderBookService.getAllOrders());
    }
    
    @GetMapping("/orders/{clOrdId}")
    public ResponseEntity<?> getOrder(@PathVariable String clOrdId) {
        Order order = orderBookService.getOrderByClOrdId(clOrdId);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(order);
    }
    
    @GetMapping("/orders/stats")
    public ResponseEntity<Map<String, Long>> getOrderStats() {
        return ResponseEntity.ok(orderBookService.getOrderStats());
    }
    
    @DeleteMapping("/orders")
    public ResponseEntity<Map<String, String>> clearOrders() {
        orderBookService.clearAllOrders();
        return ResponseEntity.ok(Map.of("message", "Order book cleared"));
    }
    
    // =========================================================================
    // Manual Execution Endpoints
    // =========================================================================
    
    @PostMapping("/orders/{clOrdId}/execute")
    public ResponseEntity<?> manualExecute(
            @PathVariable String clOrdId,
            @RequestBody Map<String, Object> request) {
        
        Order order = orderBookService.getOrderByClOrdId(clOrdId);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        
        if (!order.isOpen()) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Order is not open",
                "status", order.getStatus()
            ));
        }
        
        int quantity = request.containsKey("quantity") 
            ? ((Number) request.get("quantity")).intValue()
            : order.getLeavesQuantity();
        
        BigDecimal price = request.containsKey("price")
            ? new BigDecimal(request.get("price").toString())
            : marketDataService.getExecutionPrice(order.getSymbol(), order.getSide(), order.getPrice());
        
        // Find the session for this order
        SessionID sessionId = findSessionForOrder(order);
        if (sessionId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "No active session found for order"
            ));
        }
        
        executionService.manualExecute(clOrdId, quantity, price, sessionId);
        
        return ResponseEntity.ok(Map.of(
            "message", "Execution sent",
            "clOrdId", clOrdId,
            "quantity", quantity,
            "price", price
        ));
    }
    
    @PostMapping("/orders/{clOrdId}/reject")
    public ResponseEntity<?> rejectOrder(
            @PathVariable String clOrdId,
            @RequestBody(required = false) Map<String, String> request) {
        
        Order order = orderBookService.getOrderByClOrdId(clOrdId);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        
        String reason = request != null && request.containsKey("reason") 
            ? request.get("reason") 
            : "Order rejected by exchange";
        
        SessionID sessionId = findSessionForOrder(order);
        if (sessionId == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "No active session found for order"
            ));
        }
        
        executionService.rejectOrder(order, sessionId, reason);
        
        return ResponseEntity.ok(Map.of(
            "message", "Order rejected",
            "clOrdId", clOrdId,
            "reason", reason
        ));
    }
    
    // =========================================================================
    // Market Data Endpoints
    // =========================================================================
    
    @GetMapping("/market-data")
    public ResponseEntity<Map<String, MarketPrice>> getAllMarketData() {
        return ResponseEntity.ok(marketDataService.getAllPrices());
    }
    
    @GetMapping("/market-data/{symbol}")
    public ResponseEntity<MarketPrice> getMarketData(@PathVariable String symbol) {
        return ResponseEntity.ok(marketDataService.getMarketPrice(symbol.toUpperCase()));
    }
    
    @PostMapping("/market-data/{symbol}")
    public ResponseEntity<MarketPrice> updateMarketData(
            @PathVariable String symbol,
            @RequestBody Map<String, BigDecimal> request) {
        BigDecimal price = request.get("price");
        if (price == null) {
            return ResponseEntity.badRequest().build();
        }
        MarketPrice updated = marketDataService.updatePrice(symbol.toUpperCase(), price);
        return ResponseEntity.ok(updated);
    }
    
    // =========================================================================
    // Session Endpoints
    // =========================================================================
    
    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> getSessions() {
        List<Map<String, Object>> sessions = new ArrayList<>();
        
        for (SessionID sessionId : acceptor.getSessions()) {
            Session session = Session.lookupSession(sessionId);
            sessions.add(Map.of(
                "sessionId", sessionId.toString(),
                "senderCompId", sessionId.getSenderCompID(),
                "targetCompId", sessionId.getTargetCompID(),
                "loggedOn", session != null && session.isLoggedOn()
            ));
        }
        
        return ResponseEntity.ok(sessions);
    }
    
    // =========================================================================
    // Health Check
    // =========================================================================
    
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        long loggedOnCount = acceptor.getSessions().stream()
            .filter(sid -> {
                Session s = Session.lookupSession(sid);
                return s != null && s.isLoggedOn();
            })
            .count();
        
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "activeSessions", loggedOnCount,
            "totalOrders", orderBookService.getOrderCount(),
            "openOrders", orderBookService.getOpenOrderCount(),
            "fillMode", executionService.getConfig().getFillMode()
        ));
    }
    
    // =========================================================================
    // Helper Methods
    // =========================================================================
    
    private SessionID findSessionForOrder(Order order) {
        // Find session matching the order's sender/target
        for (SessionID sessionId : acceptor.getSessions()) {
            Session session = Session.lookupSession(sessionId);
            if (session != null && session.isLoggedOn()) {
                // In acceptor, sender/target are reversed from client perspective
                if (sessionId.getTargetCompID().equals(order.getSenderCompId())) {
                    return sessionId;
                }
            }
        }
        
        // Fallback to first logged on session
        for (SessionID sessionId : acceptor.getSessions()) {
            Session session = Session.lookupSession(sessionId);
            if (session != null && session.isLoggedOn()) {
                return sessionId;
            }
        }
        
        return null;
    }
}
