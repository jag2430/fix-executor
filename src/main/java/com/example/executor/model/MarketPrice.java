package com.example.executor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Simulated market price for a symbol.
 * Used for market order pricing and P&L calculations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketPrice {
    private String symbol;
    private BigDecimal bidPrice;
    private BigDecimal askPrice;
    private BigDecimal lastPrice;
    private int bidSize;
    private int askSize;
    private long volume;
    private LocalDateTime lastUpdateTime;
    
    public BigDecimal getMidPrice() {
        if (bidPrice != null && askPrice != null) {
            return bidPrice.add(askPrice).divide(BigDecimal.valueOf(2));
        }
        return lastPrice;
    }
    
    public BigDecimal getSpread() {
        if (bidPrice != null && askPrice != null) {
            return askPrice.subtract(bidPrice);
        }
        return BigDecimal.ZERO;
    }
}
