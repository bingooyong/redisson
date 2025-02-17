/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.redisson.client.codec.Codec;
import org.redisson.client.codec.ScoredCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.ScoredEntry;
import org.redisson.client.protocol.decoder.ListScanResult;
import org.redisson.command.CommandAsyncExecutor;
import org.redisson.core.RScoredSortedSet;

import io.netty.util.concurrent.Future;

public class RedissonScoredSortedSet<V> extends RedissonExpirable implements RScoredSortedSet<V> {

    public RedissonScoredSortedSet(CommandAsyncExecutor commandExecutor, String name) {
        super(commandExecutor, name);
    }

    public RedissonScoredSortedSet(Codec codec, CommandAsyncExecutor commandExecutor, String name) {
        super(codec, commandExecutor, name);
    }

    @Override
    public V pollFirst() {
        return get(pollFirstAsync());
    }

    @Override
    public V pollLast() {
        return get(pollLastAsync());
    }

    @Override
    public Future<V> pollFirstAsync() {
        return poll(0);
    }

    @Override
    public Future<V> pollLastAsync() {
        return poll(-1);
    }

    private Future<V> poll(int index) {
        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_OBJECT,
                "local v = redis.call('zrange', KEYS[1], ARGV[1], ARGV[2]); "
                + "if v[1] ~= nil then "
                    + "redis.call('zremrangebyrank', KEYS[1], ARGV[1], ARGV[2]); "
                    + "return v[1]; "
                + "end "
                + "return nil;",
                Collections.<Object>singletonList(getName()), index, index);
    }

    @Override
    public boolean add(double score, V object) {
        return get(addAsync(score, object));
    }

    @Override
    public boolean tryAdd(double score, V object) {
        return get(tryAddAsync(score, object));
    }

    @Override
    public V first() {
        return get(firstAsync());
    }

    @Override
    public Future<V> firstAsync() {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZRANGE_SINGLE, getName(), 0, 0);
    }

    @Override
    public V last() {
        return get(lastAsync());
    }

    @Override
    public Future<V> lastAsync() {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZRANGE_SINGLE, getName(), -1, -1);
    }

    @Override
    public Future<Boolean> addAsync(double score, V object) {
        return commandExecutor.writeAsync(getName(), codec, RedisCommands.ZADD_BOOL, getName(), BigDecimal.valueOf(score).toPlainString(), object);
    }

    @Override
    public Long addAll(Map<V, Double> objects) {
        return get(addAllAsync(objects));
    }

    @Override
    public Future<Long> addAllAsync(Map<V, Double> objects) {
        if (objects.isEmpty()) {
            return newSucceededFuture(0L);
        }
        List<Object> params = new ArrayList<Object>(objects.size()*2+1);
        params.add(getName());
        try {
            for (Entry<V, Double> entry : objects.entrySet()) {
                params.add(BigDecimal.valueOf(entry.getValue()).toPlainString());
                params.add(codec.getValueEncoder().encode(entry.getKey()));
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }

        return commandExecutor.writeAsync(getName(), codec, RedisCommands.ZADD, params.toArray());
    }

    @Override
    public Future<Boolean> tryAddAsync(double score, V object) {
        return commandExecutor.writeAsync(getName(), codec, RedisCommands.ZADD_NX_BOOL, getName(), "NX", BigDecimal.valueOf(score).toPlainString(), object);
    }

    @Override
    public boolean remove(Object object) {
        return get(removeAsync(object));
    }

    @Override
    public int removeRangeByRank(int startIndex, int endIndex) {
        return get(removeRangeByRankAsync(startIndex, endIndex));
    }

    @Override
    public Future<Integer> removeRangeByRankAsync(int startIndex, int endIndex) {
        return commandExecutor.writeAsync(getName(), codec, RedisCommands.ZREMRANGEBYRANK, getName(), startIndex, endIndex);
    }

    @Override
    public int removeRangeByScore(double startScore, boolean startScoreInclusive, double endScore, boolean endScoreInclusive) {
        return get(removeRangeByScoreAsync(startScore, startScoreInclusive, endScore, endScoreInclusive));
    }

    @Override
    public Future<Integer> removeRangeByScoreAsync(double startScore, boolean startScoreInclusive, double endScore, boolean endScoreInclusive) {
        String startValue = value(startScore, startScoreInclusive);
        String endValue = value(endScore, endScoreInclusive);
        return commandExecutor.writeAsync(getName(), codec, RedisCommands.ZREMRANGEBYSCORE, getName(), startValue, endValue);
    }

    private String value(double score, boolean inclusive) {
        StringBuilder element = new StringBuilder();
        if (!inclusive) {
            element.append("(");
        }
        if (Double.isInfinite(score)) {
            element.append(score > 0 ? "+inf" : "-inf");
        } else {
            element.append(BigDecimal.valueOf(score).toPlainString());
        }
        return element.toString();
    }

    @Override
    public void clear() {
        delete();
    }

    @Override
    public Future<Boolean> removeAsync(Object object) {
        return commandExecutor.writeAsync(getName(), codec, RedisCommands.ZREM, getName(), object);
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public int size() {
        return get(sizeAsync());
    }

    @Override
    public Future<Integer> sizeAsync() {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZCARD_INT, getName());
    }

    @Override
    public boolean contains(Object object) {
        return get(containsAsync(object));
    }

    @Override
    public Future<Boolean> containsAsync(Object o) {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZSCORE_CONTAINS, getName(), o);
    }

    @Override
    public Double getScore(V o) {
        return get(getScoreAsync(o));
    }

    @Override
    public Future<Double> getScoreAsync(V o) {
        return commandExecutor.readAsync(getName(), new ScoredCodec(codec), RedisCommands.ZSCORE, getName(), o);
    }

    @Override
    public Integer rank(V o) {
        return get(rankAsync(o));
    }

    @Override
    public Future<Integer> rankAsync(V o) {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZRANK_INT, getName(), o);
    }

    private ListScanResult<V> scanIterator(InetSocketAddress client, long startPos) {
        Future<ListScanResult<V>> f = commandExecutor.readAsync(client, getName(), codec, RedisCommands.ZSCAN, getName(), startPos);
        return get(f);
    }

    @Override
    public Iterator<V> iterator() {
        return new RedissonBaseIterator<V>() {

            @Override
            ListScanResult<V> iterator(InetSocketAddress client, long nextIterPos) {
                return scanIterator(client, nextIterPos);
            }

            @Override
            void remove(V value) {
                RedissonScoredSortedSet.this.remove(value);
            }
            
        };
    }

    @Override
    public Object[] toArray() {
        List<Object> res = (List<Object>) get(valueRangeAsync(0, -1));
        return res.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        List<Object> res = (List<Object>) get(valueRangeAsync(0, -1));
        return res.toArray(a);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return get(containsAllAsync(c));
    }

    @Override
    public Future<Boolean> containsAllAsync(Collection<?> c) {
        return commandExecutor.evalReadAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN_WITH_VALUES,
                "local s = redis.call('zrange', KEYS[1], 0, -1);" +
                        "for i = 1, #s, 1 do " +
                            "for j = 1, #ARGV, 1 do "
                            + "if ARGV[j] == s[i] "
                            + "then table.remove(ARGV, j) end "
                        + "end; "
                       + "end;"
                       + "return #ARGV == 0 and 1 or 0; ",
                Collections.<Object>singletonList(getName()), c.toArray());
    }

    @Override
    public Future<Boolean> removeAllAsync(Collection<?> c) {
        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN_WITH_VALUES,
                      "local v = 0;"
                    + "for i=1, #ARGV, 5000 do "
                        + "v = v + redis.call('zrem', KEYS[1], unpack(ARGV, i, math.min(i+4999, #ARGV))); "
                    + "end "
                    + "return v > 0;",
                Collections.<Object>singletonList(getName()), c.toArray());
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return get(removeAllAsync(c));
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return get(retainAllAsync(c));
    }

    @Override
    public Future<Boolean> retainAllAsync(Collection<?> c) {
        return commandExecutor.evalWriteAsync(getName(), codec, RedisCommands.EVAL_BOOLEAN_WITH_VALUES,
                    "local changed = 0 " +
                    "local s = redis.call('zrange', KEYS[1], 0, -1) "
                       + "local i = 1 "
                       + "while i <= #s do "
                            + "local element = s[i] "
                            + "local isInAgrs = false "
                            + "for j = 1, #ARGV, 1 do "
                                + "if ARGV[j] == element then "
                                    + "isInAgrs = true "
                                    + "break "
                                + "end "
                            + "end "
                            + "if isInAgrs == false then "
                                + "redis.call('zrem', KEYS[1], element) "
                                + "changed = 1 "
                            + "end "
                            + "i = i + 1 "
                       + "end "
                       + "return changed ",
                Collections.<Object>singletonList(getName()), c.toArray());
    }

    @Override
    public Double addScore(V object, Number value) {
        return get(addScoreAsync(object, value));
    }

    @Override
    public Future<Double> addScoreAsync(V object, Number value) {
        return commandExecutor.writeAsync(getName(), StringCodec.INSTANCE, RedisCommands.ZINCRBY,
                                   getName(), new BigDecimal(value.toString()).toPlainString(), object);
    }

    @Override
    public Collection<V> valueRange(int startIndex, int endIndex) {
        return get(valueRangeAsync(startIndex, endIndex));
    }

    @Override
    public Future<Collection<V>> valueRangeAsync(int startIndex, int endIndex) {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZRANGE, getName(), startIndex, endIndex);
    }

    @Override
    public Collection<ScoredEntry<V>> entryRange(int startIndex, int endIndex) {
        return get(entryRangeAsync(startIndex, endIndex));
    }

    @Override
    public Future<Collection<ScoredEntry<V>>> entryRangeAsync(int startIndex, int endIndex) {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZRANGE_ENTRY, getName(), startIndex, endIndex, "WITHSCORES");
    }

    @Override
    public Collection<V> valueRange(double startScore, boolean startScoreInclusive, double endScore, boolean endScoreInclusive) {
        return get(valueRangeAsync(startScore, startScoreInclusive, endScore, endScoreInclusive));
    }

    @Override
    public Future<Collection<V>> valueRangeAsync(double startScore, boolean startScoreInclusive, double endScore, boolean endScoreInclusive) {
        String startValue = value(startScore, startScoreInclusive);
        String endValue = value(endScore, endScoreInclusive);
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZRANGEBYSCORE, getName(), startValue, endValue);
    }

    @Override
    public Collection<V> valueRangeReversed(double startScore, boolean startScoreInclusive, double endScore,
            boolean endScoreInclusive) {
        return get(valueRangeReversedAsync(startScore, startScoreInclusive, endScore, endScoreInclusive));
    }

    @Override
    public Future<Collection<V>> valueRangeReversedAsync(double startScore, boolean startScoreInclusive, double endScore,
            boolean endScoreInclusive) {
        String startValue = value(startScore, startScoreInclusive);
        String endValue = value(endScore, endScoreInclusive);
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZREVRANGEBYSCORE, getName(), endValue, startValue);
    }


    @Override
    public Collection<ScoredEntry<V>> entryRange(double startScore, boolean startScoreInclusive, double endScore, boolean endScoreInclusive) {
        return get(entryRangeAsync(startScore, startScoreInclusive, endScore, endScoreInclusive));
    }

    @Override
    public Future<Collection<ScoredEntry<V>>> entryRangeAsync(double startScore, boolean startScoreInclusive, double endScore, boolean endScoreInclusive) {
        String startValue = value(startScore, startScoreInclusive);
        String endValue = value(endScore, endScoreInclusive);
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZRANGEBYSCORE_ENTRY, getName(), startValue, endValue, "WITHSCORES");
    }

    @Override
    public Collection<V> valueRange(double startScore, boolean startScoreInclusive, double endScore, boolean endScoreInclusive, int offset, int count) {
        return get(valueRangeAsync(startScore, startScoreInclusive, endScore, endScoreInclusive, offset, count));
    }

    @Override
    public Future<Collection<V>> valueRangeAsync(double startScore, boolean startScoreInclusive, double endScore, boolean endScoreInclusive, int offset, int count) {
        String startValue = value(startScore, startScoreInclusive);
        String endValue = value(endScore, endScoreInclusive);
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZRANGEBYSCORE, getName(), startValue, endValue, "LIMIT", offset, count);
    }

    @Override
    public Collection<V> valueRangeReversed(double startScore, boolean startScoreInclusive, double endScore, boolean endScoreInclusive, int offset, int count) {
        return get(valueRangeReversedAsync(startScore, startScoreInclusive, endScore, endScoreInclusive, offset, count));
    }

    @Override
    public Future<Collection<V>> valueRangeReversedAsync(double startScore, boolean startScoreInclusive, double endScore, boolean endScoreInclusive, int offset, int count) {
        String startValue = value(startScore, startScoreInclusive);
        String endValue = value(endScore, endScoreInclusive);
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZREVRANGEBYSCORE, getName(), endValue, startValue, "LIMIT", offset, count);
    }

    @Override
    public Collection<ScoredEntry<V>> entryRange(double startScore, boolean startScoreInclusive, double endScore, boolean endScoreInclusive, int offset, int count) {
        return get(entryRangeAsync(startScore, startScoreInclusive, endScore, endScoreInclusive, offset, count));
    }

    @Override
    public Future<Collection<ScoredEntry<V>>> entryRangeAsync(double startScore, boolean startScoreInclusive, double endScore, boolean endScoreInclusive, int offset, int count) {
        String startValue = value(startScore, startScoreInclusive);
        String endValue = value(endScore, endScoreInclusive);
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZRANGEBYSCORE_ENTRY, getName(), startValue, endValue, "WITHSCORES", "LIMIT", offset, count);
    }

    @Override
    public Future<Integer> revRankAsync(V o) {
        return commandExecutor.readAsync(getName(), codec, RedisCommands.ZREVRANK_INT, getName(), o);
    }

    @Override
    public Integer revRank(V o) {
        return get(revRankAsync(o));
    }

}
