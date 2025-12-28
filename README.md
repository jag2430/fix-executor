# FIX Exchange Executor Simulator

A Spring Boot application that simulates a FIX exchange for testing FIX client applications. It accepts orders via FIX 4.4 protocol and simulates order matching with configurable fill behavior.

## Features

- **FIX 4.4 protocol support** - Full support for NewOrderSingle, OrderCancelRequest, and OrderCancelReplaceRequest
- **Multiple fill modes** - Configure how orders are executed (immediate, partial, delayed, etc.)
- **REST API** - Control execution behavior, view orders, and manually execute trades
- **Simulated market data** - Realistic bid/ask spreads for common symbols
- **Order book tracking** - Full order lifecycle management

## Architecture

```
┌─────────────────┐          ┌─────────────────────────────────────┐
│   FIX Client    │   FIX    │      FIX Exchange Executor          │
│   (Port 8081)   │◄────────►│         (Port 9876)                 │
│                 │  4.4     │                                     │
└─────────────────┘          │  ┌─────────────┐  ┌──────────────┐  │
                             │  │ Order Book  │  │  Execution   │  │
┌─────────────────┐          │  │   Service   │  │   Service    │  │
│   REST Client   │  REST    │  └─────────────┘  └──────────────┘  │
│   (curl/UI)     │◄────────►│                                     │
│                 │  8080    │  ┌─────────────┐                    │
└─────────────────┘          │  │Market Data  │                    │
                             │  │  Service    │                    │
                             │  └─────────────┘                    │
                             └─────────────────────────────────────┘
```

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.6+

### Build

```bash
mvn clean package
```

### Run

```bash
mvn spring-boot:run
```

The executor will:
- Start a FIX acceptor on port **9876**
- Start a REST API on port **8080**
- Default to **IMMEDIATE_FULL** fill mode

## Fill Modes

The executor supports multiple fill modes to test different scenarios:

| Mode | Description |
|------|-------------|
| `IMMEDIATE_FULL` | Fill entire order immediately (default) |
| `IMMEDIATE_PARTIAL` | Fill partial quantity immediately, rest over time |
| `DELAYED` | Wait before filling (configurable delay) |
| `MARKET_SIMULATION` | Simulate realistic market matching with random fills |
| `REJECT_ALL` | Reject all orders (for error handling tests) |
| `MANUAL` | Wait for manual execution via REST API |

### Set Fill Mode

```bash
# Set to immediate partial fills
curl -X POST http://localhost:8080/api/config/mode/IMMEDIATE_PARTIAL

# Set to delayed fills
curl -X POST http://localhost:8080/api/config/mode/DELAYED

# Set to manual mode
curl -X POST http://localhost:8080/api/config/mode/MANUAL
```

## REST API Endpoints

### Configuration

```bash
# Get current configuration
curl http://localhost:8080/api/config

# Update configuration
curl -X PUT http://localhost:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{
    "fillMode": "IMMEDIATE_PARTIAL",
    "partialFillPercentage": 30,
    "delayMs": 2000,
    "rejectProbability": 0.1,
    "enablePartialFills": true,
    "maxPartialFills": 5
  }'

# Quick mode change
curl -X POST http://localhost:8080/api/config/mode/MARKET_SIMULATION
```

### Orders

```bash
# Get all orders
curl http://localhost:8080/api/orders

# Get open orders only
curl "http://localhost:8080/api/orders?openOnly=true"

# Get specific order
curl http://localhost:8080/api/orders/{clOrdId}

# Get order statistics
curl http://localhost:8080/api/orders/stats

# Clear order book
curl -X DELETE http://localhost:8080/api/orders
```

### Manual Execution (when in MANUAL mode)

```bash
# Execute an order (full fill)
curl -X POST http://localhost:8080/api/orders/{clOrdId}/execute \
  -H "Content-Type: application/json" \
  -d '{}'

# Execute partial fill
curl -X POST http://localhost:8080/api/orders/{clOrdId}/execute \
  -H "Content-Type: application/json" \
  -d '{"quantity": 50, "price": 150.25}'

# Reject an order
curl -X POST http://localhost:8080/api/orders/{clOrdId}/reject \
  -H "Content-Type: application/json" \
  -d '{"reason": "Insufficient liquidity"}'
```

### Market Data

```bash
# Get all market data
curl http://localhost:8080/api/market-data

# Get market data for symbol
curl http://localhost:8080/api/market-data/AAPL

# Update market price (for testing)
curl -X POST http://localhost:8080/api/market-data/AAPL \
  -H "Content-Type: application/json" \
  -d '{"price": 175.50}'
```

