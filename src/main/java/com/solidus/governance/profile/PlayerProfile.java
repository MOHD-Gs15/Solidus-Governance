package com.solidus.governance.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayerProfile {
    private final UUID playerUuid;
    private final String playerName;
    private double balance;
    private int rank;
    private int totalPlayers;
    private boolean frozen;
    private boolean suspicious;
    private String suspiciousReason;
    private double income7d;
    private double expenses7d;
    private double netChange7d;
    private double netChangePercent7d;
    private double taxPaid7d;
    private int transactionCount7d;
    private final List<String> flags = new ArrayList<String>();

    public PlayerProfile(UUID playerUuid, String playerName) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
    }

    public String formatProfile() {
        StringBuilder sb = new StringBuilder();
        sb.append("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Economy Profile: ").append(this.playerName).append(" \u2550\u2550\u2550\u2550\u2550\u2550\u2550\n");
        sb.append("  Balance: ").append(String.format("%,.2f", this.balance)).append("\n");
        sb.append("  Rank: #").append(this.rank).append(" of ").append(this.totalPlayers).append("\n");
        sb.append("  Account Status: ").append(this.frozen ? "FROZEN" : "ACTIVE").append("\n");
        sb.append("  Suspicious: ").append(this.suspicious ? "YES" : "NO").append("\n");
        if (this.suspicious && this.suspiciousReason != null) {
            sb.append("  Suspicious Reason: ").append(this.suspiciousReason).append("\n");
        }
        sb.append("  Frozen: ").append(this.frozen ? "YES" : "NO").append("\n");
        sb.append("\n  \u2500\u2500 Last 7 Days \u2500\u2500\n");
        sb.append("  Income: +").append(String.format("%,.2f", this.income7d)).append("\n");
        sb.append("  Expenses: -").append(String.format("%,.2f", this.expenses7d)).append("\n");
        String netSign = this.netChange7d >= 0.0 ? "+" : "";
        sb.append("  Net Change: ").append(netSign).append(String.format("%,.2f", this.netChange7d));
        sb.append(" (").append(String.format("%+.1f%%", this.netChangePercent7d)).append(")\n");
        sb.append("  Tax Paid: ").append(String.format("%,.2f", this.taxPaid7d)).append("\n");
        sb.append("  Transactions: ").append(this.transactionCount7d).append("\n");
        if (!this.flags.isEmpty()) {
            sb.append("\n  \u2500\u2500 Flags \u2500\u2500\n");
            for (String flag : this.flags) {
                sb.append("  \u26a0 ").append(flag).append("\n");
            }
        }
        sb.append("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
        return sb.toString();
    }

    public String formatFreeProfile() {
        StringBuilder sb = new StringBuilder();
        sb.append("\u2550\u2550\u2550\u2550\u2550\u2550\u2550 Economy Profile: ").append(this.playerName).append(" \u2550\u2550\u2550\u2550\u2550\u2550\u2550\n");
        sb.append("  Balance: ").append(String.format("%,.2f", this.balance)).append("\n");
        sb.append("  Rank: #").append(this.rank).append(" of ").append(this.totalPlayers).append("\n");
        sb.append("  Account Status: ").append(this.frozen ? "FROZEN" : "ACTIVE").append("\n");
        sb.append("  Suspicious: ").append(this.suspicious ? "YES" : "NO").append("\n");
        sb.append("  Frozen: ").append(this.frozen ? "YES" : "NO").append("\n");
        sb.append("\n  Upgrade to premium for weekly stats and flags.");
        sb.append("\n\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
        return sb.toString();
    }

    public UUID getPlayerUuid() {
        return this.playerUuid;
    }

    public String getPlayerName() {
        return this.playerName;
    }

    public double getBalance() {
        return this.balance;
    }

    public void setBalance(double balance) {
        this.balance = balance;
    }

    public int getRank() {
        return this.rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public int getTotalPlayers() {
        return this.totalPlayers;
    }

    public void setTotalPlayers(int totalPlayers) {
        this.totalPlayers = totalPlayers;
    }

    public boolean isFrozen() {
        return this.frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public boolean isSuspicious() {
        return this.suspicious;
    }

    public void setSuspicious(boolean suspicious) {
        this.suspicious = suspicious;
    }

    public String getSuspiciousReason() {
        return this.suspiciousReason;
    }

    public void setSuspiciousReason(String suspiciousReason) {
        this.suspiciousReason = suspiciousReason;
    }

    public double getIncome7d() {
        return this.income7d;
    }

    public void setIncome7d(double income7d) {
        this.income7d = income7d;
    }

    public double getExpenses7d() {
        return this.expenses7d;
    }

    public void setExpenses7d(double expenses7d) {
        this.expenses7d = expenses7d;
    }

    public double getNetChange7d() {
        return this.netChange7d;
    }

    public void setNetChange7d(double netChange7d) {
        this.netChange7d = netChange7d;
    }

    public double getNetChangePercent7d() {
        return this.netChangePercent7d;
    }

    public void setNetChangePercent7d(double netChangePercent7d) {
        this.netChangePercent7d = netChangePercent7d;
    }

    public double getTaxPaid7d() {
        return this.taxPaid7d;
    }

    public void setTaxPaid7d(double taxPaid7d) {
        this.taxPaid7d = taxPaid7d;
    }

    public int getTransactionCount7d() {
        return this.transactionCount7d;
    }

    public void setTransactionCount7d(int transactionCount7d) {
        this.transactionCount7d = transactionCount7d;
    }

    public List<String> getFlags() {
        return this.flags;
    }

    public void addFlag(String flag) {
        this.flags.add(flag);
    }
}
