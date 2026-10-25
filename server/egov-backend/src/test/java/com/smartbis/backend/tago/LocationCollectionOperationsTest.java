package com.smartbis.backend.tago;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class LocationCollectionOperationsTest {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-22T14:59:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        public Instant instant() { return now; }
        void advance(long ms) { now = now.plusMillis(ms); }
    }
    /** Shared store emulates persisted DB values across process/guard instances. */
    static class PersistedStore implements GuardUsageStore {
        final Map<LocalDate,Integer> counts = new HashMap<>();
        int reads;
        public int read(String api, LocalDate day) { assertEquals("TAGO_LOCATION",api); reads++; return counts.getOrDefault(day,0); }
        public int reserve(String api, LocalDate day, int limit, Instant now) {
            assertEquals("TAGO_LOCATION",api);
            int old = counts.getOrDefault(day,0);
            if (old >= limit) return -1;
            counts.put(day,old+1); return old+1;
        }
    }
    LocationCollectionProperties enabled() {
        var settings = new LocationCollectionProperties();
        settings.setEnabled(true); return settings;
    }

    @Test void defaultsAreDisabledAndBudgetsAreDistinct() {
        var settings = new LocationCollectionProperties();
        assertFalse(settings.isEnabled());
        assertFalse(settings.getLocal().isEnabled());
        assertFalse(settings.getHundred().isEnabled());
        assertEquals(9500,settings.getDailyLimit());
        assertEquals(10000,settings.getDeveloperAccountLimit());
        assertEquals(600,settings.getLocal().getIntervalSeconds());
        assertEquals(600,settings.getHundred().getIntervalSeconds());
        settings.validate();
    }
    @Test void disabledGuardDoesNotTouchDatabase() {
        GuardUsageStore store = mock(GuardUsageStore.class);
        var guard = new TagoQuotaGuard(new LocationCollectionProperties(),store,new MutableClock());
        guard.initialize();
        assertEquals(TagoQuotaGuard.Decision.DISABLED,guard.acquire());
        verifyNoInteractions(store);
    }
    @Test void springDefaultHasNoExecutionBeanAndNoDatabaseAccess() {
        var jdbc = mock(JdbcTemplate.class);
        var ds = mock(DataSource.class);
        new ApplicationContextRunner().withUserConfiguration(LocationCollectionConfiguration.class)
            .withBean(JdbcTemplate.class,()->jdbc).withBean(DataSource.class,()->ds)
            .run(context -> {
                assertNull(context.getStartupFailure());
                assertTrue(context.getBeansOfType(TagoLocationExecution.class).isEmpty());
                var props = context.getBean(LocationCollectionProperties.class);
                assertFalse(props.isEnabled());
                assertEquals(9500,props.getDailyLimit());
                assertEquals(88,props.getLocal().getRouteIds().size());
                assertEquals(17,props.getHundred().getRouteIds().size());
                verify(jdbc).afterPropertiesSet();
                verifyNoMoreInteractions(jdbc);
                verifyNoInteractions(ds);
            });
    }
    @Test void persistedDailyLimitBlocksAt9500() {
        var store = new PersistedStore(); var clock = new MutableClock();
        store.counts.put(LocalDate.of(2026,10,22),9499);
        var guard = new TagoQuotaGuard(enabled(),store,clock);
        guard.initialize();
        assertEquals(9499,guard.callCount());
        assertEquals(TagoQuotaGuard.Decision.ALLOWED,guard.acquire());
        assertEquals(TagoQuotaGuard.Decision.DAILY_LIMIT,guard.acquire());
        clock.advance(20000);
        assertEquals(TagoQuotaGuard.Decision.DAILY_LIMIT,guard.acquire());
        assertEquals(9500,guard.callCount());
    }
    @Test void restartLoadsTodaysUsageInsteadOfResetting() {
        var store = new PersistedStore(); var clock = new MutableClock();
        var first = new TagoQuotaGuard(enabled(),store,clock);
        first.initialize(); first.acquire();
        var restarted = new TagoQuotaGuard(enabled(),store,clock);
        restarted.initialize();
        assertEquals(1,restarted.callCount());
        restarted.acquire();
        assertEquals(2,restarted.callCount());
        assertTrue(store.reads >= 2);
    }
    @Test void midnightResetsOnlyAtKstDayBoundary() {
        var settings = enabled(); settings.setDailyLimit(1);
        var store = new PersistedStore(); var clock = new MutableClock();
        var guard = new TagoQuotaGuard(settings,store,clock);
        guard.initialize(); guard.acquire();
        assertEquals(TagoQuotaGuard.Decision.DAILY_LIMIT,guard.acquire());
        clock.advance(61000);
        assertEquals(TagoQuotaGuard.Decision.ALLOWED,guard.acquire());
        assertEquals(1,store.counts.get(LocalDate.of(2026,10,22)));
        assertEquals(1,store.counts.get(LocalDate.of(2026,10,23)));
    }
    @Test void minGapUsesInjectedClockWithoutSleeping() {
        var guard = new TagoQuotaGuard(enabled(),new PersistedStore(),new MutableClock());
        assertEquals(TagoQuotaGuard.Decision.ALLOWED,guard.acquire());
        assertEquals(TagoQuotaGuard.Decision.TOO_SOON,guard.acquire());
    }
    @Test void retriesHaveHardUpperBoundAndConsumeQuotaForEveryAttempt() throws Exception {
        var settings=enabled(); var store=new PersistedStore(); var clock=new MutableClock();
        var guard = new TagoQuotaGuard(settings,store,clock);
        var lock = mock(LocationCollectorLock.class);
        var execution = new TagoLocationExecution(settings,guard,lock,clock::advance);
        execution.start();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IOException.class,()->execution.execute(()-> { calls.incrementAndGet(); throw new IOException("fake transport"); }));
        assertEquals(3,calls.get());
        assertEquals(3,guard.callCount());
        verify(lock,times(3)).ensureHeld();
        execution.stop(); verify(lock).close();
    }
    @Test void quotaDuringRetryStopsBeforeNextHttpAttempt() throws Exception {
        var settings=enabled(); settings.setDailyLimit(1);
        var guard=new TagoQuotaGuard(settings,new PersistedStore(),new MutableClock());
        var execution=new TagoLocationExecution(settings,guard,mock(LocationCollectorLock.class),ms->{});
        AtomicInteger calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->execution.execute(()-> { calls.incrementAndGet(); throw new IOException("fake"); }));
        assertEquals(1,calls.get());
    }
    @Test void rateLimitResponseDoesNotRetryImmediately() {
        var settings=enabled(); var clock=new MutableClock();
        var guard=new TagoQuotaGuard(settings,new PersistedStore(),clock);
        var execution=new TagoLocationExecution(settings,guard,mock(LocationCollectorLock.class),clock::advance);
        AtomicInteger calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->execution.execute(()-> { calls.incrementAndGet(); throw new IllegalStateException("TAGO_HTTP_429"); }));
        assertEquals(1,calls.get());
    }
    @Test void failedPersistenceNeverRunsHttpCallback() {
        var settings=enabled(); var store=mock(GuardUsageStore.class);
        when(store.reserve(anyString(),any(),anyInt(),any())).thenThrow(new IllegalStateException("DB down"));
        var execution=new TagoLocationExecution(settings,new TagoQuotaGuard(settings,store,new MutableClock()),mock(LocationCollectorLock.class),ms->{});
        AtomicInteger calls=new AtomicInteger();
        assertThrows(IllegalStateException.class,()->execution.execute(()->calls.incrementAndGet()));
        assertEquals(0,calls.get());
    }
    @Test void lostLockNeverRunsHttpCallback() throws Exception {
        var settings=enabled(); var lock=mock(LocationCollectorLock.class);
        doThrow(new IllegalStateException("lost")).when(lock).ensureHeld();
        var store=mock(GuardUsageStore.class);
        var execution=new TagoLocationExecution(settings,new TagoQuotaGuard(settings,store,new MutableClock()),lock,ms->{});
        assertThrows(IllegalStateException.class,()->execution.execute(()->fail("No HTTP")));
        verifyNoInteractions(store);
    }
    @Test void duplicateCollectorFailsAndReturnsConnection() throws Exception {
        var ds=mock(DataSource.class); var connection=mock(Connection.class);
        var statement=mock(PreparedStatement.class); var result=mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true); when(result.getBoolean(1)).thenReturn(false);
        assertThrows(IllegalStateException.class,()->new LocationCollectorLock(ds).acquire());
        verify(connection).close();
    }
    @Test void retainedSessionIsCheckedAndExplicitlyUnlocked() throws Exception {
        var ds=mock(DataSource.class); var connection=mock(Connection.class);
        var statement=mock(PreparedStatement.class); var result=mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true); when(result.getBoolean(1)).thenReturn(true);
        when(connection.isValid(2)).thenReturn(true);
        var lock=new LocationCollectorLock(ds); lock.acquire(); lock.ensureHeld(); lock.close();
        verify(connection).prepareStatement("SELECT pg_advisory_unlock(?)");
        verify(connection).close();
    }
    @Test void jdbcStoreRestoresExistingCountAndReservesAtomically() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Integer>>any(),any(),any())).thenReturn(List.of(19));
        var store=new JdbcGuardUsageStore(jdbc);
        assertEquals(19,store.read("TAGO_LOCATION",LocalDate.of(2026,10,22)));
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Integer>>any(),any(),any(),any(),any(),any(),any())).thenReturn(List.of(20));
        assertEquals(20,store.reserve("TAGO_LOCATION",LocalDate.of(2026,10,22),9500,Instant.now()));
        verify(jdbc).query(contains("ON CONFLICT (api_name, usage_date)"),org.mockito.ArgumentMatchers.<RowMapper<Integer>>any(),
                eq("TAGO_LOCATION"),eq(LocalDate.of(2026,10,22)),eq(9500),eq(9500),any(),any());
    }
    @Test void routeGroupsAreConfigurationOnlyAndCannotOverlap() {
        var settings=enabled();
        settings.getLocal().setRouteIds(List.of("configured-route"));
        settings.getHundred().setRouteIds(List.of("configured-route"));
        assertThrows(IllegalArgumentException.class,settings::validate);
    }
    @Test void jdbcDenialRecordsBlockedTimeWithoutAnotherReservation() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<Integer>>any(),any(),any(),any(),any(),any(),any())).thenReturn(List.of());
        var day=LocalDate.of(2026,10,23);
        assertEquals(-1,new JdbcGuardUsageStore(jdbc).reserve("TAGO_LOCATION",day,9500,Instant.now()));
        verify(jdbc).update(contains("SET blocked_at = COALESCE"),any(),eq(9500),eq("TAGO_LOCATION"),eq(day),eq(9500));
    }
    @Test void migrationIsAdditiveAndContainsNoExistingTableWrites() throws Exception {
        var path=Path.of("../database/migrations/V015__location_collection_operations.sql");
        String sql=Files.readString(path).replaceAll("(?m)--.*$","").toUpperCase();
        assertEquals(3,sql.split("CREATE TABLE IF NOT EXISTS",-1).length-1);
        assertTrue(sql.contains("RAISE EXCEPTION"));
        assertFalse(java.util.regex.Pattern.compile("\\b(ALTER|DROP|INSERT|UPDATE|DELETE|TRUNCATE)\\b").matcher(sql).find());
        assertFalse(sql.contains("BUS_POSITIONS"));
    }
    @Test void appsAndControllersHaveNoTagoLocationCalls() throws Exception {
        for (Path root:List.of(Path.of("src/main/java/com/smartbis/backend"),Path.of("../../client-android/app/src/main/java"),Path.of("src/main/resources/static"))) {
            try(var files=Files.walk(root)) {
                for(Path path:files.filter(Files::isRegularFile).filter(p->p.toString().endsWith("Controller.java") || p.toString().endsWith(".kt") || p.toString().endsWith(".js") || p.toString().endsWith(".html")).toList()) {
                    String text=Files.readString(path);
                    assertFalse(text.contains("getRouteAcctoBusLcList") || text.contains("BusLcInfoInqireService"),path.toString());
                }
            }
        }
    }
}
