# Validación aislada del TPP

Esta rama permite ejecutar detective-manager con ml-service como única dependencia externa de ejecución. Se conserva el procesamiento real del orquestador. La base embebida y los adaptadores locales sustituyen infraestructura ajena al análisis, sin simular resultados de ML.

## Diferencias respecto de la configuración original

| Dependencia o requisito original | Uso | Configuración en `validacion` |
|---------------------------------|-----|-------------------------------|
| PostgreSQL y credenciales `DB_*` | Persistencia JPA de visitas, análisis, personas y vectores | H2 en memoria dentro de la JVM; dialecto H2 y modo PostgreSQL para la columna JSONB |
| Cloudinary y credenciales `CLOUDINARY_*` | Imágenes de eventos y de personas conocidas | Cliente local; conserva exactamente los bytes y devuelve una URL relativa |
| RabbitMQ y consumidores de notificaciones | Alertas de peligro y sugerencias del batch | Publisher local; agrega el mensaje a un archivo JSONL |
| Gateway o microcontrolador | Generación y envío de archivos | Envío directo multipart mediante curl, Swagger o script Python |
| Servicios de usuarios o aplicación móvil | Consumo posterior de notificaciones | No se requieren: detective-manager no los llama directamente |
| Servicio de ML | Rostros, objetos, conversación, video, embeddings y clustering | Se conservan los clientes reales y la URL configurable |
| Base y reglas de producción | Consolidación por track, señales, tiempos y veredicto | Se conservan servicios, repositorios JPA, entidades y umbrales |

`application.properties` selecciona `validacion` por defecto en esta rama. `application-validacion.properties` reemplaza la conexión de base, excluye la autoconfiguración AMQP y configura archivos locales. `CloudinaryConfig`, `CloudinaryStorageClientImpl` y `RabbitMqConfig` se excluyen por perfil. `ValidationInfrastructureConfig` registra los reemplazos locales.

