package com.google.refine.operations.column;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.commons.lang.Validate;

import com.google.refine.browsing.Engine;
import com.google.refine.browsing.EngineConfig;
import com.google.refine.browsing.FilteredRows;
import com.google.refine.browsing.RowVisitor;
import com.google.refine.expr.EvalError;
import com.google.refine.history.HistoryEntry;
import com.google.refine.model.Cell;
import com.google.refine.model.Column;
import com.google.refine.model.ColumnsDiff;
import com.google.refine.model.Project;
import com.google.refine.model.Row;
import com.google.refine.model.changes.CellAtRow;
import com.google.refine.model.changes.ColumnAdditionChange;
import com.google.refine.operations.EngineDependentOperation;
import com.google.refine.operations.OnError;
import com.google.refine.operations.OperationDescription;
import com.google.refine.process.LongRunningProcess;
import com.google.refine.process.Process;
import com.google.refine.translation.LibreTranslateClient;
import com.google.refine.translation.TranslationConfig;
import com.google.refine.translation.TranslationException;
import com.google.refine.translation.TranslationLanguages;

/**
 * Adds a new column whose cells are the translated contents of a source column.
 *
 * <p>The new column is inserted immediately to the right of the source column.
 * Row order and all other columns are preserved. Translation is done by a
 * LibreTranslate server whose URL and optional API key are read from JVM
 * system properties or environment variables at run time (never stored in the
 * serialized operation JSON).
 *
 * <p>Edge-case behaviour (all documented in {@code Assignment3/design.md}):
 * <ul>
 *   <li>Blank / null cells are skipped and left blank in the new column.</li>
 *   <li>Non-string cells (numbers, dates, …) are copied unchanged.</li>
 *   <li>Identical text values are translated only once per run (cache).</li>
 *   <li>If the requested column name is already taken, a numeric suffix is
 *       appended (e.g. {@code review_es2}) without warning the user at this
 *       layer — the frontend dialog warns before submit.</li>
 *   <li>Translation failures per cell follow the {@link OnError} policy.</li>
 * </ul>
 */
public class ColumnTranslationOperation extends EngineDependentOperation {

    final protected String _baseColumnName;
    final protected String _sourceLang;
    final protected String _targetLang;
    final protected String _newColumnName;
    final protected OnError _onError;

    @JsonCreator
    public ColumnTranslationOperation(
            @JsonProperty("engineConfig") EngineConfig engineConfig,
            @JsonProperty("baseColumnName") String baseColumnName,
            @JsonProperty("sourceLang") String sourceLang,
            @JsonProperty("targetLang") String targetLang,
            @JsonProperty("newColumnName") String newColumnName,
            @JsonProperty("onError") OnError onError) {
        super(engineConfig);
        _baseColumnName = baseColumnName;
        _sourceLang = sourceLang;
        _targetLang = targetLang;
        _newColumnName = newColumnName;
        _onError = onError;
    }

    @Override
    public void validate() {
        super.validate();
        Validate.notNull(_baseColumnName, "Missing base column name");
        Validate.notNull(_sourceLang, "Missing source language");
        Validate.isTrue(TranslationLanguages.isValidSource(_sourceLang),
                "Unknown source language: " + _sourceLang);
        Validate.notNull(_targetLang, "Missing target language");
        Validate.isTrue(TranslationLanguages.isValidTarget(_targetLang),
                "Unknown target language: " + _targetLang);
        Validate.isTrue(!_sourceLang.equals(_targetLang),
                "Source and target language must be different");
        Validate.notNull(_newColumnName, "Missing new column name");
        Validate.notNull(_onError, "Missing 'on error' behavior");
    }

    @JsonProperty("baseColumnName")
    public String getBaseColumnName() {
        return _baseColumnName;
    }

    @JsonProperty("sourceLang")
    public String getSourceLang() {
        return _sourceLang;
    }

    @JsonProperty("targetLang")
    public String getTargetLang() {
        return _targetLang;
    }

    @JsonProperty("newColumnName")
    public String getNewColumnName() {
        return _newColumnName;
    }

    @JsonProperty("onError")
    public OnError getOnError() {
        return _onError;
    }

    @Override
    protected String getBriefDescription(Project project) {
        return OperationDescription.column_translation_brief(
                _newColumnName, _baseColumnName, _sourceLang, _targetLang);
    }

    @Override
    public Optional<ColumnsDiff> getColumnsDiff() {
        // The actual column name is computed at run time (deduplication may append a suffix),
        // so we cannot predict it here. Returning empty signals "unpredictable" to callers.
        return Optional.empty();
    }

