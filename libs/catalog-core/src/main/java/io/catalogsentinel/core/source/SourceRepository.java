package io.catalogsentinel.core.source;

import io.catalogsentinel.core.common.JdbcValues;
import io.catalogsentinel.domain.source.SourceRole;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.Optional;

@Repository
public class SourceRepository {

    private static final String COLUMNS = "source_id, code, name, role, enabled, created_at, updated_at";

    private static final String INSERT_SQL = """
            INSERT INTO catalog_sources (code, name, role, enabled)
            VALUES (:code, :name, :role, :enabled)
            ON CONFLICT (code) DO NOTHING
            RETURNING %s
            """.formatted(COLUMNS);

    private static final RowMapper<CatalogSource> MAPPER = (rs, i) -> new CatalogSource(
            rs.getLong("source_id"),
            rs.getString("code"),
            rs.getString("name"),
            SourceRole.valueOf(rs.getString("role")),
            rs.getBoolean("enabled"),
            JdbcValues.instant(rs, "created_at"),
            JdbcValues.instant(rs, "updated_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public SourceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a source; returns empty when the code is already taken (no exception-driven control flow). */
    public Optional<CatalogSource> insertIfCodeAvailable(String code, String name, SourceRole role, boolean enabled) {
        List<CatalogSource> rows = jdbc.query(INSERT_SQL, new MapSqlParameterSource()
                .addValue("code", code)
                .addValue("name", name)
                .addValue("role", role.name())
                .addValue("enabled", enabled), MAPPER);
        return rows.stream().findFirst();
    }

    public Optional<CatalogSource> findById(long sourceId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM catalog_sources WHERE source_id = :id",
                new MapSqlParameterSource("id", sourceId), MAPPER).stream().findFirst();
    }

    public Optional<CatalogSource> findByCode(String code) {
        return jdbc.query("SELECT " + COLUMNS + " FROM catalog_sources WHERE code = :code",
                new MapSqlParameterSource("code", code), MAPPER).stream().findFirst();
    }

    /** Keyset page ordered by source_id ascending (sources are few; stable ordering matters more than recency). */
    public List<CatalogSource> list(SourceRole role, Boolean enabled, Long afterId, int fetchSize) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM catalog_sources WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource("limit", fetchSize);
        if (role != null) {
            sql.append(" AND role = :role");
            params.addValue("role", role.name());
        }
        if (enabled != null) {
            sql.append(" AND enabled = :enabled");
            params.addValue("enabled", enabled);
        }
        if (afterId != null) {
            sql.append(" AND source_id > :afterId");
            params.addValue("afterId", afterId, Types.BIGINT);
        }
        sql.append(" ORDER BY source_id LIMIT :limit");
        return jdbc.query(sql.toString(), params, MAPPER);
    }

    public Optional<CatalogSource> updateEnabled(long sourceId, boolean enabled) {
        return jdbc.query("UPDATE catalog_sources SET enabled = :enabled WHERE source_id = :id RETURNING " + COLUMNS,
                new MapSqlParameterSource().addValue("id", sourceId).addValue("enabled", enabled), MAPPER)
                .stream().findFirst();
    }
}
