package com.fongmi.android.tv.db;

import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * AppDatabase keeps fallbackToDestructiveMigration(true), so a version step without a migration silently wipes
 * sites, history, favorites and configs on upgrade. These guards fail the build instead.
 */
public class MigrationsTest {

    // Oldest version with a hand-written migration; upgrades from below it are destructive by design.
    private static final int FIRST_MIGRATED_VERSION = 30;

    private static final Pattern SCHEMA_SQL = Pattern.compile("\"(tableName|createSql)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern CREATE_TABLE = Pattern.compile("(?i)^CREATE TABLE (?:IF NOT EXISTS )?`?(\\w+)`?");
    private static final Pattern CREATE_INDEX = Pattern.compile("(?i)^CREATE (?:UNIQUE )?INDEX (?:IF NOT EXISTS )?`?(\\w+)`?");
    private static final Pattern DROP_TABLE = Pattern.compile("(?i)^DROP TABLE (?:IF EXISTS )?`?(\\w+)`?");
    private static final Pattern RENAME_TABLE = Pattern.compile("(?i)^ALTER TABLE `?(\\w+)`? RENAME TO `?(\\w+)`?");

    private static File moduleFile(String path) {
        File file = new File(path);
        return file.exists() ? file : new File("app", path);
    }

    private static String schemaJson(int version) throws Exception {
        File file = moduleFile("schemas/" + AppDatabase.class.getName() + "/" + version + ".json");
        assertTrue("Missing exported Room schema " + file.getPath() + "; build the app so Room exports it and commit it", file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static List<Migration> declaredMigrations() throws Exception {
        List<Migration> items = new ArrayList<>();
        for (Field field : Migrations.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Migration.class.isAssignableFrom(field.getType())) items.add((Migration) field.get(null));
        }
        return items;
    }

    @Test
    public void currentVersionSchemaIsExported() throws Exception {
        String json = schemaJson(AppDatabase.VERSION);
        Matcher version = Pattern.compile("\"version\"\\s*:\\s*(\\d+)").matcher(json);
        assertTrue(version.find());
        assertEquals(AppDatabase.VERSION, Integer.parseInt(version.group(1)));
        assertTrue(json.contains("\"identityHash\""));
    }

    @Test
    public void everyVersionStepHasRegisteredMigration() {
        Map<Integer, Migration> byStart = new HashMap<>();
        for (Migration migration : Migrations.ALL) {
            assertNotNull("Null entry in Migrations.ALL", migration);
            assertTrue("Migration " + migration.startVersion + "->" + migration.endVersion + " must move forward", migration.endVersion > migration.startVersion);
            assertTrue("Migration " + migration.startVersion + "->" + migration.endVersion + " exceeds AppDatabase.VERSION " + AppDatabase.VERSION, migration.endVersion <= AppDatabase.VERSION);
            assertNull("Duplicate migration from " + migration.startVersion, byStart.put(migration.startVersion, migration));
        }
        int version = FIRST_MIGRATED_VERSION;
        while (version < AppDatabase.VERSION) {
            Migration next = byStart.get(version);
            assertNotNull("No registered migration from version " + version + " toward AppDatabase.VERSION " + AppDatabase.VERSION + "; fallbackToDestructiveMigration would wipe user data", next);
            version = next.endVersion;
        }
        assertEquals(AppDatabase.VERSION, version);
    }

    @Test
    public void everyDeclaredMigrationIsRegistered() throws Exception {
        List<Migration> registered = Arrays.asList(Migrations.ALL);
        for (Migration migration : declaredMigrations()) {
            assertTrue("Migration " + migration.startVersion + "->" + migration.endVersion + " is declared but missing from Migrations.ALL", registered.contains(migration));
        }
        String source = new String(Files.readAllBytes(moduleFile("src/main/java/com/fongmi/android/tv/db/AppDatabase.java").toPath()), StandardCharsets.UTF_8);
        assertTrue("AppDatabase must register Migrations.ALL", source.contains(".addMigrations(Migrations.ALL)"));
    }

    @Test
    public void tablesRebuiltByMigrationsMatchCurrentSchema() throws Exception {
        Map<String, String> expected = new HashMap<>();
        Matcher sql = SCHEMA_SQL.matcher(schemaJson(AppDatabase.VERSION));
        String table = null;
        while (sql.find()) {
            String value = sql.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
            if (sql.group(1).equals("tableName")) table = value;
            else expected.put(key(value.replace("${TABLE_NAME}", table)), value.replace("${TABLE_NAME}", table));
        }
        Map<String, String> actual = new LinkedHashMap<>();
        for (String statement : replay()) {
            Matcher m;
            if ((m = CREATE_TABLE.matcher(statement)).find()) actual.put("table:" + m.group(1), statement);
            else if ((m = CREATE_INDEX.matcher(statement)).find()) actual.put("index:" + m.group(1), statement);
            else if ((m = DROP_TABLE.matcher(statement)).find()) actual.remove("table:" + m.group(1));
            else if ((m = RENAME_TABLE.matcher(statement)).find()) {
                String create = actual.remove("table:" + m.group(1));
                if (create != null) actual.put("table:" + m.group(2), create.replaceFirst("`?" + m.group(1) + "`?", "`" + m.group(2) + "`"));
            }
        }
        assertFalse(actual.isEmpty());
        for (Map.Entry<String, String> entry : actual.entrySet()) {
            String schema = expected.get(entry.getKey());
            assertNotNull("Migration creates " + entry.getKey() + " which is absent from schema " + AppDatabase.VERSION, schema);
            assertEquals("Migration SQL for " + entry.getKey() + " differs from the exported schema", normalize(schema), normalize(entry.getValue()));
        }
    }

    private static String key(String statement) {
        Matcher m = CREATE_TABLE.matcher(statement);
        if (m.find()) return "table:" + m.group(1);
        m = CREATE_INDEX.matcher(statement);
        assertTrue(statement, m.find());
        return "index:" + m.group(1);
    }

    private static String normalize(String statement) {
        return statement.replace("`", "").replaceAll("(?i)IF NOT EXISTS ", "").replaceAll("\\s+", " ").trim().toUpperCase();
    }

    private static List<String> replay() {
        List<String> statements = new ArrayList<>();
        SupportSQLiteDatabase db = (SupportSQLiteDatabase) Proxy.newProxyInstance(SupportSQLiteDatabase.class.getClassLoader(), new Class<?>[]{SupportSQLiteDatabase.class}, (proxy, method, args) -> {
            if (method.getName().equals("execSQL")) {
                statements.add((String) args[0]);
                return null;
            }
            throw new UnsupportedOperationException("Recording database only supports execSQL, not " + method.getName());
        });
        List<Migration> ordered = new ArrayList<>(Arrays.asList(Migrations.ALL));
        ordered.sort((a, b) -> Integer.compare(a.startVersion, b.startVersion));
        for (Migration migration : ordered) migration.migrate(db);
        return statements;
    }
}
