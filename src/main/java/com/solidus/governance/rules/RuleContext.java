package com.solidus.governance.rules;

public class RuleContext {
    private final double avgBalance;
    private final double totalMoneySupply;
    private final int onlinePlayerCount;
    private final double giniCoefficient;
    private final double transactionVolume24h;
    private final double inflationRate;

    public RuleContext(double avgBalance, double totalMoneySupply, int onlinePlayerCount, double giniCoefficient, double transactionVolume24h, double inflationRate) {
        this.avgBalance = avgBalance;
        this.totalMoneySupply = totalMoneySupply;
        this.onlinePlayerCount = onlinePlayerCount;
        this.giniCoefficient = giniCoefficient;
        this.transactionVolume24h = transactionVolume24h;
        this.inflationRate = inflationRate;
    }

    public double getAvgBalance() {
        return this.avgBalance;
    }

    public double getTotalMoneySupply() {
        return this.totalMoneySupply;
    }

    public int getOnlinePlayerCount() {
        return this.onlinePlayerCount;
    }

    public double getGiniCoefficient() {
        return this.giniCoefficient;
    }

    public double getTransactionVolume24h() {
        return this.transactionVolume24h;
    }

    public double getInflationRate() {
        return this.inflationRate;
    }
}