### Sessions

```bash
# Get connected sessions
curl http://localhost:8080/api/sessions

# Health check
curl http://localhost:8080/api/health
```

## Configuration Options

### ExecutionConfig Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `fillMode` | FillMode | IMMEDIATE_FULL | How orders are filled |
| `partialFillPercentage` | int | 50 | Initial fill % for IMMEDIATE_PARTIAL |
| `delayMs` | long | 1000 | Delay in ms for DELAYED mode |
| `rejectProbability` | double | 0.0 | Probability of random rejection (0.0-1.0) |
| `priceSlippage` | BigDecimal | 0 | Price slippage for market orders |
| `enablePartialFills` | boolean | true | Allow partial fills |
| `minPartialFillQty` | int | 10 | Minimum quantity for partial fills |
| `maxPartialFills` | int | 5 | Maximum partial fills per order |
| `rejectReason` | String | "Order rejected..." | Custom rejection reason |
| `logExecutions` | boolean | true | Log execution details |

## Testing Scenarios

### Scenario 1: Basic Order Flow

```bash
# Start executor in default mode
mvn spring-boot:run

# From client, send order - will be filled immediately
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"symbol":"AAPL","side":"BUY","orderType":"LIMIT","quantity":100,"price":175.00}'
```

### Scenario 2: Partial Fills

```bash
# Configure partial fills
curl -X PUT http://localhost:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{"fillMode":"IMMEDIATE_PARTIAL","partialFillPercentage":25}'

# Send order - will receive multiple partial fills
```

### Scenario 3: Error Handling

```bash
# Configure random rejections
curl -X PUT http://localhost:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{"fillMode":"IMMEDIATE_FULL","rejectProbability":0.3}'

# Send orders - 30% will be randomly rejected
```

### Scenario 4: Manual Trading

```bash
# Set manual mode
curl -X POST http://localhost:8080/api/config/mode/MANUAL

# Send order from client
# Order will sit in book waiting for manual execution

# Manually execute
curl -X POST http://localhost:8080/api/orders/{clOrdId}/execute \
  -H "Content-Type: application/json" \
  -d '{"quantity":50,"price":175.00}'
```

### Scenario 5: Market Simulation

```bash
# Enable market simulation
curl -X POST http://localhost:8080/api/config/mode/MARKET_SIMULATION

# Send limit orders - will be matched based on price levels
# Market orders - will be filled at bid/ask prices
```

## Project Structure

```
fix-executor/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/example/executor/
│   │   │   ├── FixExecutorApplication.java
│   │   │   ├── config/
│   │   │   │   └── FixConfig.java
│   │   │   ├── controller/
│   │   │   │   └── ExecutorController.java
│   │   │   ├── fix/
│   │   │   │   └── ExecutorFixApplication.java
│   │   │   ├── model/
│   │   │   │   ├── ExecutionConfig.java
│   │   │   │   ├── MarketPrice.java
│   │   │   │   └── Order.java
│   │   │   └── service/
│   │   │       ├── ExecutionService.java
│   │   │       ├── MarketDataService.java
│   │   │       └── OrderBookService.java
│   │   └── resources/
│   │       ├── application.yml
│   │       └── quickfix-executor.cfg
```

## Default Market Data

The executor comes pre-configured with market data for common symbols:

| Symbol | Default Price |
|--------|---------------|
| AAPL | $175.00 |
| MSFT | $380.00 |
| GOOGL | $140.00 |
| AMZN | $180.00 |
| TSLA | $250.00 |
| META | $500.00 |
| NVDA | $480.00 |
| JPM | $195.00 |
| V | $275.00 |
| JNJ | $155.00 |

Unknown symbols get a randomly generated price between $10-$500.

## Integration with FIX Client

This executor is designed to work with the FIX Client application:

1. **Start the executor first** (listens on port 9876)
2. **Start the FIX client** (connects to port 9876)
3. **Send orders via client REST API** (port 8081)
4. **Monitor/control via executor REST API** (port 8080)

```bash
# Terminal 1: Start executor
cd fix-executor
mvn spring-boot:run

# Terminal 2: Start client
cd fix-client
mvn spring-boot:run

# Terminal 3: Send orders
curl -X POST http://localhost:8081/api/orders \
  -H "Content-Type: application/json" \
  -d '{"symbol":"AAPL","side":"BUY","orderType":"LIMIT","quantity":100,"price":175.00}'
```

## License

MIT
