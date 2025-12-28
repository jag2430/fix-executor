package com.example.executor.service;

import com.example.executor.model.MarketPrice;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for simulating market data.
 * Provides realistic bid/ask prices for symbols.
 */
@Slf4j
@Service
public class MarketDataService {
    
    private final Map<String, MarketPrice> marketPrices = new ConcurrentHashMap<>();
    private final Random random = new Random();
    
    // Default prices for common symbols
    private static final Map<String, BigDecimal> DEFAULT_PRICES = Map.of(
        "AAPL", new BigDecimal("175.00"),
        "MSFT", new BigDecimal("380.00"),
        "GOOGL", new BigDecimal("140.00"),
        "AMZN", new BigDecimal("180.00"),
        "TSLA", new BigDecimal("250.00"),
        "META", new BigDecimal("500.00"),
        "NVDA", new BigDecimal("480.00"),
        "JPM", new BigDecimal("195.00"),
        "V", new BigDecimal("275.00"),
        "JNJ", new BigDecimal("155.00")
    );
    
    @PostConstruct
    public void init() {
        // Initialize market data for default symbols
        DEFAULT_PRICES.forEach((symbol, price) -> {
            updatePrice(symbol, price);
        });
        log.info("Market data service initialized with {} symbols", marketPrices.size());
    }
    
    /**
     * Get current market price for a symbol.
     * If not found, generates a random price.
     */
    public MarketPrice getMarketPrice(String symbol) {
        return marketPrices.computeIfAbsent(symbol, s -> {
            // Generate random price between 10 and 500
            BigDecimal price = BigDecimal.valueOf(10 + random.nextDouble() * 490)
                .setScale(2, RoundingMode.HALF_UP);
            return createMarketPrice(s, price);
        });
    }
    
    /**
     * Update market price for a symbol
     */
    public MarketPrice updatePrice(String symbol, BigDecimal price) {
        MarketPrice marketPrice = createMarketPrice(symbol, price);
        marketPrices.put(symbol, marketPrice);
        log.debug("Updated price for {}: bid={} ask={}", 
            symbol, marketPrice.getBidPrice(), marketPrice.getAskPrice());
        return marketPrice;
    }
    
    /**
     * Get execution price for an order based on side
     */
    public BigDecimal getExecutionPrice(String symbol, String side, BigDecimal limitPrice) {
        MarketPrice market = getMarketPrice(symbol);
        
        if (limitPrice != null) {
            // Limit order - execute at limit price if marketable
            if ("BUY".equalsIgnoreCase(side)) {
                // Buy limit - execute at min(limit, ask)
                return limitPrice.min(market.getAskPrice());
            } else {
                // Sell limit - execute at max(limit, bid)
                return limitPrice.max(market.getBidPrice());
            }
        } else {
            // Market order - execute at bid/ask
            if ("BUY".equalsIgnoreCase(side)) {
                return market.getAskPrice();  // Buy at ask
            } else {
                return market.getBidPrice();  // Sell at bid
            }
        }
    }
    
    /**
     * Simulate price movement (can be called periodically)
     */
    public void simulatePriceMovement(String symbol) {
        MarketPrice current = marketPrices.get(symbol);
        if (current != null) {
            // Random price change between -1% and +1%
            double changePercent = (random.nextDouble() - 0.5) * 0.02;
            BigDecimal newPrice = current.getLastPrice()
                .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(changePercent)))
                .setScale(2, RoundingMode.HALF_UP);
            updatePrice(symbol, newPrice);
        }
    }
    
    /**
     * Get all current market prices
     */
    public Map<String, MarketPrice> getAllPrices() {
        return new ConcurrentHashMap<>(marketPrices);
    }
    
    private MarketPrice createMarketPrice(String symbol, BigDecimal price) {
        // Create realistic bid/ask spread (0.01% to 0.1% spread)
        BigDecimal spreadPercent = BigDecimal.valueOf(0.0001 + random.nextDouble() * 0.0009);
        BigDecimal halfSpread = price.multiply(spreadPercent).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        
        BigDecimal bidPrice = price.subtract(halfSpread).setScale(2, RoundingMode.HALF_UP);
        BigDecimal askPrice = price.add(halfSpread).setScale(2, RoundingMode.HALF_UP);
        
        return MarketPrice.builder()
            .symbol(symbol)
            .lastPrice(price)
            .bidPrice(bidPrice)
            .askPrice(askPrice)
            .bidSize(100 + random.nextInt(900))
            .askSize(100 + random.nextInt(900))
            .volume(random.nextLong(1000000))
            .lastUpdateTime(LocalDateTime.now())
            .build();
    }
}
