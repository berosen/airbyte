package io.airbyte.integrations.destination.redis;

import io.airbyte.integrations.base.JavaBaseConstants;
import java.io.Closeable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import redis.clients.jedis.Jedis;

public class RedisOpsProvider implements Closeable {

    private final Jedis jedis;

    private static final String PATTERN = ":[0-9]*";

    public RedisOpsProvider(RedisConfig redisConfig) {
        this.jedis = RedisPoolManager.initConnection(redisConfig);
    }

    public void insert(String namespace, String stream, String data) {
        var baseKey = generateBaseKey(namespace, stream);
        var index = jedis.incr(baseKey);
        var indexKey = generateIndexKey(baseKey, index);
        jedis.hmset(indexKey, Map.of(
            JavaBaseConstants.COLUMN_NAME_DATA, data,
            JavaBaseConstants.COLUMN_NAME_EMITTED_AT, String.valueOf(Instant.now().toEpochMilli())));
    }

    public void rename(String namespace, String key, String newkey) {
        var baseKey = generateBaseKey(namespace, key);
        var baseNewKey = generateBaseKey(namespace, newkey);
        jedis.keys(baseKey + PATTERN).forEach(k -> {
            var index = jedis.incr(baseNewKey);
            jedis.rename(k, generateIndexKey(baseNewKey, index));
        });
    }

    public void delete(String namespace, String stream) {
        var baseKey = generateBaseKey(namespace, stream);
        jedis.keys(baseKey + PATTERN).forEach(jedis::del);
        // reset index?
    }

    public List<RedisRecord> getAll(String namespace, String stream) {
        var baseKey = generateBaseKey(namespace, stream);
        return jedis.keys(baseKey + PATTERN).stream()
            .map(k -> Tuple.of(k, jedis.hgetAll(k)))
            .map(t -> new RedisRecord(
                Long.parseLong(t.value1().substring(t.value1().lastIndexOf(":") + 1)),
                t.value2().get(JavaBaseConstants.COLUMN_NAME_DATA),
                Instant.ofEpochMilli(Long.parseLong(t.value2().get(JavaBaseConstants.COLUMN_NAME_EMITTED_AT)))))
            .collect(Collectors.toList());
    }

    public void ping(String message) {
        jedis.ping(message);
    }

    public void flush() {
        jedis.flushAll();
    }

    @Override
    public void close() {
        jedis.close();
    }

    private String generateBaseKey(String namespace, String stream) {
        return namespace + ":" + stream;
    }

    private String generateIndexKey(String key, Long id) {
        return key + ":" + id;
    }
}
