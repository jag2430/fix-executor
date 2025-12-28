package com.example.executor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {
    private String clOrdId;
    private String orderId;
    private String symbol;
    private String side;          // BUY or SELL
    private String orderType;     // MARKET or LIMIT
    private int quantity;
    private int filledQuantity;
    private int leavesQuantity;
    private BigDecimal price;
    private BigDecimal avgPrice;
    private String status;        // NEW, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED
    private String senderCompId;
    private String targetCompId;
    private LocalDateTime timestamp;
    private LocalDateTime lastUpdateTime;
    
    public boolean isOpen() {
        return status != null && 
            !status.equals("FILLED") && 
            !status.equals("CANCELLED") && 
            !status.equals("REJECTED");
    }
    
    public boolean isBuy() {
        return "BUY".equalsIgnoreCase(side);
    }
    
    public boolean isSell() {
        return "SELL".equalsIgnoreCase(side);
    }
}