    @Override
    public ColumnTranslationOperation renameColumns(Map<String, String> newColumnNames) {
        return new ColumnTranslationOperation(
                _engineConfig.renameColumnDependencies(newColumnNames),
                newColumnNames.getOrDefault(_baseColumnName, _baseColumnName),
                _sourceLang,
                _targetLang,
                newColumnNames.getOrDefault(_newColumnName, _newColumnName),
                _onError);
    }

    @Override
    public Process createProcess(Project project, Properties options) throws Exception {
        Engine engine = createEngine(project);
        engine.initializeFromConfig(_engineConfig);
        return new ColumnTranslationProcess(project, engine, getBriefDescription(null));
    }

    public class ColumnTranslationProcess extends LongRunningProcess implements Runnable {

        final protected Project _project;
        final protected Engine _engine;
        final protected long _historyEntryID;

        public ColumnTranslationProcess(Project project, Engine engine, String description) {
            super(description);
            _project = project;
            _engine = engine;
            _historyEntryID = HistoryEntry.allocateID();
        }

        @Override
        protected Runnable getRunnable() {
            return this;
        }

        @Override
        public void run() {
            Column column = _project.columnModel.getColumnByName(_baseColumnName);
            if (column == null) {
                _project.processManager.onFailedProcess(this,
                        new Exception("No column named " + _baseColumnName));
                return;
            }

            // Decide the actual column name, deduplicating if needed (Decision A).
            String actualNewName = _project.columnModel.getUnduplicatedColumnName(_newColumnName);
            int insertIndex = _project.columnModel.columns.indexOf(column) + 1;

            String url = TranslationConfig.getUrl();
            String apiKey = TranslationConfig.getApiKey();
            LibreTranslateClient client = new LibreTranslateClient(url, apiKey);

            // Collect the rows that pass the current engine filter (Decision B).
            List<RowToTranslate> toTranslate = new ArrayList<>();
            final int cellIndex = column.getCellIndex();
            FilteredRows filteredRows = _engine.getAllFilteredRows();
            filteredRows.accept(_project, new RowVisitor() {

                @Override
                public void start(Project project) {
                }

                @Override
                public void end(Project project) {
                }

                @Override
                public boolean visit(Project project, int rowIndex, Row row) {
                    toTranslate.add(new RowToTranslate(rowIndex, row.getCell(cellIndex)));
                    return false;
                }
            });

            int count = toTranslate.size();
            List<CellAtRow> results = new ArrayList<>(count);
            // Cache: only successful translations are reused (Decision E).
            Map<String, String> cache = new HashMap<>();

            for (int i = 0; i < count; i++) {
                RowToTranslate item = toTranslate.get(i);
                Serializable newValue = translateCell(item.cell, client, cache);
                if (newValue != null) {
                    results.add(new CellAtRow(item.rowIndex, new Cell(newValue, null)));
                }
                _progress = (i + 1) * 100 / count;
                if (_canceled) {
                    break;
                }
            }

            if (!_canceled) {
                String description = OperationDescription.column_translation_desc(
                        actualNewName, results.size(), _baseColumnName, _sourceLang, _targetLang);
                HistoryEntry historyEntry = new HistoryEntry(
                        _historyEntryID,
                        _project,
                        description,
                        ColumnTranslationOperation.this,
                        new ColumnAdditionChange(actualNewName, insertIndex, results));
                _project.history.addEntry(historyEntry);
                _project.processManager.onDoneProcess(this);
            }
        }

        /**
         * Returns the value to store in the new column for a single source cell,
         * or {@code null} to leave the new cell blank.
         */
        private Serializable translateCell(
                Cell cell, LibreTranslateClient client, Map<String, String> cache) {
            // Null / blank cell → skip, leave blank (Decision C).
            if (cell == null || cell.value == null) {
                return null;
            }

            // Non-string cell → copy value unchanged (Decision C).
            if (!(cell.value instanceof String)) {
                return cell.value;
            }

            String text = (String) cell.value;
            if (text.isBlank()) {
                return null;
            }

            // Cache lookup: only successful results are cached (Decision E).
            String cacheKey = _sourceLang + "\u0000" + _targetLang + "\u0000" + text;
            if (cache.containsKey(cacheKey)) {
                return cache.get(cacheKey);
            }

            try {
                String translated = client.translate(text, _sourceLang, _targetLang);
                cache.put(cacheKey, translated);
                return translated;
            } catch (TranslationException e) {
                // Apply on-error policy (Decision 7 / Decision G).
                return switch (_onError) {
                    case StoreError -> new EvalError(e.getMessage());
                    case KeepOriginal -> text;
                    default -> null; // SetToBlank
                };
            }
        }
    }

    private static class RowToTranslate {

        final int rowIndex;
        final Cell cell;

        RowToTranslate(int rowIndex, Cell cell) {
            this.rowIndex = rowIndex;
            this.cell = cell;
        }
    }
}
