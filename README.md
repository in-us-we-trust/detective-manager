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

## Variables de entorno

| Variable | Descripción | Default |
|----------|-------------|---------|
| `DB_URL` | URL de conexión a PostgreSQL | — |
| `DB_USERNAME` | Usuario de la base de datos | — |
| `DB_PASSWORD` | Contraseña de la base de datos | — |
| `ML_SERVICE_BASE_URL` | URL base del servicio de ML | — |
| `USERS_MANAGER_BASE_URL` | URL base del servicio de usuarios | — |
| `CLOUDINARY_CLOUD_NAME` | Nombre del cloud en Cloudinary | — |
| `CLOUDINARY_API_KEY` | API key de Cloudinary | — |
| `CLOUDINARY_API_SECRET` | API secret de Cloudinary | — |
| `PORT` | Puerto del servidor | `8087` |
| `SPRING_PROFILES_ACTIVE` | Perfil activo de Spring | `local` |

## Configuración local

Crear el archivo `src/main/resources/application-local.properties` (ignorado por git) con los valores reales:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/detective_db
spring.datasource.username=postgres
spring.datasource.password=postgres
ml.service.base-url=http://localhost:8081
users-manager.base-url=http://localhost:8081
cloudinary.cloud-name=...
cloudinary.api-key=...
cloudinary.api-secret=...
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

```bash
mvn test
```

Los tests de integración utilizan una base de datos PostgreSQL en `localhost:5430`. Levantar con:

```bash
docker compose -f docker-compose.test.yaml up -d
```

Los clientes externos (`MlServiceApiClient`, `UsersManagerApiClient`, `CloudinaryStorageClient`) son mockeados en los tests.
