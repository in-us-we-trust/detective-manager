# detective-manager

Microservicio encargado de gestionar la detección de intrusos y eventos atípicos en el ecosistema Hogar seguro. Recibe eventos desde microcontroladores IoT, los analiza mediante un servicio de machine learning externo, persiste el resultado y notifica a los usuarios afectados en caso de detectar un intruso.

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
| `RABBIT_QUEUE` | Cola/routing key donde publicar notificaciones | `doorbell-notifications` |
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
rabbitmq.notifications.routing-key=doorbell-notifications
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

Los clientes externos (`MlServiceApiClient`, `NotificationPublisher`, `CloudinaryStorageClient`) son mockeados en los tests.
