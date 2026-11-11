
package com.google.refine.commands.column;

import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.refine.browsing.EngineConfig;
import com.google.refine.commands.CommandTestBase;
import com.google.refine.model.AbstractOperation;
import com.google.refine.model.Project;
import com.google.refine.operations.OnError;
import com.google.refine.operations.column.ColumnTranslationOperation;

public class TranslateColumnCommandTests extends CommandTestBase {

    private TranslateColumnCommand command;
    private Project project;
    private EngineConfig engineConfig = EngineConfig.deserialize("{\"mode\":\"row-based\"}");

    @BeforeMethod
    public void setUp() throws Exception {
        command = new TranslateColumnCommand();
        project = createProjectWithColumns("TranslateTest", "review");
    }

    @Test
    public void testCreateOperation() throws Exception {
        when(request.getParameter("baseColumnName")).thenReturn("review");
        when(request.getParameter("sourceLang")).thenReturn("auto");
        when(request.getParameter("targetLang")).thenReturn("es");
        when(request.getParameter("newColumnName")).thenReturn("review_es");
        when(request.getParameter("onError")).thenReturn("set-to-blank");

        AbstractOperation op = command.createOperation(project, request, engineConfig);

        assertTrue(op instanceof ColumnTranslationOperation);
        ColumnTranslationOperation cop = (ColumnTranslationOperation) op;
        assertEquals(cop.getBaseColumnName(), "review");
        assertEquals(cop.getSourceLang(), "auto");
        assertEquals(cop.getTargetLang(), "es");
        assertEquals(cop.getNewColumnName(), "review_es");
        assertEquals(cop.getOnError(), OnError.SetToBlank);
    }

    @Test
    public void testOnErrorKeepOriginal() throws Exception {
        when(request.getParameter("baseColumnName")).thenReturn("review");
        when(request.getParameter("sourceLang")).thenReturn("en");
        when(request.getParameter("targetLang")).thenReturn("zh");
        when(request.getParameter("newColumnName")).thenReturn("review_zh");
        when(request.getParameter("onError")).thenReturn("keep-original");

        AbstractOperation op = command.createOperation(project, request, engineConfig);
        assertEquals(((ColumnTranslationOperation) op).getOnError(), OnError.KeepOriginal);
    }

    @Test
    public void testOnErrorStoreError() throws Exception {
        when(request.getParameter("baseColumnName")).thenReturn("review");
        when(request.getParameter("sourceLang")).thenReturn("en");
        when(request.getParameter("targetLang")).thenReturn("zh");
        when(request.getParameter("newColumnName")).thenReturn("review_zh");
        when(request.getParameter("onError")).thenReturn("store-error");

        AbstractOperation op = command.createOperation(project, request, engineConfig);
        assertEquals(((ColumnTranslationOperation) op).getOnError(), OnError.StoreError);
    }
}
