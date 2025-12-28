package com.example.executor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * FIX Exchange Executor Simulator
 * 
 * This application simulates a FIX exchange that accepts orders from clients
 * and simulates order matching and execution with configurable fill behavior.
 * 
 * Features:
 * - FIX 4.4 protocol support
 * - Configurable fill modes (immediate, partial, delayed, reject)
 * - Order book simulation
 * - REST API for controlling execution behavior
 * - Support for market and limit orders
 * - Order cancel and amend handling
 */
@SpringBootApplication
public class FixExecutorApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(FixExecutorApplication.class, args);
    }
}
