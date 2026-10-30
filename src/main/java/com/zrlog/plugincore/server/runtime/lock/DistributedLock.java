package com.zrlog.plugincore.server.runtime.lock;

import com.hibegin.common.dao.ResultValueConvertUtils;
import com.zrlog.plugin.common.LoggerUtil;
import com.zrlog.plugincore.server.dao.WebSiteDAO;

import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.logging.Logger;

public class DistributedLock implements Lock {

    public static final String LOCK_PREFIX = "distributed_lock_";

    private static final Logger LOGGER = LoggerUtil.getLogger(DistributedLock.class);
    private static final long DEFAULT_LEASE_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final String CREATED_AT_PREFIX = "Created at ";
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private final String lockKey;
    private final String rawLockKey;
    private final String owner;
    private final long leaseMillis;
    private String lockValue;

    public DistributedLock(String lockKey) {
        this(lockKey, DEFAULT_LEASE_MILLIS);
    }

    public DistributedLock(String lockKey, long leaseMillis) {
        if (leaseMillis < 1) throw new IllegalArgumentException("lease must be positive");
        this.leaseMillis = leaseMillis;
        this.lockKey = LOCK_PREFIX + lockKey;
        this.rawLockKey = lockKey;
        this.owner = UUID.randomUUID().toString();
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        long totalWaitTime = unit.toMillis(time);
        if (totalWaitTime > 600 * 1000) {
            throw new IllegalArgumentException("waitTime too long");
        }
        if (totalWaitTime <= 0) {
            throw new IllegalArgumentException("waitTime must be a positive number");
        }
        int seek = 200;
        while (true) {
            if (tryLock()) {
                return true;
            }
            totalWaitTime -= seek;
            if (totalWaitTime <= 0) {
                return false;
            }
            Thread.sleep(seek);
        }
    }

    @Override
    public void lock() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void lockInterruptibly() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean tryLock() {
        if (lockValue != null) {
            return false;
        }
        long now = System.currentTimeMillis();
        String value = lockValue(now + leaseMillis);
        try {
            clearExpiredLock(now);
            if (insertLock(value)) {
                lockValue = value;
                return true;
            }
            clearExpiredLock(now);
            if (insertLock(value)) {
                lockValue = value;
                return true;
            }
            return false;
        } catch (SQLException e) {
            if (!isDuplicateKey(e)) {
                LOGGER.warning("tryLock " + rawLockKey + " error " + e.getMessage());
            }
            return false;
        }
    }

    @Override
    public void unlock() {
        String value = lockValue;
        lockValue = null;
        if (value == null) {
            return;
        }
        try {
            deleteLockValue(value);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException();
    }

    private boolean insertLock(String value) throws SQLException {
        return new WebSiteDAO()
                .set("name", lockKey)
                .set("remark", CREATED_AT_PREFIX + ResultValueConvertUtils.formatDate(new Date(), DATE_FORMAT))
                .set("value", value)
                .save();
    }

    private void clearExpiredLock(long now) throws SQLException {
        Map<String, Object> lockRow = queryLockRow();
        if (lockRow == null) {
            return;
        }
        Object value = lockRow.get("value");
        Long expiresAt = parseExpiresAt(value == null ? null : value.toString());
        if (expiresAt == null) {
            expiresAt = parseLegacyExpiresAt(lockRow.get("remark"));
        }
        if (expiresAt != null && expiresAt <= now) {
            deleteLockValue(value == null ? null : value.toString());
        }
    }

    private Map<String, Object> queryLockRow() throws SQLException {
        List<Map<String, Object>> rows = new WebSiteDAO()
                .queryListWithParams("select name,remark,value from website where name=?", lockKey);
        if (rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    private void deleteLockValue(String value) throws SQLException {
        if (value == null) {
            return;
        }
        new WebSiteDAO().set("name", lockKey).set("value", value).delete();
    }

    private String lockValue(long expiresAt) {
        return owner + "|" + expiresAt;
    }

    private Long parseExpiresAt(String value) {
        if (value == null) {
            return null;
        }
        int index = value.lastIndexOf('|');
        if (index < 0 || index == value.length() - 1) {
            return null;
        }
        try {
            return Long.parseLong(value.substring(index + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long parseLegacyExpiresAt(Object remark) {
        if (!(remark instanceof String)) {
            return null;
        }
        String text = (String) remark;
        if (!text.startsWith(CREATED_AT_PREFIX)) {
            return null;
        }
        try {
            return new SimpleDateFormat(DATE_FORMAT)
                    .parse(text.substring(CREATED_AT_PREFIX.length()))
                    .getTime() + DEFAULT_LEASE_MILLIS;
        } catch (ParseException e) {
            return null;
        }
    }

    private boolean isDuplicateKey(SQLException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        return lowerMessage.contains("duplicate")
                || lowerMessage.contains("unique")
                || lowerMessage.contains("constraint");
    }
}
