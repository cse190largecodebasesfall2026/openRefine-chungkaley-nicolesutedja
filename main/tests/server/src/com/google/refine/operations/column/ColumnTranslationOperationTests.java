package com.google.refine.operations.column;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.databind.node.TextNode;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import com.google.refine.RefineTest;
import com.google.refine.browsing.EngineConfig;
import com.google.refine.expr.EvalError;
import com.google.refine.model.AbstractOperation;
import com.google.refine.model.Column;
import com.google.refine.model.Project;
import com.google.refine.operations.OnError;
import com.google.refine.operations.OperationRegistry;
import com.google.refine.translation.TranslationConfig;
import com.google.refine.util.ParsingUtilities;
import com.google.refine.util.TestUtils;

public class ColumnTranslationOperationTests extends RefineTest {

    private static final String ENGINE_ROW_BASED = "{\"mode\":\"row-based\"}";
    private static final EngineConfig ENGINE_CONFIG = EngineConfig.deserialize(ENGINE_ROW_BASED);

    /** JSON response returned by the mock LibreTranslate server for any request. */
    private MockWebServer server;

    @BeforeTest
    public void registerOperation() {
        OperationRegistry.registerOperation(getCoreModule(), "column-translation",
                ColumnTranslationOperation.class);
    }

    @BeforeMethod
    public void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
        // Point the operation at the mock server.
        System.setProperty(TranslationConfig.URL_PROPERTY, server.url("/").toString());
        System.clearProperty(TranslationConfig.KEY_PROPERTY);
    }

    @AfterMethod
    public void stopServer() throws Exception {
        System.clearProperty(TranslationConfig.URL_PROPERTY);
        server.close();
    }

    // ── Helper to build a mock translation response ───────────────────────────

    private MockResponse translationResponse(String translatedText) {
        return new MockResponse.Builder()
                .body("{\"translatedText\":\"" + translatedText + "\"}")
                .build();
    }

    private MockResponse errorResponse(int code) {
        return new MockResponse.Builder()
                .code(code)
                .body("{\"error\":\"mock error\"}")
                .build();
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    @Test
    public void testSerialization() throws Exception {
        String description = new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "auto", "es", "review_es", OnError.SetToBlank)
                .getBriefDescription(null);

        String json = "{\"op\":\"core/column-translation\","
                + "\"description\":" + new TextNode(description).toString() + ","
                + "\"engineConfig\":{\"mode\":\"row-based\",\"facets\":[]},"
                + "\"baseColumnName\":\"review\","
                + "\"sourceLang\":\"auto\","
                + "\"targetLang\":\"es\","
                + "\"newColumnName\":\"review_es\","
                + "\"onError\":\"set-to-blank\"}";

        TestUtils.isSerializedTo(
                ParsingUtilities.mapper.readValue(json, ColumnTranslationOperation.class), json);
    }

    @Test
    public void testSerializedOperationContainsNoUrlOrKey() throws Exception {
        System.setProperty(TranslationConfig.URL_PROPERTY, "http://secret-server:9999");
        System.setProperty(TranslationConfig.KEY_PROPERTY, "top-secret-key");

        ColumnTranslationOperation op = new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "en", "es", "review_es", OnError.SetToBlank);

        String json = ParsingUtilities.mapper.writeValueAsString(op);
        assertTrue(!json.contains("secret-server"), "URL must not appear in JSON: " + json);
        assertTrue(!json.contains("top-secret-key"), "Key must not appear in JSON: " + json);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Test
    public void testValidateRejectsNullBaseColumn() {
        AbstractOperation op = new ColumnTranslationOperation(
                ENGINE_CONFIG, null, "en", "es", "result", OnError.SetToBlank);
        assertThrows(IllegalArgumentException.class, op::validate);
    }

    @Test
    public void testValidateRejectsUnknownTargetLang() {
        AbstractOperation op = new ColumnTranslationOperation(
                ENGINE_CONFIG, "col", "en", "fr", "result", OnError.SetToBlank);
        assertThrows(IllegalArgumentException.class, op::validate);
    }

    @Test
    public void testValidateRejectsSameSourceAndTarget() {
        AbstractOperation op = new ColumnTranslationOperation(
                ENGINE_CONFIG, "col", "en", "en", "result", OnError.SetToBlank);
        assertThrows(IllegalArgumentException.class, op::validate);
    }

    @Test
    public void testValidateRejectsNullNewColumnName() {
        AbstractOperation op = new ColumnTranslationOperation(
                ENGINE_CONFIG, "col", "auto", "es", null, OnError.SetToBlank);
        assertThrows(IllegalArgumentException.class, op::validate);
    }

    // ── Normal translation ─────────────────────────────────────────────────────

    @Test
    public void testBasicTranslation() throws Exception {
        Project project = createProject(
                new String[] { "review" },
                new Serializable[][] { { "Hello" }, { "World" } });

        server.enqueue(translationResponse("Hola"));
        server.enqueue(translationResponse("Mundo"));

        ColumnTranslationOperation op = new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "en", "es", "review_es", OnError.SetToBlank);
        runOperation(op, project);

        // New column inserted to the right of the source
        List<String> colNames = project.columnModel.columns.stream()
                .map(Column::getName).toList();
        assertEquals(colNames, List.of("review", "review_es"));

        Column newCol = project.columnModel.getColumnByName("review_es");
        int ci = newCol.getCellIndex();
        assertEquals(project.rows.get(0).getCellValue(ci), "Hola");
        assertEquals(project.rows.get(1).getCellValue(ci), "Mundo");
    }

    @Test
    public void testSourceColumnPreserved() throws Exception {
        Project project = createProject(
                new String[] { "review" },
                new Serializable[][] { { "Hello" } });

        server.enqueue(translationResponse("Hola"));

        runOperation(new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "en", "es", "review_es", OnError.SetToBlank), project);

        // Source column still present and unchanged
        Column src = project.columnModel.getColumnByName("review");
        assertEquals(project.rows.get(0).getCellValue(src.getCellIndex()), "Hello");
    }

    @Test
    public void testNewColumnIsInsertedRightOfSource() throws Exception {
        Project project = createProject(
                new String[] { "id", "review", "lang" },
                new Serializable[][] { { 1, "Hello", "en" } });

        server.enqueue(translationResponse("Hola"));

        runOperation(new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "en", "es", "review_es", OnError.SetToBlank), project);

        List<String> colNames = project.columnModel.columns.stream()
                .map(Column::getName).toList();
        assertEquals(colNames, List.of("id", "review", "review_es", "lang"));
    }

    // ── Blank and non-text cells (Decision C) ─────────────────────────────────

    @Test
    public void testBlankCellIsSkipped() throws Exception {
        Project project = createProject(
                new String[] { "review" },
                new Serializable[][] { { "" }, { null }, { "   " } });

        // No server calls expected
        runOperation(new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "en", "es", "review_es", OnError.SetToBlank), project);

        assertEquals(server.getRequestCount(), 0, "Blank cells must not trigger API calls");

        Column newCol = project.columnModel.getColumnByName("review_es");
        int ci = newCol.getCellIndex();
        assertNull(project.rows.get(0).getCellValue(ci));
        assertNull(project.rows.get(1).getCellValue(ci));
        assertNull(project.rows.get(2).getCellValue(ci));
    }

    @Test
    public void testNonTextCellIsCopiedUnchanged() throws Exception {
        Project project = createProject(
                new String[] { "review" },
                new Serializable[][] { { 42 }, { 3.14 } });

        // No server calls expected
        runOperation(new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "en", "es", "review_es", OnError.SetToBlank), project);

        assertEquals(server.getRequestCount(), 0, "Non-text cells must not trigger API calls");

        Column newCol = project.columnModel.getColumnByName("review_es");
        int ci = newCol.getCellIndex();
        assertEquals(project.rows.get(0).getCellValue(ci), 42);
        assertEquals(project.rows.get(1).getCellValue(ci), 3.14);
    }

    // ── On-error behaviour (Decision 7) ───────────────────────────────────────

    @Test
    public void testOnErrorSetToBlank() throws Exception {
        Project project = createProject(
                new String[] { "review" },
                new Serializable[][] { { "Hello" } });

        server.enqueue(errorResponse(500));

        runOperation(new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "en", "es", "review_es", OnError.SetToBlank), project);

        Column newCol = project.columnModel.getColumnByName("review_es");
        assertNull(project.rows.get(0).getCellValue(newCol.getCellIndex()));
    }

    @Test
    public void testOnErrorKeepOriginal() throws Exception {
        Project project = createProject(
                new String[] { "review" },
                new Serializable[][] { { "Hello" } });

        server.enqueue(errorResponse(500));

        runOperation(new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "en", "es", "review_es", OnError.KeepOriginal), project);

        Column newCol = project.columnModel.getColumnByName("review_es");
        assertEquals(project.rows.get(0).getCellValue(newCol.getCellIndex()), "Hello");
    }

    @Test
    public void testOnErrorStoreError() throws Exception {
        Project project = createProject(
                new String[] { "review" },
                new Serializable[][] { { "Hello" } });

        server.enqueue(errorResponse(503));

        runOperation(new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "en", "es", "review_es", OnError.StoreError), project);

        Column newCol = project.columnModel.getColumnByName("review_es");
        Object value = project.rows.get(0).getCellValue(newCol.getCellIndex());
        assertTrue(value instanceof EvalError, "Expected EvalError, got: " + value);
    }

    // ── Caching (Decision E) ───────────────────────────────────────────────────

    @Test
    public void testIdenticalValuesTranslatedOnce() throws Exception {
        Project project = createProject(
                new String[] { "review" },
                new Serializable[][] { { "Hello" }, { "Hello" }, { "Hello" } });

        server.enqueue(translationResponse("Hola")); // only one call expected

        runOperation(new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "en", "es", "review_es", OnError.SetToBlank), project);

        assertEquals(server.getRequestCount(), 1, "Identical values must be translated only once");

        Column newCol = project.columnModel.getColumnByName("review_es");
        int ci = newCol.getCellIndex();
        assertEquals(project.rows.get(0).getCellValue(ci), "Hola");
        assertEquals(project.rows.get(1).getCellValue(ci), "Hola");
        assertEquals(project.rows.get(2).getCellValue(ci), "Hola");
    }

    @Test
    public void testFailedTranslationIsNotCached() throws Exception {
        Project project = createProject(
                new String[] { "review" },
                new Serializable[][] { { "Hello" }, { "Hello" } });

        // First call fails, second succeeds
        server.enqueue(errorResponse(500));
        server.enqueue(translationResponse("Hola"));

        runOperation(new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "en", "es", "review_es", OnError.SetToBlank), project);

        assertEquals(server.getRequestCount(), 2,
                "Failed translations must not be cached — identical value should be retried");

        Column newCol = project.columnModel.getColumnByName("review_es");
        int ci = newCol.getCellIndex();
        assertNull(project.rows.get(0).getCellValue(ci)); // first failed → blank
        assertEquals(project.rows.get(1).getCellValue(ci), "Hola"); // second succeeded
    }

    // ── Name deduplication (Decision A) ───────────────────────────────────────

    @Test
    public void testNameDeduplicationWhenColumnExists() throws Exception {
        Project project = createProject(
                new String[] { "review", "review_es" },
                new Serializable[][] { { "Hello", "existing" } });

        server.enqueue(translationResponse("Hola"));

        runOperation(new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "en", "es", "review_es", OnError.SetToBlank), project);

        // Should have created "review_es2", not overwrote "review_es"
        Column newCol = project.columnModel.getColumnByName("review_es2");
        assertTrue(newCol != null, "Expected column review_es2 to be created");
        assertEquals(project.rows.get(0).getCellValue(newCol.getCellIndex()), "Hola");
        // Original "review_es" must be unchanged
        Column existing = project.columnModel.getColumnByName("review_es");
        assertEquals(project.rows.get(0).getCellValue(existing.getCellIndex()), "existing");
    }

    // ── Undo ──────────────────────────────────────────────────────────────────

    @Test
    public void testUndo() throws Exception {
        Project project = createProject(
                new String[] { "review" },
                new Serializable[][] { { "Hello" } });

        server.enqueue(translationResponse("Hola"));

        runOperation(new ColumnTranslationOperation(
                ENGINE_CONFIG, "review", "en", "es", "review_es", OnError.SetToBlank), project);

        assertTrue(project.columnModel.getColumnByName("review_es") != null);

        project.history.undoRedo(0L);

        assertNull(project.columnModel.getColumnByName("review_es"),
                "Column must be removed after undo");
    }

    // ── Auto-detect source language ───────────────────────────────────────────

    @Test
    public void testAutoDetectSourceLanguage() throws Exception {
        Project project = createProject(
                new String[] { "text" },
                new Serializable[][] { { "Hello" } });

        server.enqueue(translationResponse("Hola"));

        runOperation(new ColumnTranslationOperation(
                ENGINE_CONFIG, "text", "auto", "es", "text_es", OnError.SetToBlank), project);

        mockwebserver3.RecordedRequest req = server.takeRequest();
        String body = req.getBody().utf8();
        assertTrue(body.contains("\"source\":\"auto\""), "Body: " + body);
    }
}
