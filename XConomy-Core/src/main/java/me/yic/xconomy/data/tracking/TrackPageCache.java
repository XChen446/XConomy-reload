/*
 *  This file (TrackPageCache.java) is a part of project XConomy
 *  Copyright (C) YiC and contributors
 *
 *  This program is free software: you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the
 *  Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 *
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package me.yic.xconomy.data.tracking;

import me.yic.xconomy.AdapterManager;
import me.yic.xconomy.XConomyLoad;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tab 补全用的页数缓存。
 * 页数查询需要走数据库，不能在主线程执行，所以采用"先返回缓存值，后台异步刷新"的策略：
 * - 第一次请求某玩家/类型的页数时，缓存为空，返回 1（保守值），同时触发异步刷新；
 * - 之后 tab 补全直接读缓存，数值准确。
 */
public class TrackPageCache {

    // key: "<uuid>:income" 或 "<uuid>:expense"
    private static final Map<String, Integer> pageCache = new ConcurrentHashMap<>();

    // 防止同一 key 同时触发多次异步刷新
    private static final Map<String, Boolean> pending = new ConcurrentHashMap<>();

    /**
     * 获取指定玩家/类型的最大页数。
     * 若缓存未命中，触发异步刷新并返回 1。
     */
    public static int getMaxPage(UUID playerUUID, String type) {
        String key = playerUUID.toString() + ":" + type;
        Integer cached = pageCache.get(key);
        if (cached != null) {
            return cached;
        }
        // 缓存未命中，异步刷新
        refreshAsync(playerUUID, type, key);
        return 1;
    }

    /**
     * 主动刷新缓存（在 /xconomy track 命令执行完毕后调用，保持缓存最新）
     */
    public static void refresh(UUID playerUUID, String type) {
        String key = playerUUID.toString() + ":" + type;
        refreshAsync(playerUUID, type, key);
    }

    private static void refreshAsync(UUID playerUUID, String type, String key) {
        if (pending.putIfAbsent(key, Boolean.TRUE) != null) {
            return; // 已有刷新任务在跑
        }
        AdapterManager.runTaskAsynchronously(() -> {
            try {
                int count = type.equals("income")
                        ? TransactionQuery.countIncomeRecords(playerUUID)
                        : TransactionQuery.countExpenseRecords(playerUUID);
                int pageSize = Math.max(XConomyLoad.Config.TRACKING_RECORDS_PER_PAGE, 1);
                int maxPage = (int) Math.ceil((double) count / pageSize);
                pageCache.put(key, Math.max(maxPage, 1));
            } finally {
                pending.remove(key);
            }
        });
    }

    /**
     * 清除指定玩家的页数缓存（玩家离线或数据删除时调用）
     */
    public static void invalidate(UUID playerUUID) {
        pageCache.remove(playerUUID.toString() + ":income");
        pageCache.remove(playerUUID.toString() + ":expense");
        pending.remove(playerUUID.toString() + ":income");
        pending.remove(playerUUID.toString() + ":expense");
    }

    public static void clearAll() {
        pageCache.clear();
        pending.clear();
    }
}
