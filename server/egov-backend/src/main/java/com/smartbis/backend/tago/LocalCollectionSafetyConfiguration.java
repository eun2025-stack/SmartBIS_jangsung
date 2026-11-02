package com.smartbis.backend.tago;

import java.net.URI;
import java.time.Instant;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;

/** Reject the wrong target before a DataSource/pool can open any connection. */
@Configuration(proxyBeanMethods=false)
@Profile("local-collection")
public class LocalCollectionSafetyConfiguration {
    @Bean public static BeanFactoryPostProcessor localDatabaseGuard(Environment environment) {
        return factory->{
            String value=environment.getProperty("spring.datasource.url","");
            URI uri;
            try { uri=URI.create(value.startsWith("jdbc:")?value.substring(5):""); }
            catch(IllegalArgumentException failure) { throw new IllegalStateException("LOCAL_DATABASE_URL_INVALID"); }
            if(!"postgresql".equals(uri.getScheme()) || !java.util.Set.of("localhost","127.0.0.1","[::1]","::1").contains(uri.getHost()==null?"":uri.getHost())
                || !"/smartbis_db_copy2".equals(uri.getPath())) throw new IllegalStateException("LOCAL_DATABASE_REQUIRED_BEFORE_CONNECTION");
            if(environment.getActiveProfiles().length!=1 || !"local-collection".equals(environment.getActiveProfiles()[0]))
                throw new IllegalStateException("SINGLE_LOCAL_PROFILE_REQUIRED");
            if(!"-".equals(environment.getProperty("tago.catalog.sync-cron"))) throw new IllegalStateException("LOCAL_CATALOG_MUST_BE_DISABLED");
            String stop=environment.getProperty("collection.stop-at","");
            if(stop.isBlank() || !Instant.parse(stop).isAfter(Instant.now())) throw new IllegalStateException("FUTURE_COLLECTION_DEADLINE_REQUIRED");
            var end=Instant.parse(stop).atZone(java.time.ZoneId.of("Asia/Seoul"));
            if(end.getDayOfWeek()!=java.time.DayOfWeek.SUNDAY || !end.toLocalTime().equals(java.time.LocalTime.of(21,0)))
                throw new IllegalStateException("SUNDAY_21_DEADLINE_REQUIRED");
        };
    }
}
