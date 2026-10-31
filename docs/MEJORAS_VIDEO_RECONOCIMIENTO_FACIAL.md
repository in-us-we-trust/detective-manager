# Mejoras de video y reconocimiento facial

Esta guía describe las mejoras funcionales desarrolladas en `validacion` y trasladadas a `feature/last-fixes`. Permite reproducir el cambio en otra rama sin incorporar la infraestructura del experimento. No constituye un despliegue a producción.

Origen de los cambios:

- `44b2c82`: acercamientos de video informativos. También contiene ajustes del inicializador y del catálogo de validación, que no forman parte de este traslado.
- `1d98b23`: reconocimiento facial múltiple. Sus pruebas de H2 y auditoría pertenecen al experimento; para la rama productiva se cubre el contrato HTTP y el flujo del orquestador sin depender de ese perfil.

## Video: acercarse no implica riesgo

`APPROACHING_CAMERA` y `FAST_APPROACH` siguen detectándose y conservándose en las señales y datos derivados. Son exclusivamente informativas:

- No elevan la severidad ni agregan advertencias de riesgo, tampoco junto a `UNKNOWN_PERSON`.
- `FAST_APPROACH` tiene una regla `NORMAL` con una descripción neutral. Se eliminan las combinaciones de acercamiento con desconocidos y con rostro cubierto que daban prioridad al acercamiento.
- Ambas señales se excluyen de `VIDEO_FINDINGS`, para que no invaliden `NO_SUSPICIOUS_BEHAVIOR`, y se incluyen en `LOW_INFO`, para que no desplacen detalles relevantes del mensaje.
- `VideoAnalysisStep` agrega `NO_SUSPICIOUS_BEHAVIOR` si el análisis terminó correctamente y sólo produjo acercamientos, o ninguna señal de comportamiento. No la agrega cuando hay observaciones incompletas ni cuando aparecen otros hallazgos.
- El mensaje de rostro cubierto se conserva por `COVERED_FACE`, independientemente de que haya acercamiento.

No cambiar `VideoFeatureCalculator`, `VideoAnalysisProperties`, sus duraciones ni sus umbrales. `LOITERING`, `LONG_STOP`, presencia prolongada, movimientos erráticos, regresos y las demás señales mantienen sus reglas. `UNKNOWN_PERSON` conserva su propia clasificación `LLAMATIVO`; acercarse no agrega gravedad. Armas y amenazas mantienen prioridad `PELIGROSO / DANGER`.

Archivos funcionales:

| Archivo | Cambio |
| --- | --- |
| `orchestrator/step/videoanalysis/VideoAnalysisStep.java` | Emitir la señal de comportamiento no sospechoso cuando sólo hay señales informativas y el análisis está completo. |
| `orchestrator/evaluator/RuleBasedDangerEvaluator.java` | Quitar reglas de riesgo por acercamiento; ajustar reglas normales, `LOW_INFO` y `VIDEO_FINDINGS`. |
| `orchestrator/evaluator/VisitEvidenceRules.java` | Quitar `COVERED_FACE + FAST_APPROACH`; el rostro cubierto sigue teniendo su regla propia. |
| `orchestrator/evaluator/Signal.java` | Documentar el carácter informativo de ambas señales. |
| `mapper/TrackMessageBuilder.java` | Presentar la advertencia de rostro cubierto por su propio hallazgo. |

Las rutas de la tabla son relativas a `src/main/java/com/is2/detective_manager/`.

## Reconocimiento facial por rostro

El request a `POST /api/ml-service/v1/analysis/face-recognition` conserva `image` y `embeddings`. Manager envía la imagen directamente a ese endpoint. `face-embedding` se usa para inscripción y para la sugerencia existente de un único desconocido, no como requisito previo del reconocimiento.

Interpretación del nuevo contrato de ML:

- `data.known` significa que al menos un rostro fue reconocido, no que todos sean conocidos.
- Cuando existe `faces`, usar `faces[i].known` para cada rostro. No reconocer a alguien sólo porque tenga `closest_match`: con `known=false`, ese objeto es un candidato que no superó el umbral.
- Admitir `distance=null` y `closest_match=null`. Para rostros no reconocidos, la respuesta de Manager deja `embeddingId`, `personId` y `personName` nulos.
- Conservar `box` en píxeles de la imagen original. `face_index` sólo identifica la detección dentro de esa respuesta; no enlaza personas entre evidencias ni reemplaza `trackId`.
- Si `faces` está ausente, conservar el contrato anterior de una sola persona. Si está presente pero vacío, incompleto, con índices repetidos o incoherente con `data.known`, fallar el step en vez de producir una identificación positiva.
- El `422` de ML por ausencia de caras se trata como análisis facial fallido, no como una persona desconocida.

Las señales se condensan como un conjunto, sin votos ni repeticiones por cara:

| Imagen grupal | Señales faciales |
| --- | --- |
| Todos conocidos | `KNOWN_PERSON`, `MULTIPLE_PEOPLE` |
| Conocidos y desconocidos | `KNOWN_PERSON`, `UNKNOWN_PERSON`, `MULTIPLE_PEOPLE` |
| Todos desconocidos | `UNKNOWN_PERSON`, `MULTIPLE_PEOPLE` |

`MULTIPLE_PEOPLE` se emite sólo si hay más de un rostro. Se reutilizan las reglas existentes de reconocimiento mixto: se bloquea `SAFE` cuando también existe `UNKNOWN_PERSON`. En una visita sin otros hallazgos, el grupo mixto resulta `LLAMATIVO / CONTINUE`. Cuando todos son conocidos, la prioridad existente de `SAFE` se conserva si hay `NO_THREAT_OBJECT` y ninguna regla superior. No promediar riesgos ni atribuir automáticamente un arma, amenaza o conducta a la persona cuyo nombre se reconoció.

