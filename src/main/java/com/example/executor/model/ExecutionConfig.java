package com.example.executor.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Configuration for how the executor should fill orders.
 * Can be modified at runtime via REST API.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionConfig {
    
    /**
     * Fill mode determines how orders are executed
     */
    public enum FillMode {
        IMMEDIATE_FULL,      // Fill entire order immediately
        IMMEDIATE_PARTIAL,   // Fill partial quantity immediately, rest later
        DELAYED,             // Wait before filling
        MARKET_SIMULATION,   // Simulate realistic market matching
        REJECT_ALL,          // Reject all orders (for testing)
        MANUAL               // Wait for manual execution via API
    }
    
    @Builder.Default
    private FillMode fillMode = FillMode.IMMEDIATE_FULL;
    
    @Builder.Default
    private int partialFillPercentage = 50;  // For IMMEDIATE_PARTIAL mode
    
    @Builder.Default
    private long delayMs = 1000;  // For DELAYED mode
    
    @Builder.Default
    private double rejectProbability = 0.0;  // Probability of random rejection (0.0-1.0)
    
    @Builder.Default
    private BigDecimal priceSlippage = BigDecimal.ZERO;  // Price slippage for market orders
    
    @Builder.Default
    private boolean enablePartialFills = true;  // Allow partial fills
    
    @Builder.Default
    private int minPartialFillQty = 10;  // Minimum quantity for partial fills
    
    @Builder.Default
    private int maxPartialFills = 5;  // Maximum number of partial fills per order
    
    @Builder.Default
    private String rejectReason = "Order rejected by exchange";  // Custom reject reason
    
    @Builder.Default
    private boolean logExecutions = true;  // Log execution details
}
