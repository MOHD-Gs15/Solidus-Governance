package com.solidus.governance.simulation;

public record SimulationState(boolean running, boolean paused, long totalTicks, int currentDelayMs, long avgTickNanos, long lastTickNanos, double estimatedTps, String throttlingLevel, int adaptiveSampleSize, int activeAccountCount) {
}
