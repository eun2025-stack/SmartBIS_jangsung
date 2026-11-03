package com.smartbis.backend.tago;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollectionRunSchemaTest {
    @Test void actualLocalDdlArchivesExistingRowsAndKeepsNewRunSeparate() throws Exception {
        String sql=Files.readString(Path.of("local-collection/run-schema.sql"));
        assertTrue(sql.contains("current_database()<>'smartbis_db_copy2'"));
        // Target check is PostgreSQL-specific. Execute the actual table/column DDL
        // in an isolated in-memory database, never a host/container database.
        sql=sql.substring(sql.indexOf("CREATE TABLE"));
        // H2 supports untargeted ON CONFLICT DO NOTHING, not PG's target syntax.
        sql=sql.replace("ON CONFLICT(collection_run_id)","ON CONFLICT");
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE")) {
            var s=connection.createStatement();
            s.execute("CREATE DOMAIN timestamptz AS TIMESTAMP WITH TIME ZONE");
            for(String table:new String[]{"bus_positions","arrival_predictions","collection_pause_intervals","collection_process_runs"}) {
                s.execute("CREATE TABLE "+table+"(id int primary key,observed_at timestamptz,started_at timestamptz)");
                s.execute("INSERT INTO "+table+"(id) VALUES(1)");
            }
            for(int repeat=0;repeat<2;repeat++)for(String statement:sql.split(";"))if(!statement.isBlank())s.execute(statement);
            for(String table:new String[]{"bus_positions","arrival_predictions","collection_pause_intervals","collection_process_runs"}) {
                try(var rows=s.executeQuery("SELECT count(*) FROM "+table+" d JOIN collection_runs r USING(collection_run_id) WHERE r.archived")){
                    assertTrue(rows.next());assertEquals(1,rows.getInt(1));
                }
            }
            UUID run=UUID.randomUUID();
            s.execute("INSERT INTO collection_runs VALUES('"+run+"',CURRENT_TIMESTAMP,NULL,'"+"a".repeat(40)+"','"+"b".repeat(64)+"',false)");
            s.execute("INSERT INTO bus_positions(id,collection_run_id) VALUES(2,'"+run+"')");
            try(var rows=s.executeQuery("SELECT count(*) FROM bus_positions WHERE collection_run_id='"+run+"'")){
                assertTrue(rows.next());assertEquals(1,rows.getInt(1));
            }
            try(var rows=s.executeQuery("SELECT count(*) FROM bus_positions")){assertTrue(rows.next());assertEquals(2,rows.getInt(1));}
        }
    }
}
