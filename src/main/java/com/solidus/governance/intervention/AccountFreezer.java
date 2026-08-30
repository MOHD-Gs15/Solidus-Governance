package com.solidus.governance.intervention;

import com.solidus.governance.SolidusGovernanceMod;
import com.solidus.governance.audit.AuditDatabase;
import com.solidus.governance.engine.GovernanceEngine;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AccountFreezer {
    private final Set<UUID> frozenCache = ConcurrentHashMap.newKeySet();
    private GovernanceEngine engine;

    public void setEngine(GovernanceEngine engine) {
        this.engine = engine;
    }

    public void initialize() {
        if (this.engine == null) {
            return;
        }
        List<AuditDatabase.FreezeRecord> freezes = this.engine.getAuditDatabase().getActiveFreezes();
        for (AuditDatabase.FreezeRecord freeze : freezes) {
            if (freeze.isExpired()) continue;
            this.frozenCache.add(freeze.uuid);
        }
        SolidusGovernanceMod.LOGGER.info("Account Freezer initialized. {} accounts currently frozen.", (Object)this.frozenCache.size());
    }

    public void freeze(UUID uuid, String reason, UUID frozenBy, int durationMinutes) {
        Long expiresAt;
        this.frozenCache.add(uuid);
        Long l = expiresAt = durationMinutes > 0 ? Long.valueOf(System.currentTimeMillis() + (long)durationMinutes * 60000L) : null;
        if (this.engine != null) {
            this.engine.getAuditDatabase().recordFreeze(uuid, reason, frozenBy, expiresAt);
            this.engine.getAuditLogger().logFreeze(frozenBy, "Admin", uuid, uuid.toString().substring(0, 8), reason, durationMinutes);
        }
    }

    public void unfreeze(UUID uuid, UUID unfrozenBy) {
        this.frozenCache.remove(uuid);
        if (this.engine != null) {
            this.engine.getAuditDatabase().unfreezeAccount(uuid);
            this.engine.getAuditLogger().logUnfreeze(unfrozenBy, "Admin", uuid, uuid.toString().substring(0, 8));
        }
    }

    public boolean isFrozen(UUID uuid) {
        return this.frozenCache.contains(uuid);
    }

    public int getFrozenCount() {
        return this.frozenCache.size();
    }

    public void checkExpirations() {
        if (this.engine == null) {
            return;
        }
        List<AuditDatabase.FreezeRecord> freezes = this.engine.getAuditDatabase().getActiveFreezes();
        for (AuditDatabase.FreezeRecord freeze : freezes) {
            if (!freeze.isExpired()) continue;
            this.frozenCache.remove(freeze.uuid);
            this.engine.getAuditDatabase().unfreezeAccount(freeze.uuid);
            SolidusGovernanceMod.LOGGER.info("Account {} unfrozen (freeze expired).", (Object)freeze.uuid);
        }
    }
}
