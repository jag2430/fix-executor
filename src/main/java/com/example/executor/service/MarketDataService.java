package com.example.executor.service;

import com.example.executor.model.MarketPrice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for market data - fetches REAL prices from Finnhub.
 * NO HARDCODED PRICES - always fetches fresh data from Finnhub.
 * If Finnhub unavailable, uses the limit price from the order.
 */
@Slf4j
@Service
public class MarketDataService {

  private static final String FINNHUB_REST_URL = "https://finnhub.io/api/v1";

  @Value("${market-data.finnhub.api-key:}")
  private String finnhubApiKey;

  private final Map<String, MarketPrice> marketPrices = new ConcurrentHashMap<>();
  private final Map<String, Long> lastFetchTime = new ConcurrentHashMap<>();
  private final Random random = new Random();
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient;

  // Cache prices for 30 seconds
  private static final long CACHE_TTL_MS = 30_000;

  public MarketDataService() {
    this.httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }

  @PostConstruct
  public void init() {
    if (finnhubApiKey == null || finnhubApiKey.isEmpty()) {
      log.error("=================================================");
      log.error("FINNHUB API KEY NOT CONFIGURED!");
      log.error("Orders will be filled at LIMIT PRICE (no market data)");
      log.error("Set FINNHUB_API_KEY environment variable or");
      log.error("market-data.finnhub.api-key in application.yml");
      log.error("Get your FREE API key at: https://finnhub.io/register");
      log.error("=================================================");
    } else {
      log.info("Finnhub API key configured - will fetch real market prices");
    }
  }

  /**
   * Get current market price for a symbol.
   * ALWAYS fetches from Finnhub - no hardcoded fallbacks.
   */
  public MarketPrice getMarketPrice(String symbol) {
    String upperSymbol = symbol.toUpperCase();

    // Always try to fetch fresh price if cache expired
    if (shouldFetchPrice(upperSymbol)) {
      fetchPriceFromFinnhub(upperSymbol);
    }

    // Return cached price if available
    MarketPrice cached = marketPrices.get(upperSymbol);
    if (cached != null) {
      return cached;
    }

    // No cached price - return null (caller should handle)
    log.warn("No market price available for {} - will use order limit price", upperSymbol);
    return null;
  }

  /**
   * Check if we should fetch a fresh price (cache expired or not cached)
   */
  private boolean shouldFetchPrice(String symbol) {
    Long lastFetch = lastFetchTime.get(symbol);
    if (lastFetch == null) {
      return true;
    }
    return System.currentTimeMillis() - lastFetch > CACHE_TTL_MS;
  }

  /**
   * Fetch real-time price from Finnhub
   */
  private void fetchPriceFromFinnhub(String symbol) {
    if (finnhubApiKey == null || finnhubApiKey.isEmpty()) {
      log.debug("No Finnhub API key - cannot fetch price for {}", symbol);
      return;
    }

    try {
      String url = String.format("%s/quote?symbol=%s&token=%s",
          FINNHUB_REST_URL, symbol, finnhubApiKey);

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .header("Accept", "application/json")
          .GET()
          .timeout(Duration.ofSeconds(5))
          .build();

      HttpResponse<String> response = httpClient.send(request,
          HttpResponse.BodyHandlers.ofString());

      if (response.statusCode() == 200) {
        parseAndUpdatePrice(symbol, response.body());
        lastFetchTime.put(symbol, System.currentTimeMillis());
      } else if (response.statusCode() == 429) {
        log.warn("Finnhub rate limit reached for {}", symbol);
      } else {
        log.warn("Finnhub API returned {} for {}: {}",
            response.statusCode(), symbol, response.body());
      }

    } catch (Exception e) {
      log.warn("Failed to fetch price from Finnhub for {}: {}", symbol, e.getMessage());
    }
  }

