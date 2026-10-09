package com.is2.detective_manager.configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Pool de threads para correr los steps de un chunk en paralelo. */
@Configuration
public class AsyncConfig {

  /**
   * Pool para el análisis paralelo. Los steps corren acá (llamadas al ML, lentas). Se dimensiona
   * pensando en (steps por chunk) x (requests concurrentes esperados).
   */
  @Bean(name = "analysisExecutor", destroyMethod = "shutdown")
  public ExecutorService analysisExecutor() {
    return Executors.newFixedThreadPool(1);
  }
}
