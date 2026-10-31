# detective-manager

Microservicio encargado de gestionar la detección de intrusos y eventos atípicos en el ecosistema Hogar seguro. Recibe eventos desde microcontroladores IoT, los analiza mediante un servicio de machine learning externo, persiste el resultado y notifica a los usuarios afectados en caso de detectar un intruso.

Las mejoras de acercamientos de video y reconocimiento facial múltiple, junto con sus criterios y guía para trasladarlas a otras ramas, están documentadas en [Mejoras de video y reconocimiento facial](docs/MEJORAS_VIDEO_RECONOCIMIENTO_FACIAL.md).

## Flujo principal

1. El microcontrolador envía un evento con imagen en base64 y tipo de evento.
2. La imagen se sube a Cloudinary para almacenamiento persistente.
3. Se llama al servicio de ML para determinar si hay un intruso.
4. El evento se persiste en la base de datos.
5. Si se detecta un intruso, se notifica al servicio de usuarios.

## Endpoints

| Método | Path | Descripción |
|--------|------|-------------|
| `POST` | `/api/detective-manager/v1/events` | Reportar un evento de seguridad |
| `POST` | `/api/detective-manager/v1/devices/{deviceId}/face-embeddings` | Registrar el rostro conocido de una persona |
| `GET` | `/api/detective-manager/v1/devices/{deviceId}/persons/{personId}/face-embeddings` | Obtener todos los embeddings de una persona |

### Request

**Headers:**
- `device_id` (requerido): UUID del dispositivo que reporta el evento.

**Body:**
```json
{
  "image": "<base64>",
  "type": "bell | motion | camera"
}
```

### Response `201 Created`
```json
{
  "data": {
    "eventId": "uuid",
    "deviceId": "uuid",
    "timestamp": "2026-04-20T00:00:00",
    "isIntruder": true,
    "imageUrl": "https://...",
    "eventType": "bell"
  }
}
```

### Errores

| Código | Causa |
|--------|-------|
| `400` | `device_id` ausente o inválido, body inválido o campo desconocido |
| `502` | Error al comunicarse con el servicio de ML o de notificaciones |

## Reconocimiento y tiempos de video

Si una visita acumula detecciones reconocidas y no reconocidas, se mantiene en
`LLAMATIVO / CONTINUE`, salvo que otra regla indique mayor gravedad. No se atribuyen
esas detecciones a una misma persona ni se confirma `SAFE` por reconocer a alguien.

Un desconocido que se acerca rápidamente y la detección de varias personas aportan
reglas `LLAMATIVO / CONTINUE`, sin descartar las señales detectadas. La urgencia con
presión, varias personas con presión y los patrones de merodeo o rostro cubierto
conservan sus reglas `SOSPECHOSO`; armas y amenazas mantienen `PELIGROSO / DANGER`.