La respuesta pública mantiene sus campos principales. Para grupos, `additionalInformation` agrega:

- `faceCount`, `knownFaceCount` y `unknownFaceCount`.
- `personNames`, con los nombres reconocidos sin repetir.
- `faces`, con `faceIndex`, `box`, `known`, `distance`, `embeddingId`, `personId` y `personName`.
- `personName` sigue representando la mejor coincidencia reconocida cuando tiene nombre. El mensaje puede listar varios conocidos.

Para una sola persona se conserva el formato previo, incluyendo `additionalInformation=null` para un desconocido y `personName` para un conocido con nombre disponible.

### Límite de las sugerencias automáticas

Una imagen con varios rostros no debe enviarse completa a `face-embedding`, porque ese endpoint sigue exigiendo un único rostro. Se omite la sugerencia automática del grupo y se deja `unknownPersonSuggestion=SKIPPED_MULTIPLE_FACES` en los datos derivados del step. No se recorta ni inscribe automáticamente a cada acompañante desde Manager. La sugerencia de un solo desconocido permanece intacta.

Archivos funcionales:

| Archivo | Cambio |
| --- | --- |
| `model/dto/FaceRecognitionFaceDto.java` | DTO de cada rostro y su caja. |
| `model/dto/FaceRecognitionResultDto.java` | Incorporar la lista opcional `faces`. |
| `orchestrator/step/facerecognition/FaceRecognitionOutcome.java` | Resultado por rostro; nombres e identidades sólo cuando hay reconocimiento. |
| `orchestrator/step/facerecognition/RealFaceRecognizer.java` | Validar y convertir `faces`; conservar compatibilidad con el contrato anterior. |
| `orchestrator/step/facerecognition/FaceRecognitionStep.java` | Producir señales, conteos, nombres y detalles de los rostros. |
| `service/impl/OrchestratorServiceImpl.java` | Integrar el detalle grupal en la respuesta y el mensaje; evitar embeddings de fotos grupales. |
| `model/dto/EventChunkResponseDto.java` | Documentar la extensión de `additionalInformation`. |

Las rutas también son relativas a `src/main/java/com/is2/detective_manager/`.

## Cómo aplicar el cambio en otra rama

1. Partir de un checkout limpio y revisar si ya existen `MixedRecognitionEvidence`, las señales de video y las reglas de grupos. Estas mejoras dependen de ese comportamiento previo; no sustituir otras reglas de la rama destino.
2. Revisar los diffs de los dos commits de origen y trasladar sólo los archivos funcionales anteriores y sus tests. Evitar aplicar completos los commits de `validacion`.
3. En el orquestador, adaptar específicamente la extracción del detalle grupal, la composición del mensaje, `additionalInformation` y el control de sugerencias. Conservar la persistencia, notificaciones e infraestructura de la rama destino. No copiar el archivo completo desde `validacion`, porque allí también tiene hooks de auditoría experimental.
4. Mantener los contratos anteriores para una persona y comprobar las prioridades de reglas con conocidos, desconocidos, armas, amenazas y merodeo.
5. Ejecutar los tests y revisar el diff antes de hacer un commit. La rama productiva conserva PostgreSQL, Cloudinary, RabbitMQ y su configuración de despliegue.

Quedan fuera del traslado: H2, perfil `validacion`, auditorías JSON/gzip, headers de auditoría, scripts y catálogo de casos, archivos personales de `validation-data/`, almacenamiento local, notificaciones JSONL y cambios de CI/CD del experimento. No cambiar modelos, umbrales ni código de ML Service en este traslado.

## Verificación

Las pruebas cubren acercamientos con conocidos y desconocidos, señales informativas solas y combinadas con conductas sospechosas, observaciones incompletas, prioridades de armas y amenazas, grupos mixtos, todos conocidos, todos desconocidos, candidatos no reconocidos, respuestas nulas/incoherentes y compatibilidad con una sola persona.

`FaceRecognitionHttpContractTest` verifica la deserialización y el request real del cliente mediante un servidor HTTP de pruebas en memoria. `OrchestratorFlowContractTest` cubre señales acumuladas, veredictos, respuesta grupal y omisión de embeddings del grupo. Los tests no ejecutan casos de validación ni inferencias de modelos reales.

Con Java 17 y Maven, los tests de contrato se pueden ejecutar con:

```bash
mvn -Dtest='*Face*Test,*Evaluator*Test,InformationalVideoSignalsTest,VideoAnalysisStepTest,TrackMessageBuilderTest,OrchestratorFlowContractTest,FaceRecognitionHttpContractTest' test
```

La suite completa utiliza el PostgreSQL de pruebas configurado en `docker-compose.test.yml`. Para aplicar luego la mejora a un servicio en ejecución hay que reconstruir la imagen de Manager. El reconocimiento múltiple requiere que la imagen de ML incluya el nuevo contrato `faces`; el reconocimiento de una persona conserva compatibilidad con el contrato anterior.

El traslado a `feature/last-fixes` se verificó con `mvn spotless:apply clean verify`: 473 pruebas aprobadas, sin fallos ni tests omitidos, y 89,6 % de cobertura de líneas. Se usó un PostgreSQL temporal de pruebas, no H2. El formateador también normalizó una firma de `RabbitMqConfig`, sin cambiar su configuración ni el arreglo previo de publicación a la cola.
