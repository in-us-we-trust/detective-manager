# detective-manager — rama `validacion`

Configuración experimental para las pruebas del TPP. En esta rama, el perfil por defecto es `validacion` y la única dependencia externa en ejecución es **ml-service**.

| Componente | Configuración de validación |
|------------|----------------------------|
| Persistencia | H2 embebido en memoria; conserva JPA, tracks, assessments, personas y embeddings |
| Imágenes | Archivos locales en `validation-data/images/` |
| Notificaciones | Mensajes JSONL en `validation-data/notifications.jsonl` |
| Análisis | Clientes reales de ml-service para imagen, audio y video |

Esta configuración no está destinada a PRD. Los perfiles `local`, `test` y `prod` mantienen las integraciones originales cuando se activan explícitamente. No se modifican los umbrales, señales ni reglas del evaluador.

Con Java 17 y Maven, y ml-service disponible en el puerto 8000:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=validacion
```

Con Docker y ml-service ejecutándose en el host:

```bash
docker compose -f docker-compose.validacion.yml up --build
```

Ambas opciones exponen detective-manager en `http://localhost:8088`. Swagger: `http://localhost:8088/docs`. Para otra ubicación del ML, configurá `ML_SERVICE_BASE_URL`.

El endpoint real es `POST /api/detective-manager/v1/orchestrator/events`, con multipart: `trackId`, `deviceId`, `mediaType` (`IMAGE`, `AUDIO` o `VIDEO`) y `file` binario. No requiere registrar previamente el dispositivo.

```bash
python3 scripts/send-validation-event.py --file /ruta/imagen.jpg
python3 scripts/send-validation-event.py --file /ruta/audio.wav
python3 scripts/send-validation-event.py --file /ruta/chunk_001.mp4
```

El script genera una visita nueva por envío y guarda la evidencia en `validation-data/results/`. Para evaluar acumulación entre archivos de una misma visita, pasá el mismo `--track-id UUID` a todos los envíos.

Un HTTP `201` también puede incluir un fallo de análisis: revisá el mensaje, el veredicto y los logs. La base se vacía al reiniciar; las imágenes y evidencias locales permanecen.

Para preparar nuevamente los dos dispositivos y sus personas conocidas antes de cada lote o después de un reinicio, usá el [inicializador reproducible](docs/INICIALIZACION_VALIDACION.md). Sus UUID, nombres e imágenes se configuran en un archivo local ignorado por Git. No se realiza el alta hasta disponer de todas las imágenes y superar las verificaciones previas con ML real.

Para configurar y ejecutar los 40 casos aprobados por caso, categoría o lote completo, consultá [Ejecución de la validación cuantitativa](docs/RUN_VALIDACION.md). Incluye comprobaciones sin altas y resultados independientes con copias verificadas de las auditorías.

El inventario de cambios, los contratos, las pruebas y los límites para el informe están en [docs/VALIDACION.md](docs/VALIDACION.md).
