package com.google.refine.commands.column;

import javax.servlet.http.HttpServletRequest;

import com.google.refine.browsing.EngineConfig;
import com.google.refine.commands.EngineDependentCommand;
import com.google.refine.model.AbstractOperation;
import com.google.refine.model.Project;
import com.google.refine.operations.cell.TextTransformOperation;
import com.google.refine.operations.column.ColumnTranslationOperation;

/**
 * Creates a {@link ColumnTranslationOperation} from an HTTP request.
 *
 * <p>The engine config sent by the browser decides which rows are translated:
 * the current facet selection, or an empty config for all rows (Decision B).
 */
public class TranslateColumnCommand extends EngineDependentCommand {

    @Override
    protected AbstractOperation createOperation(Project project,
            HttpServletRequest request, EngineConfig engineConfig) throws Exception {
        String baseColumnName = request.getParameter("baseColumnName");
        String sourceLang = request.getParameter("sourceLang");
        String targetLang = request.getParameter("targetLang");
        String newColumnName = request.getParameter("newColumnName");
        String onError = request.getParameter("onError");

        return new ColumnTranslationOperation(
                engineConfig,
                baseColumnName,
                sourceLang,
                targetLang,
                newColumnName,
                TextTransformOperation.stringToOnError(onError));
    }
}