El [gateway](https://github.com/in-us-we-trust/gateway-microcontroller/blob/master/internal/config/config.go)
configura por defecto clips de video de **5 segundos**, audio de 10 segundos y una
sesión de 30 segundos. La sesión no equivale a 30 segundos observados de una persona.

| Señal | Tiempo observado mínimo | Variable de configuración |
|-------|-------------------------|---------------------------|
| Presencia prolongada | 20 segundos | `VIDEO_PROLONGED_PRESENCE_SECONDS` |
| Detención extensa | 10 segundos | `VIDEO_LONG_STOP_SECONDS` |
| Presencia acumulada en la visita | 20 segundos | `VIDEO_VISIT_PROLONGED_PRESENCE_SECONDS` |

Los dos primeros tiempos se calculan por persona dentro de cada clip. El merodeo
requiere presencia prolongada y cambios de dirección repetidos. Un regreso puntual
a una posición anterior conserva su regla individual `LLAMATIVO / CONTINUE`, pero
no basta para activar merodeo ni una regla sospechosa al combinarse con presencia
prolongada. Una espera quieta, aunque sea prolongada, tampoco activa merodeo.
La presencia prolongada, la detención extensa y el merodeo no pueden confirmarse
en un clip de 5 segundos. Los tres cambios de dirección sostenidos también necesitan
más tiempo con la ventana de suavizado actual de 0,75 segundos.

Los clips de 5 segundos sí permiten detectar acercamiento, acercamiento rápido,
regreso a una posición anterior y varias personas con al menos 2 segundos de
solapamiento, siempre que las observaciones sostengan esos patrones. Las pruebas
de tiempos cubren estas posibilidades y los límites del muestreo. Los umbrales se
conservan para que una espera breve no se convierta en una alerta de merodeo.

La señal `PROLONGED_VISIT_PRESENCE` suma, dentro del mismo `trackId`, los intervalos
de frames con al menos una persona. Varias personas simultáneas cuentan una sola vez;
los frames sin detecciones no aportan tiempo. Al alcanzar 20 segundos observados,
la señal aporta una regla `LLAMATIVO / CONTINUE`. Una persona conocida sin objetos
amenazantes ni otros indicios conserva `NORMAL / SAFE`; las reglas sospechosas,
peligrosas y de atención por urgencia o presión mantienen su prioridad. La señal
no activa combinaciones de merodeo ni afirma que sea la misma persona o que haya
permanecido entre clips. No se acumulan trayectorias, detenciones ni cambios de
dirección entre videos, por lo que seis fragmentos de 5 segundos no equivalen a un
video de 30 segundos para detectar esos patrones por persona.

Los tiempos y la identificación de cada clip quedan guardados en `dm_assessments`.
Se usa el nombre `chunk_NNN.mp4` que ya envía el gateway para ordenar clips,
deduplicar reenvíos y cortar la secuencia si falta un número. Un clip sin personas
o con análisis insuficiente/fallido también corta el conteo. Los clips tardíos se
reordenan; el primer análisis válido de un clip prevalece sobre sus reenvíos.
Si falta un nombre numerado, se deduplican clips positivos por SHA-256 del contenido;
archivos idénticos pueden subestimar presencia y no permiten detectar números faltantes.
Cambiar de clips numerados a no numerados corta la secuencia. Una vez confirmado,
el hallazgo permanece en el historial de la visita aunque luego no haya personas.

Los assessments anteriores sin tiempos no aportan segundos y cortan la secuencia.
Las nuevas columnas son opcionales y se incorporan mediante el `ddl-auto=update`
ya configurado. Se conservan los campos multipart, DTOs, veredictos y códigos de
pantalla existentes; no se requieren cambios en otros repositorios.

## Registro de rostros conocidos

DeepFace debe ejecutarse en `ml-service`, donde ya vive la lógica Python de visión
artificial. `detective-manager` se limita a orquestar la solicitud y persistir el
embedding; no ejecuta Python dentro de la JVM.

### Request

`POST /api/detective-manager/v1/devices/{deviceId}/face-embeddings`

```json
{
  "personName": "Mateo",
  "image": "<base64>"
}
```

### Response `201 Created`

```json
{
  "data": {
    "id": 1,
    "deviceId": "uuid",
    "personId": 1,
    "personName": "Mateo",
    "embedding": [0.012, -0.034],
    "modelName": "ArcFace",
    "detectorBackend": "retinaface",
    "dimensions": 512,
    "createdAt": "2026-06-12T12:00:00"
  }
}
```

El servicio busca una persona con ese nombre dentro del dispositivo. Si no
existe, la crea automáticamente en `persons` con un ID numérico autoincremental.
La comparación del nombre ignora mayúsculas, minúsculas y espacios en los
extremos.

El vector se almacena como `jsonb` en `face_embeddings`; la imagen original no
se persiste. Cada llamada crea un embedding nuevo, por lo que una misma persona
y modelo puede tener múltiples vectores.

### Obtener los embeddings de una persona

```http
GET /api/detective-manager/v1/devices/{deviceId}/persons/{personId}/face-embeddings
```

La respuesta contiene todos los vectores registrados para esa persona dentro
del dispositivo, ordenados por `createdAt`:

```json
{
  "data": [
    {
      "id": 1,
      "deviceId": "uuid",
      "personId": 1,
      "personName": "Mateo",
      "embedding": [0.012, -0.034],
      "modelName": "ArcFace",
      "detectorBackend": "retinaface",
      "dimensions": 512,
      "createdAt": "2026-06-12T12:00:00"
    }
  ]
}
```

### Contrato requerido en `ml-service`

`detective-manager` llama a:

```http
POST /api/ml-service/v1/analysis/face-embedding
Content-Type: application/json
```

```json
{
  "image": "<base64>"
}
```

Y espera:

```json
{
  "data": {
    "embedding": [0.012, -0.034],
    "model_name": "ArcFace",
    "detector_backend": "retinaface"
  }
}
```

## Variables de entorno

| Variable | Descripción | Default |
|----------|-------------|---------|
| `DB_URL` | URL de conexión a PostgreSQL | — |
| `DB_USERNAME` | Usuario de la base de datos | — |
| `DB_PASSWORD` | Contraseña de la base de datos | — |
| `ML_SERVICE_BASE_URL` | URL base del servicio de ML | — |
| `CLOUDINARY_CLOUD_NAME` | Nombre del cloud en Cloudinary | — |
| `CLOUDINARY_API_KEY` | API key de Cloudinary | — |
| `CLOUDINARY_API_SECRET` | API secret de Cloudinary | — |
| `RABBIT_URL` | URL AMQP/AMQPS de RabbitMQ | `amqp://guest:guest@localhost:5672/` |
| `RABBITMQ_NOTIFICATIONS_EXCHANGE` | Exchange topic de notificaciones | `notifications` |
| `RABBITMQ_NOTIFICATIONS_INTRUDER_ROUTING_KEY` | Routing key de alerta de intruso | `notification.intruder` |
| `RABBITMQ_NOTIFICATIONS_UNKNOWN_PERSON_ROUTING_KEY` | Routing key de sugerencia de persona desconocida | `notification.unknown-person` |
| `PORT` | Puerto del servidor | `8087` |
| `SPRING_PROFILES_ACTIVE` | Perfil activo de Spring | `local` |

## Configuración local

Crear el archivo `src/main/resources/application-local.properties` (ignorado por git) con los valores reales:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/detective_db
spring.datasource.username=postgres
spring.datasource.password=postgres
ml.service.base-url=http://localhost:8081
cloudinary.cloud-name=...
cloudinary.api-key=...
cloudinary.api-secret=...
spring.rabbitmq.addresses=amqp://guest:guest@localhost:5672/
rabbitmq.notifications.exchange=notifications
rabbitmq.notifications.intruder-routing-key=notification.intruder
rabbitmq.notifications.unknown-person-routing-key=notification.unknown-person
```

## Ejecución

```bash
mvn spring-boot:run
```

Con perfil específico:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

## Documentación API

Con la aplicación corriendo, el Swagger está disponible en:

```
http://localhost:8087/docs
```

## Tests

Toda la suite puede ejecutarse dentro de Docker. El comando construye el
runner Java, levanta PostgreSQL, espera a que la base esté disponible, ejecuta
Spotless y los tests con cobertura, y elimina los contenedores al terminar:

```bash
./scripts/test-docker.sh
```

La salida se divide en preparación, ejecución y limpieza. Muestra las clases
de test detectadas, el resultado de cada clase, el total y el estado de la
cobertura. Los logs internos de Maven, Spring, Hibernate y Docker se ocultan
durante una ejecución exitosa. Si algo falla, se muestra el error relevante y
se conserva el log técnico completo en la ruta temporal indicada.

No hace falta tener Java, Maven ni PostgreSQL instalados localmente; solamente
Docker con Compose.