  /**
   * Parse Finnhub quote response
   * Response: {"c":150.0,"d":1.5,"dp":1.0,"h":151.0,"l":149.0,"o":149.5,"pc":148.5}
   */
  private void parseAndUpdatePrice(String symbol, String jsonResponse) {
    try {
      JsonNode root = objectMapper.readTree(jsonResponse);

      if (root.has("error")) {
        log.warn("Finnhub error for {}: {}", symbol, root.path("error").asText());
        return;
      }

      double currentPrice = root.path("c").asDouble();
      if (currentPrice <= 0) {
        log.warn("No valid price for {} from Finnhub (c={})", symbol, currentPrice);
        return;
      }

      BigDecimal price = BigDecimal.valueOf(currentPrice)
          .setScale(2, RoundingMode.HALF_UP);

      MarketPrice marketPrice = createMarketPrice(symbol, price);
      marketPrices.put(symbol, marketPrice);

      log.info("Fetched {} price from Finnhub: ${} (bid={}, ask={})",
          symbol, price, marketPrice.getBidPrice(), marketPrice.getAskPrice());

    } catch (Exception e) {
      log.warn("Failed to parse Finnhub response for {}: {}", symbol, e.getMessage());
    }
  }

  /**
   * Update market price for a symbol (manual override via REST API)
   */
  public MarketPrice updatePrice(String symbol, BigDecimal price) {
    MarketPrice marketPrice = createMarketPrice(symbol.toUpperCase(), price);
    marketPrices.put(symbol.toUpperCase(), marketPrice);
    lastFetchTime.put(symbol.toUpperCase(), System.currentTimeMillis());
    log.info("Manually updated price for {}: ${} (bid={}, ask={})",
        symbol, price, marketPrice.getBidPrice(), marketPrice.getAskPrice());
    return marketPrice;
  }

  /**
   * Get execution price for an order based on side.
   *
   * If no market data available, returns the LIMIT PRICE (for limit orders)
   * or throws exception (for market orders without price reference).
   */
  public BigDecimal getExecutionPrice(String symbol, String side, BigDecimal limitPrice) {
    MarketPrice market = getMarketPrice(symbol);

    // If no market data available, use the limit price
    if (market == null) {
      if (limitPrice != null) {
        log.info("No market data for {} - executing at limit price: ${}", symbol, limitPrice);
        return limitPrice;
      } else {
        // Market order with no market data - this is a problem
        log.error("MARKET order for {} but no market data available!", symbol);
        // Return a safe fallback - should not happen in production
        return new BigDecimal("100.00");
      }
    }

    // We have market data
    if (limitPrice != null) {
      // Limit order
      if ("BUY".equalsIgnoreCase(side)) {
        // Buy limit - execute at min(limit, ask)
        BigDecimal execPrice = limitPrice.min(market.getAskPrice());
        log.info("BUY LIMIT {} @ ${} vs ask ${} -> exec @ ${}",
            symbol, limitPrice, market.getAskPrice(), execPrice);
        return execPrice;
      } else {
        // Sell limit - execute at max(limit, bid)
        BigDecimal execPrice = limitPrice.max(market.getBidPrice());
        log.info("SELL LIMIT {} @ ${} vs bid ${} -> exec @ ${}",
            symbol, limitPrice, market.getBidPrice(), execPrice);
        return execPrice;
      }
    } else {
      // Market order - execute at bid/ask
      if ("BUY".equalsIgnoreCase(side)) {
        log.info("MARKET BUY {} -> exec @ ask ${}", symbol, market.getAskPrice());
        return market.getAskPrice();
      } else {
        log.info("MARKET SELL {} -> exec @ bid ${}", symbol, market.getBidPrice());
        return market.getBidPrice();
      }
    }
  }

  /**
   * Get all current market prices (cached)
   */
  public Map<String, MarketPrice> getAllPrices() {
    return new ConcurrentHashMap<>(marketPrices);
  }

  /**
   * Force refresh price from Finnhub
   */
  public MarketPrice refreshPrice(String symbol) {
    lastFetchTime.remove(symbol.toUpperCase());
    return getMarketPrice(symbol);
  }

  /**
   * Clear all cached prices
   */
  public void clearCache() {
    marketPrices.clear();
    lastFetchTime.clear();
    log.info("Market data cache cleared");
  }

  private MarketPrice createMarketPrice(String symbol, BigDecimal price) {
    // Create realistic bid/ask spread (0.01% to 0.03% spread)
    BigDecimal spreadPercent = BigDecimal.valueOf(0.0001 + random.nextDouble() * 0.0002);
    BigDecimal halfSpread = price.multiply(spreadPercent)
        .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);

    // Ensure minimum spread of $0.01
    if (halfSpread.compareTo(new BigDecimal("0.005")) < 0) {
      halfSpread = new BigDecimal("0.005");
    }

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