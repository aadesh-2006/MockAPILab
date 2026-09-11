package com.mockapilab.modules.runtime.state;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MockRedisTemplateBuilder {

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, String>> hashes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> sets = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public StringRedisTemplate build() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        SetOperations<String, String> setOps = mock(SetOperations.class);

        when(template.opsForHash()).thenReturn((HashOperations) hashOps);
        when(template.opsForSet()).thenReturn(setOps);

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
            boolean h = hashes.remove(key) != null;
            boolean s = sets.remove(key) != null;
            return h || s;
        }).when(template).delete(anyString());

        return template;
    }
}