package com.smartbis.backend.tago;

import java.time.Clock;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@PropertySource({"classpath:tago-location-route-groups.properties", "classpath:tago-location-operations.properties"})
@EnableConfigurationProperties(LocationCollectionProperties.class)
public class LocationCollectionConfiguration {
    @Bean(name="laneAScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler laneAScheduler() { return laneScheduler("location-A-"); }
    @Bean(name="laneBScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler laneBScheduler() { return laneScheduler("location-B-"); }
    @Bean(name="laneCScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler laneCScheduler() { return laneScheduler("location-C-"); }
    @Bean(name="arrivalScheduler")
    org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler arrivalScheduler() { return laneScheduler("arrival-"); }
    private org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler laneScheduler(String prefix) {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);scheduler.setThreadNamePrefix(prefix);return scheduler;
    }
    @Bean
    @ConditionalOnProperty(prefix = "tago.location.operations", name = "enabled", havingValue = "true")
    TagoLocationExecution tagoLocationExecution(LocationCollectionProperties settings, JdbcTemplate jdbc, DataSource dataSource,
            org.springframework.beans.factory.ObjectProvider<CollectionDeadline> deadlines) {
        Clock clock = Clock.system(ZoneId.of(settings.getZone()));
        var execution=new TagoLocationExecution(settings, new TagoQuotaGuard(settings, new JdbcGuardUsageStore(jdbc), clock),
                new LocationCollectorLock(dataSource), Thread::sleep, new JdbcRouteCallPlanStore(jdbc)::failure);
        execution.setDeadline(deadlines.getIfAvailable(CollectionDeadline::unlimited));return execution;
    }
    @Bean
    @ConditionalOnProperty(prefix = "tago.location.operations", name = "enabled", havingValue = "true")
    RouteCallPlanScheduler routeCallPlanScheduler(LocationCollectionProperties settings, JdbcTemplate jdbc) {
        ZoneId zone = ZoneId.of(settings.getZone());
        return new RouteCallPlanScheduler(new JdbcRouteCallPlanStore(jdbc), Clock.system(zone), zone, settings);
    }
}
