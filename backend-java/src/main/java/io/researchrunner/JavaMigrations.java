package io.researchrunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Arrays;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Explicit migration entrypoint. Never repair, clean, or automatically baseline an existing database. */
public final class JavaMigrations {
    private JavaMigrations() {}
    public static int migrate(DataSource database,boolean baselineExisting) throws Exception {
        try(var lock=database.getConnection()) {
            try(var statement=lock.createStatement();var rows=statement.executeQuery(
                    "SELECT pg_try_advisory_lock(hashtextextended('researchrunner.java_migrations',0))")) {
                rows.next();if(!rows.getBoolean(1))throw new IllegalStateException("已有 Java 数据库迁移在执行");
            }
            try {
                var flyway=Flyway.configure().dataSource(database).locations("classpath:db/migration")
                        .defaultSchema("public").schemas("public").createSchemas(false)
                        .baselineOnMigrate(false).baselineVersion("1").cleanDisabled(true).load();
                boolean managed=exists(lock,"flyway_schema_history");
                if(!managed && count(lock,"SELECT count(*) FROM pg_tables WHERE schemaname='public'")>0) {
                    if(!baselineExisting)throw new IllegalStateException("已有数据库必须检查后显式使用 --baseline-existing");
                    validateLegacy(lock);
                    flyway.baseline();
                }
                return flyway.migrate().migrationsExecuted;
            } finally {
                try(var statement=lock.createStatement()) {
                    statement.execute("SELECT pg_advisory_unlock(hashtextextended('researchrunner.java_migrations',0))");
                }
            }
        }
    }
    private static void validateLegacy(Connection connection) throws Exception {
        if(!exists(connection,"alembic_version") || count(connection,
                "SELECT count(*) FROM public.alembic_version WHERE version_num='0002_shared_library'")!=1
                || count(connection,"SELECT count(*) FROM public.alembic_version")!=1)
            throw new IllegalStateException("旧库 Alembic 版本不兼容");
        if(count(connection,"SELECT count(*) FROM pg_extension WHERE extname='vector'")!=1)
            throw new IllegalStateException("旧库未安装 pgvector");
        try(var statement=connection.createStatement()) {
            statement.execute("SET search_path TO public, pg_catalog");
            try(var result=statement.executeQuery(resource("/db/legacy_signature.sql"))) {
                result.next();var json=new ObjectMapper();
                var expected=json.readTree(resource("/db/legacy_signature.json"));var actual=json.readTree(result.getString(1));
                // pg_dump round trips CHECK expressions with equivalent casts. Accept only these two frozen signatures.
                if(!expected.equals(actual) && !json.readTree(resource("/db/compatible_signature.json")).equals(actual)) {
                    for(String section:java.util.List.of("columns","constraints","indexes")) {
                        var left=expected.path(section);var right=actual.path(section);
                        if(!left.equals(right)) {
                            for(int i=0;i<Math.max(left.size(),right.size());i++) {
                                if(!left.path(i).equals(right.path(i)))throw new IllegalStateException("旧库结构不一致："+section+" expected="+left.path(i)+" actual="+right.path(i));
                            }
                        }
                    }
                    throw new IllegalStateException("旧库结构不一致，禁止建立基线");
                }
            }
        }
    }
    private static boolean exists(Connection connection,String name) throws Exception {
        try(var statement=connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1,"public."+name);try(var result=statement.executeQuery()){result.next();return result.getBoolean(1);}
        }
    }
    private static long count(Connection connection,String sql) throws Exception {
        try(var statement=connection.createStatement();var result=statement.executeQuery(sql)){result.next();return result.getLong(1);}
    }
    private static String resource(String path) throws Exception {
        try(var input=JavaMigrations.class.getResourceAsStream(path)) {
            if(input==null)throw new IllegalStateException("缺少迁移资源");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    public static int run(String[] args) {
        try {
            if(args.length>1 || (args.length==1 && !args[0].equals("--baseline-existing")))
                throw new IllegalArgumentException("用法：migrate [--baseline-existing]");
            var env=new StandardEnvironment();
            var database=new DriverManagerDataSource(env.getProperty("JAVA_DATABASE_URL","jdbc:postgresql://postgres:5432/gpt_researcher"),
                    env.getProperty("POSTGRES_USER","gpt_researcher"),env.getProperty("POSTGRES_PASSWORD",""));
            int count=migrate(database,Arrays.asList(args).contains("--baseline-existing"));
            System.out.println("ResearchRunner Java 数据库迁移成功，执行迁移数："+count);return 0;
        } catch(Exception error){System.err.println("ResearchRunner Java 数据库迁移失败："+error.getClass().getSimpleName());return 1;}
    }
}
