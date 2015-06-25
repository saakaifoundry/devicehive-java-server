package com.devicehive.application.hazelcast;

import com.devicehive.util.ApplicationContextHolder;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.hibernate.HazelcastCacheRegionFactory;
import org.hibernate.cache.CacheException;
import org.hibernate.cfg.Settings;

import java.util.Properties;

public class HazelcastCustomCacheRegionFactory extends HazelcastCacheRegionFactory {

    @Override
    public void start(Settings settings, Properties properties) throws CacheException {
        ApplicationContextHolder.getApplicationContext().getBean(HazelcastInstance.class);
        super.start(settings, properties);
    }
}