Las bibliotecas de PostgreSQL, AMQP y Cloudinary permanecen en el proyecto para conservar los otros perfiles y la suite existente. No requieren servidores, conexiones ni credenciales en el perfil de validación. H2 se agrega como dependencia de ejecución. Los [modos de compatibilidad de H2](https://h2database.github.io/html/features.html#compatibility) no reproducen todo el comportamiento de PostgreSQL.

No activar los perfiles `mock-face`, `mock-objects`, `mock-conversation` ni `mock-video` durante una validación con ML real. El servicio ML puede necesitar sus propios modelos, descargas o recursos: este aislamiento corresponde a las dependencias directas de detective-manager.

## Arranque

### Java 17 y Maven

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=validacion
```

La URL del ML por defecto es `http://localhost:8000`. Para cambiarla:

```bash
ML_SERVICE_BASE_URL=http://localhost:8001 mvn spring-boot:run -Dspring-boot.run.profiles=validacion
```

El perfil no carga `application-local.properties`, aunque exista con credenciales de otro entorno.

### Docker

Con ml-service disponible en el host:

```bash
docker compose -f docker-compose.validacion.yml up --build
```

Este compose levanta únicamente detective-manager, con `ML_SERVICE_BASE_URL=http://host.docker.internal:8000` y un volumen local para evidencias. Para un ML en otra máquina, configurar su URL accesible desde el contenedor. El puerto publicado se limita a `127.0.0.1:8088`.

```bash
ML_SERVICE_BASE_URL=http://192.168.1.10:8000 docker compose -f docker-compose.validacion.yml up --build
```

No usar `docker-compose.local.yml` para este experimento: incluye PostgreSQL y la configuración original de Cloudinary y RabbitMQ.

| Variable | Valor por defecto del perfil |
|----------|-----------------------------|
| `ML_SERVICE_BASE_URL` | `http://localhost:8000` en JVM; `http://host.docker.internal:8000` en compose |
| `PORT` | `8088` |
| `VALIDATION_MEDIA_DIRECTORY` | `./validation-data/images` |
| `VALIDATION_NOTIFICATIONS_FILE` | `./validation-data/notifications.jsonl` |

En compose, las dos rutas locales se fijan dentro de `/app/validation-data` y se montan en `./validation-data` del host.

## Endpoint multimedia

```http
POST /api/detective-manager/v1/orchestrator/events
Content-Type: multipart/form-data
```

| Campo | Contenido |
|-------|-----------|
| `trackId` | UUID de la visita. Reutilizarlo únicamente para fragmentos del mismo caso |
| `deviceId` | UUID de prueba. No necesita un dispositivo creado en la base |
| `mediaType` | `IMAGE`, `AUDIO` o `VIDEO`, en mayúsculas |
| `file` | Archivo binario. No enviar un JSON base64 al endpoint de eventos |

Ejemplo de imagen:

```bash
curl --fail-with-body http://localhost:8088/api/detective-manager/v1/orchestrator/events \
  -F trackId=22222222-2222-4222-8222-222222222222 \
  -F deviceId=11111111-1111-4111-8111-111111111111 \
  -F mediaType=IMAGE \
  -F 'file=@/ruta/imagen.jpg'
```

Para audio o video, cambiar `mediaType` y el archivo. El límite configurado es de 100 MB para el archivo y para la solicitud completa; el multipart agrega algunos bytes al tamaño del archivo.

| Entrada | Análisis ejecutado y llamada al ML |
|---------|-----------------------------------|
| IMAGE | `face-recognition` y `object-detection`, enviando la imagen base64 dentro de JSON |
| AUDIO | `conversation`, multipart con `visitor_audio` |
| VIDEO | `video`, multipart con `video` |

Todos esos endpoints del ML cuelgan de `/api/ml-service/v1/analysis/`. Una imagen de persona desconocida también puede disparar `face-embedding` para guardar un vector huérfano. El alta de personas conocidas usa `face-embedding`; el batch utiliza clustering. No se eliminan esas llamadas al ML.

La respuesta mantiene `201 Created` y el contrato existente:

```json
{
  "data": {
    "trackId": "22222222-2222-4222-8222-222222222222",
    "chunkType": "IMAGE",
    "finalVerdict": "CONTINUE",
    "finalSeverity": "LLAMATIVO",
    "message": "...",
    "additionalInformation": null
  }
}
```

Los valores dependen del contenido analizado y de las señales anteriores de la visita. `201` no demuestra que todos los análisis hayan terminado correctamente: el orquestador conserva errores parciales en el mensaje y puede continuar con otros resultados. Esta conducta se mantiene para poder observarla durante las pruebas.

## Envíos reproducibles y evidencia

El script usa Python 3 y solamente su biblioteca estándar:

```bash
python3 scripts/send-validation-event.py --file /ruta/imagen.jpg
python3 scripts/send-validation-event.py --file /ruta/audio.wav
python3 scripts/send-validation-event.py --file /ruta/chunk_001.mp4
```

Infiere el tipo por extensión; se puede fijar con `--media-type`. Por defecto usa el dispositivo `11111111-1111-4111-8111-111111111111`, genera un `trackId` nuevo y espera hasta 300 segundos. Para simular una visita que recibe varias modalidades:

```bash
python3 scripts/send-validation-event.py --file /ruta/imagen.jpg --track-id 33333333-3333-4333-8333-333333333333
python3 scripts/send-validation-event.py --file /ruta/audio.wav --track-id 33333333-3333-4333-8333-333333333333
python3 scripts/send-validation-event.py --file /ruta/chunk_001.mp4 --track-id 33333333-3333-4333-8333-333333333333
```

Usar un UUID nuevo al repetir un caso independiente. El script conserva el nombre del archivo: los videos `chunk_NNN.mp4` participan de la deduplicación y del cómputo de presencia por visita.

Cada solicitud genera `validation-data/results/{caseId}.json` con ruta del archivo, tamaño, SHA-256, campos enviados, fechas UTC, tiempo transcurrido, código HTTP, respuesta completa y error de red si corresponde. La salida exitosa del script indica éxito HTTP; la clasificación funcional se evalúa contra un resultado esperado definido antes de la ejecución.

El tiempo registrado es la latencia HTTP de esa solicitud: incluye comunicación, ML, persistencia y orquestación; no es exclusivamente tiempo de inferencia. Separar inicialización y calentamiento de modelos de las repeticiones usadas para medir latencia.

| Evidencia | Ubicación o consulta |
|-----------|----------------------|
| Solicitudes y respuestas | `validation-data/results/*.json` |
| Imágenes originales | `validation-data/images/{deviceId}/*.bin` |
| Descarga de imagen | `GET /validation/media/{deviceId}/{archivo}.bin`, como binario |
| Mensajes que habrían sido publicados | `validation-data/notifications.jsonl` |
| Señales acumuladas y veredicto por visita | `GET /api/detective-manager/v1/devices/{deviceId}/tracks` |
| Detalle e imagen de una visita | `GET /api/detective-manager/v1/devices/{deviceId}/tracks/{trackId}` |
| Errores y duración por step | Logs de detective-manager |

Las URLs de imagen son relativas al servidor. Los archivos `.bin` conservan los bytes originales y se ofrecen para descarga. Los mensajes JSONL contienen `type`, `timestamp` y `payload` con el DTO original. `INTRUDER_DETECTED` registra una alerta; `UNKNOWN_PERSON_DETECTED` registra una sugerencia generada por el batch, que no se ejecuta automáticamente.

Se mantiene el requisito existente de una imagen previa en el track para construir una notificación de peligro. Un caso de solo audio o video puede terminar en `DANGER` sin generar un mensaje. Una persona desconocida guarda un embedding huérfano; no genera por sí sola una sugerencia del batch.

La base H2 se vacía en cada reinicio, incluyendo personas conocidas, embeddings, tracks y assessments. Las imágenes, notificaciones y archivos de evidencia permanecen. Exportar las consultas necesarias antes de detener el proceso. Usar `--output-dir` para separar lotes de evidencia; los archivos locales están ignorados por Git.

## Registro de una persona conocida

```http
POST /api/detective-manager/v1/devices/{deviceId}/persons
Content-Type: application/json
```

```json
{
  "name": "Persona de prueba",
  "images": ["BASE64_SIN_PREFIJO_DATA"],
  "role": "Residente"
}
```

La imagen se guarda localmente y el embedding lo genera el ML real. Registrar con el mismo `deviceId` de los eventos. Consultar luego `GET /api/detective-manager/v1/devices/{deviceId}/persons`. Después de reiniciar la aplicación hay que registrar nuevamente las personas.

## Pruebas automatizadas del aislamiento

```bash
mvn -Dtest=LocalValidationAdaptersTest,ValidationProfileIntegrationTest test
python3 -m unittest discover -s scripts/tests -v
```

Las pruebas Java del perfil levantan Spring y H2 reales, conservan los analizadores reales y sustituyen la respuesta HTTP del ML mediante `MockRestServiceServer`. Verifican la ausencia de clientes Cloudinary/Rabbit, imagen/audio/video, persistencia de señales, recuperación de embeddings numéricos, descarga de imagen, notificación local y manejo de fallos de ML. Son pruebas de integración del manager y sus contratos HTTP; no miden la calidad de los modelos.

Comprobación al preparar esta configuración (29/10/2026): 367 pruebas Java aprobadas en 31 clases, 6 pruebas Python aprobadas, Spotless correcto, JAR generado y cobertura de líneas de 77,19 % (1865/2416). Se ejecutaron todas las clases que no requieren PostgreSQL externo, incluyendo las nuevas pruebas de integración con H2. Comando reproducible:

```bash
mvn '-Dtest=*,!PersonsControllerIntegrationTest,!DevicesControllerIntegrationTest,!BatchControllerIntegrationTest,!DetectiveManagerApplicationTests,!VisitPresencePersistenceIntegrationTest' spotless:check clean verify
```

Las cinco clases excluidas necesitan PostgreSQL y no se ejecutaron en esta comprobación. El informe de cobertura generado está en `target/site/jacoco/index.html` y los resultados Java en `target/surefire-reports/`. Estos porcentajes describen la cobertura de código de esa ejecución, no la precisión de detección de los modelos.

Para la suite histórica completa con PostgreSQL y la cobertura existente, usar Docker disponible:

```bash
./scripts/test-docker.sh
```

## Casos para completar en el informe

Registrar para cada caso el identificador, archivo/hash, resultado esperado, resultado observado, evidencias y conclusión. Definir manualmente la verdad de referencia del material antes de ejecutarlo y registrar la versión/configuración del ML. No hay resultados de calidad del modelo hasta ejecutar material real contra ml-service.

| Escenario | Comportamiento a contrastar |
|-----------|----------------------------|
| Persona conocida sin señales adversas | Reconocimiento y veredicto `SAFE` |
| Persona desconocida | Señal `UNKNOWN_PERSON` y continuidad del análisis |
| Arma reconocida en imagen | `WEAPON_DETECTED`, `DANGER` y alerta local si hay imagen |
| Audio con amenaza y audio neutral | Señales de conversación y prioridad frente al historial |
| Video con acercamiento, regreso o varias personas | Señales justificadas por observaciones y duración |
| Modalidades combinadas con el mismo track | Acumulación de señales sin perder evidencia previa |
| Casos independientes | Ausencia de contaminación al usar tracks nuevos |
| Reenvío de video numerado | Presencia no duplicada por el mismo número de clip |
| Fallo de ML o respuesta incompleta | Mensaje de error o insuficiencia, sin confundirlo con análisis exitoso |

Los umbrales actuales se conservan: presencia prolongada de 20 s, detención de 10 s y presencia acumulada de visita de 20 s. Un clip de 5 s no confirma los dos primeros patrones por persona. La presencia entre clips cuenta intervalos observados con personas, sin asumir continuidad ni identidad entre videos. Presencia prolongada por sí sola no demuestra merodeo; se mantienen las reglas de trayectorias y su prioridad.

## Alcance de las conclusiones

El experimento permite validar el contrato multimedia, la integración con ML, las reglas de decisión, la acumulación por visita y la construcción de notificaciones. No valida la entrega por RabbitMQ, el almacenamiento Cloudinary, la aplicación móvil, el dispositivo físico ni el comportamiento específico, rendimiento o durabilidad de PostgreSQL. La latencia se mide con infraestructura local distinta de producción.

Esta rama es de uso experimental y no está destinada a integrarse ni desplegarse en PRD. El job de CD exige la referencia `master`, incluso si se invoca manualmente; una ejecución desde `validacion` no realiza el despliegue.
