/*******************************************************************************
 * Copyright (c) 2026 kura-edge contributors.
 * 基于 Eclipse Kura（https://github.com/eclipse-kura/kura，EPL-2.0）代码修改。
 ******************************************************************************/
package com.kura.wires.components.asset;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.kura.wires.model.channel.ChannelFlag;
import com.kura.wires.model.channel.ChannelRecord;
import com.kura.wires.model.type.TypedValue;

/**
 * 相邻两次读的取值去重缓存（{@code emit.on.change=true} 时启用），
 * 对照上游 {@code ValueChangeCache}。
 * <p>
 * 失败记录永远通过并清除缓存；成功记录只有取值变化才通过。
 * </p>
 */
final class ValueChangeCache {

    private final Map<String, TypedValue<?>> cache = new HashMap<>();

    synchronized List<ChannelRecord> filterRecords(final List<ChannelRecord> records) {
        final List<ChannelRecord> result = new ArrayList<>(records.size());
        for (final ChannelRecord record : records) {
            if (shouldEmit(record)) {
                result.add(record);
            }
        }
        return result;
    }

    private boolean shouldEmit(final ChannelRecord record) {
        final String key = record.getChannelName();
        if (RecordFillers.flagOf(record) != ChannelFlag.SUCCESS) {
            this.cache.remove(key);
            return true;
        }
        final TypedValue<?> value = record.getValue();
        if (Objects.equals(this.cache.get(key), value)) {
            return false;
        }
        this.cache.put(key, value);
        return true;
    }
}
