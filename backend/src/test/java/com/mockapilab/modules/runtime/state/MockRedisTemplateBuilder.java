package com.mockapilab.modules.runtime.state;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MockRedisTemplateBuilder {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> hashes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> sets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();
    private volatile RuntimeException nextPutAllFailure;
    private volatile RuntimeException nextRegisterFailure;
    private volatile RuntimeException nextDeleteFailure;
    private volatile RuntimeException nextHasKeyFailure;
    private volatile Runnable beforePromoteHook;

    public void setNextPutAllFailure(RuntimeException failure) {
        this.nextPutAllFailure = failure;
    }

    public void setNextRegisterFailure(RuntimeException failure) {
        this.nextRegisterFailure = failure;
    }

    public void setNextDeleteFailure(RuntimeException failure) {
        this.nextDeleteFailure = failure;
    }

    public void setNextHasKeyFailure(RuntimeException failure) {
        this.nextHasKeyFailure = failure;
    }

    public void setBeforePromoteHook(Runnable hook) {
        this.beforePromoteHook = hook;
    }

    public Map<String, ConcurrentHashMap<String, String>> getHashes() {
        return hashes;
    }

    public Map<String, Set<String>> getSets() {
        return sets;
    }

    public Map<String, String> getValues() {
        return values;
    }

    @SuppressWarnings("unchecked")
    public StringRedisTemplate build() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(template.opsForHash()).thenReturn((HashOperations) hashOps);
        when(template.opsForSet()).thenReturn(setOps);
        when(template.opsForValue()).thenReturn(valueOps);

        doAnswer(inv -> {
            String key = (String) inv.getArgument(0);
            String val = (String) inv.getArgument(1);
            values.put(key, val);
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        doAnswer(inv -> {
            String key = (String) inv.getArgument(0);
            String val = (String) inv.getArgument(1);
            values.put(key, val);
            return null;
        }).when(valueOps).set(anyString(), anyString());

        when(valueOps.get(anyString())).thenAnswer(inv -> values.get((String) inv.getArgument(0)));

        when(template.hasKey(anyString())).thenAnswer(inv -> {
            RuntimeException failure = nextHasKeyFailure;
            if (failure != null) {
                nextHasKeyFailure = null;
                throw failure;
            }
            String key = (String) inv.getArgument(0);
            return hashes.containsKey(key) || sets.containsKey(key) || values.containsKey(key);
        });

        when(template.expire(anyString(), any(Duration.class))).thenAnswer(inv -> {
            String key = (String) inv.getArgument(0);
            return hashes.containsKey(key) || sets.containsKey(key) || values.containsKey(key);
        });

        // entries
        doAnswer(inv -> {
            String key = String.valueOf(inv.getArguments()[0]);
            Map<String, String> map = hashes.get(key);
            return map != null ? new HashMap<>(map) : Collections.emptyMap();
        }).when(hashOps).entries(anyString());

        // get
        doAnswer(inv -> {
            String key = String.valueOf(inv.getArguments()[0]);
            String field = String.valueOf(inv.getArguments()[1]);
            Map<String, String> map = hashes.get(key);
            return map != null ? map.get(field) : null;
        }).when(hashOps).get(anyString(), any());

        // put
        doAnswer(inv -> {
            String key = String.valueOf(inv.getArguments()[0]);
            String field = String.valueOf(inv.getArguments()[1]);
            String value = String.valueOf(inv.getArguments()[2]);
            hashes.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(field, value);
            return null;
        }).when(hashOps).put(anyString(), any(), any());

        // putAll
        doAnswer(inv -> {
            RuntimeException failure = nextPutAllFailure;
            if (failure != null) {
                nextPutAllFailure = null;
                throw failure;
            }
            String key = String.valueOf(inv.getArguments()[0]);
            Object a1 = inv.getArguments()[1];
            if (a1 instanceof Map<?, ?> m) {
                ConcurrentHashMap<String, String> h = hashes.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
                m.forEach((k, v) -> h.put(String.valueOf(k), String.valueOf(v)));
            }
            return null;
        }).when(hashOps).putAll(anyString(), any(Map.class));

        // delete
        doAnswer(inv -> {
            String key = String.valueOf(inv.getArguments()[0]);
            Map<String, String> map = hashes.get(key);
            long count = 0;
            if (map != null) {
                for (int i = 1; i < inv.getArguments().length; i++) {
                    Object arg = inv.getArguments()[i];
                    if (arg != null) {
                        if (arg instanceof Object[] arr) {
                            for (Object item : arr) {
                                if (item != null && map.remove(item.toString()) != null) {
                                    count++;
                                }
                            }
                        } else {
                            if (map.remove(arg.toString()) != null) {
                                count++;
                            }
                        }
                    }
                }
            }
            return count;
        }).when(hashOps).delete(anyString(), any());

        // size
        doAnswer(inv -> {
            String key = String.valueOf(inv.getArguments()[0]);
            Map<String, String> map = hashes.get(key);
            return map != null ? (long) map.size() : 0L;
        }).when(hashOps).size(anyString());

        // isMember
        when(setOps.isMember(anyString(), any(Object.class))).thenAnswer(inv -> {
            String key = String.valueOf(inv.getArguments()[0]);
            String val = String.valueOf(inv.getArguments()[1]);
            Set<String> set = sets.get(key);
            return set != null && set.contains(val);
        });

        // add
        doAnswer(inv -> {
            String key = String.valueOf(inv.getArguments()[0]);
            Set<String> set = sets.computeIfAbsent(key, k -> Collections.synchronizedSet(new HashSet<>()));
            long count = 0;
            for (int i = 1; i < inv.getArguments().length; i++) {
                Object arg = inv.getArguments()[i];
                if (arg != null) {
                    if (arg instanceof Object[] arr) {
                        for (Object item : arr) {
                            if (item != null && set.add(item.toString())) {
                                count++;
                            }
                        }
                    } else {
                        if (set.add(arg.toString())) {
                            count++;
                        }
                    }
                }
            }
            return count;
        }).when(setOps).add(anyString(), any());

        // members
        doAnswer(inv -> {
            String key = String.valueOf(inv.getArguments()[0]);
            Set<String> set = sets.get(key);
            return set != null ? new HashSet<>(set) : Collections.emptySet();
        }).when(setOps).members(anyString());

        // size
        doAnswer(inv -> {
            String key = String.valueOf(inv.getArguments()[0]);
            Set<String> set = sets.get(key);
            return set != null ? (long) set.size() : 0L;
        }).when(setOps).size(anyString());

        // template delete
        doAnswer(inv -> {
            String key = String.valueOf(inv.getArguments()[0]);
            RuntimeException failure = nextDeleteFailure;
            if (failure != null) {
                nextDeleteFailure = null;
                throw failure;
            }
            boolean h = hashes.remove(key) != null;
            boolean s = sets.remove(key) != null;
            boolean v = values.remove(key) != null;
            return h || s || v;
        }).when(template).delete(anyString());

        // template execute(RedisScript, List<String>, Object...)
        doAnswer(inv -> {
            RedisScript<?> script = (RedisScript<?>) inv.getArgument(0);
            String scriptText = script.getScriptAsString();
            List<?> keys = (List<?>) inv.getArgument(1);

            // Spring Data Redis varargs may arrive as individual args or a single Object[]
            Object[] rawArgs;
            if (inv.getArguments().length == 3 && inv.getArgument(2) instanceof Object[] arr) {
                rawArgs = arr;
            } else {
                rawArgs = new Object[Math.max(0, inv.getArguments().length - 2)];
                for (int i = 2; i < inv.getArguments().length; i++) {
                    rawArgs[i - 2] = inv.getArgument(i);
                }
            }

            if (scriptText.contains("SMEMBERS")) {
                String indexKey = String.valueOf(keys.get(0));
                String deletedKey = String.valueOf(keys.get(1));
                synchronized (this) {
                    values.put(deletedKey, "1");
                    Set<String> removed = sets.remove(indexKey);
                    return removed != null ? List.copyOf(removed) : Collections.emptyList();
                }
            }

            if (scriptText.contains("SADD")) {
                RuntimeException failure = nextRegisterFailure;
                if (failure != null) {
                    nextRegisterFailure = null;
                    throw failure;
                }
                String indexKey = String.valueOf(keys.get(0));
                String deletedKey = String.valueOf(keys.get(1));
                String path = String.valueOf(rawArgs[0]);
                synchronized (this) {
                    if (values.containsKey(deletedKey)) {
                        return 0L;
                    }
                    sets.computeIfAbsent(indexKey, k -> Collections.synchronizedSet(new HashSet<>())).add(path);
                    return 1L;
                }
            }

            Runnable hook = beforePromoteHook;
            if (hook != null) {
                beforePromoteHook = null;
                hook.run();
            }

            String collectionKey = String.valueOf(keys.get(0));
            String fenceKey = keys.size() > 1 ? String.valueOf(keys.get(1)) : null;
            String tempKey = keys.size() > 2 ? String.valueOf(keys.get(2)) : null;

            synchronized (this) {
                String incoming = rawArgs.length > 0 ? String.valueOf(rawArgs[0]) : "";
                boolean hasTemp = rawArgs.length > 1 && "1".equals(String.valueOf(rawArgs[1]));
                boolean isZero = "0".equals(incoming);
                boolean isPos = isPositiveDecimal(incoming);

                if (!isZero && !isPos) {
                    if (hasTemp && tempKey != null) {
                        hashes.remove(tempKey);
                    }
                    return 0L;
                }

                if (fenceKey != null) {
                    String currentRaw = values.get(fenceKey);
                    if (currentRaw != null) {
                        if (isZero) {
                            if (hasTemp && tempKey != null) {
                                hashes.remove(tempKey);
                            }
                            return 0L;
                        }
                        if (isPositiveDecimal(currentRaw) && compareDecimalStrings(incoming, currentRaw) <= 0) {
                            if (hasTemp && tempKey != null) {
                                hashes.remove(tempKey);
                            }
                            return 0L;
                        }
                    }
                }

                if (hasTemp) {
                    if (tempKey == null) {
                        return -1L;
                    }
                    ConcurrentHashMap<String, String> staged = hashes.remove(tempKey);
                    if (staged == null) {
                        return -1L;
                    }
                    hashes.put(collectionKey, staged);
                } else {
                    hashes.remove(collectionKey);
                }

                if (isPos && fenceKey != null) {
                    values.put(fenceKey, incoming);
                }
                return 1L;
            }
        }).when(template).execute(any(RedisScript.class), anyList(), any(Object[].class));

        return template;
    }

    private static boolean isPositiveDecimal(String s) {
        if (s == null || s.isEmpty() || s.charAt(0) < '1' || s.charAt(0) > '9') {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static int compareDecimalStrings(String a, String b) {
        if (a.length() != b.length()) {
            return Integer.compare(a.length(), b.length());
        }
        return a.compareTo(b);
    }
}