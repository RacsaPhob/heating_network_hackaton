package ru.heatnet.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persists dataset and job metadata in the configured PostgreSQL or H2 database. */
@Repository
public class RecordStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public RecordStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("CREATE TABLE IF NOT EXISTS heatnet_records (id VARCHAR(36) PRIMARY KEY, kind VARCHAR(16) NOT NULL, payload TEXT NOT NULL)");
    }

    public synchronized void save(String id, String kind, Object record) {
        validate(id, kind);
        try {
            String json = mapper.writeValueAsString(record);
            if (jdbc.update("UPDATE heatnet_records SET payload = ? WHERE id = ? AND kind = ?", json, id, kind) == 0) {
                jdbc.update("INSERT INTO heatnet_records (id, kind, payload) VALUES (?, ?, ?)", id, kind, json);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось сохранить запись", ex);
        }
    }

    public JsonNode get(String id, String kind) {
        validate(id, kind);
        List<String> rows = jdbc.queryForList("SELECT payload FROM heatnet_records WHERE id = ? AND kind = ?", String.class, id, kind);
        if (rows.isEmpty()) throw new NoSuchElementException("Запись не найдена: " + id);
        return parse(rows.get(0));
    }

    public List<JsonNode> list(String kind) {
        if (!kind.equals("dataset") && !kind.equals("job")) throw new IllegalArgumentException("Некорректный тип записи");
        return jdbc.query("SELECT payload FROM heatnet_records WHERE kind = ?", (rs, row) -> parse(rs.getString(1)), kind);
    }

    private void validate(String id, String kind) {
        if (!id.matches("[a-f0-9-]{36}") || (!kind.equals("dataset") && !kind.equals("job"))) {
            throw new IllegalArgumentException("Некорректный ID или тип записи");
        }
    }

    private JsonNode parse(String json) {
        try { return mapper.readTree(json); }
        catch (IOException ex) { throw new IllegalStateException("Повреждена сохранённая запись", ex); }
    }
}
