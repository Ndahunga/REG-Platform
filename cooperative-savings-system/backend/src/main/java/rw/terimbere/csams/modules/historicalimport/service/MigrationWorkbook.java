package rw.terimbere.csams.modules.historicalimport.service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import rw.terimbere.csams.modules.historicalimport.dto.HistoricalImportError;
import rw.terimbere.csams.modules.historicalimport.entity.HistoricalMigrationSheet;

/** Raw cell values of the three migration sheets, before any business validation. */
final class MigrationWorkbook {

    private final Map<HistoricalMigrationSheet, List<Row>> sheets = new EnumMap<>(HistoricalMigrationSheet.class);
    private final List<HistoricalImportError> workbookErrors = new ArrayList<>();
    private rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportKind kind = rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportKind.OPENING_BALANCE;

    rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportKind kind() {
        return kind;
    }

    void kind(rw.terimbere.csams.modules.historicalimport.entity.HistoricalImportKind kind) {
        this.kind = kind;
    }

    Map<HistoricalMigrationSheet, List<Row>> sheets() {
        return sheets;
    }

    List<HistoricalImportError> workbookErrors() {
        return workbookErrors;
    }

    List<Row> rows(HistoricalMigrationSheet sheet) {
        return sheets.getOrDefault(sheet, List.of());
    }

    record Row(HistoricalMigrationSheet sheet, int rowNumber, Map<String, String> cells) {
        String get(String header) {
            String value = cells.get(HistoricalMigrationSheet.normalizeHeader(header));
            return value == null ? "" : value.trim();
        }
    }
}
