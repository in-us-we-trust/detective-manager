package com.is2.detective_manager;

import jakarta.annotation.PostConstruct;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DbTestUtils {

  @Autowired private DataSource dataSource;

  private JdbcTemplate jdbcTemplate;

  @PostConstruct
  private void init() {
    this.jdbcTemplate = new JdbcTemplate(dataSource);
  }

  public void clearTables() {
    jdbcTemplate.execute(
        "TRUNCATE TABLE face_embeddings, persons, events RESTART IDENTITY CASCADE");
  }
}
