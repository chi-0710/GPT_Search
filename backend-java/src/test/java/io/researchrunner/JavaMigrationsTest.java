package io.researchrunner;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="JAVA_WRITE_TEST_DATABASE_URL",matches=".+")
class JavaMigrationsTest {
    private DriverManagerDataSource admin() {
        String url=System.getenv("JAVA_WRITE_TEST_DATABASE_URL");
        if(url==null || !url.endsWith("/researchrunner_java_write_test"))throw new IllegalStateException("Only isolated test database allowed");
        return new DriverManagerDataSource(url,"java_test","java_test");
    }
    @FunctionalInterface interface Check {void run(DriverManagerDataSource source) throws Exception;}
    private void database(Check check) throws Exception {
        var admin=admin();var jdbc=new JdbcTemplate(admin);
        String name="researchrunner_migration_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE DATABASE "+name);
        try {check.run(new DriverManagerDataSource(admin.getUrl().replace("/researchrunner_java_write_test","/"+name),"java_test","java_test"));}
        finally {jdbc.execute("DROP DATABASE "+name);}
    }
    private void legacy(DriverManagerDataSource source) throws Exception {
        try(var connection=source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V1__compatible_application_schema.sql"));
        }
    }
    @Test void emptyDatabaseMigratesWithoutPythonAndRepeatedMigrationIsNoOp() throws Exception {
        database(source->{
            assertEquals(1,JavaMigrations.migrate(source,false));assertEquals(0,JavaMigrations.migrate(source,false));
            var jdbc=new JdbcTemplate(source);
            assertEquals("0002_shared_library",jdbc.queryForObject("SELECT version_num FROM alembic_version",String.class));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='1' AND success",Integer.class));
        });
    }
    @Test void explicitBaselineRetainsHistoryAndLegacyVersion() throws Exception {
        database(source->{
            legacy(source);var jdbc=new JdbcTemplate(source);
            jdbc.update("INSERT INTO reports(id,public_id,question,answer,ordered_data,status,created_at,updated_at) VALUES(?,'historical','question','answer','[]','completed',now(),now())",UUID.randomUUID());
            assertThrows(IllegalStateException.class,()->JavaMigrations.migrate(source,false));
            assertEquals(0,JavaMigrations.migrate(source,true));
            assertEquals("answer",jdbc.queryForObject("SELECT answer FROM reports WHERE public_id='historical'",String.class));
            assertEquals("BASELINE",jdbc.queryForObject("SELECT type FROM flyway_schema_history WHERE version='1'",String.class));
            assertEquals("0002_shared_library",jdbc.queryForObject("SELECT version_num FROM alembic_version",String.class));
        });
    }
    @Test void refusesUnexpectedSchemaAndVersionWithoutCreatingHistoryTable() throws Exception {
        database(source->{
            legacy(source);var jdbc=new JdbcTemplate(source);
            jdbc.execute("ALTER TABLE reports DROP COLUMN question");
            assertThrows(IllegalStateException.class,()->JavaMigrations.migrate(source,true));
            assertNull(jdbc.queryForObject("SELECT to_regclass('public.flyway_schema_history')::text",String.class));
        });
        database(source->{
            legacy(source);var jdbc=new JdbcTemplate(source);jdbc.update("UPDATE alembic_version SET version_num='unknown'");
            assertThrows(IllegalStateException.class,()->JavaMigrations.migrate(source,true));
            assertNull(jdbc.queryForObject("SELECT to_regclass('public.flyway_schema_history')::text",String.class));
        });
    }
    @Test void actualAlembicCreatedTestDatabaseMatchesBaselineContract() throws Exception {
        assertEquals(0,JavaMigrations.migrate(admin(),true));
    }
}
