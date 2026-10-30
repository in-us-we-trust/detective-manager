# Ejecución de los 40 casos aprobados

Trabajar en la rama `validacion`, con Python 3.9 o posterior en macOS o Linux. Detective Manager debe usar el perfil `validacion` y ML Service debe estar disponible. El ejecutor usa los analizadores reales y conserva las reglas, modelos y umbrales existentes.

## Preparación local

Las evidencias pertenecen a su propia carpeta:

```text
validation-data/
  seed.local.json
  cases.json
  1/1/...
  5/3/...
  6/4/...
```

Preparar `seed.local.json` y las referencias como explica [Inicialización de H2](INICIALIZACION_VALIDACION.md). Los UUID se leen de ese archivo: `cases.json` usa los alias `juli` y `mate`, sin repetir UUID. La opción `device_aliases` del catálogo permite relacionar esos alias con las etiquetas de los dispositivos de la configuración privada.

Si todavía no existe el catálogo local, generarlo:

```bash
python3 scripts/validation_cases.py
```

El generador crea `validation-data/cases.json` y conserva una configuración existente. Incluye los 40 casos, sus severidades y señales aprobadas, dispositivo, modalidad y orden de envío. Su fuente son las especificaciones suministradas para la validación; no obtiene expectativas de las reglas Java.

La asociación de archivos usa nombres, extensiones y metadatos. No mueve, convierte, renombra ni copia las evidencias originales; las rutas de un caso deben permanecer dentro de su propia carpeta. Se conserva el nombre original de cada archivo, incluidos los fragmentos `chunk_NNN.mp4`.

Los formatos admitidos por el ejecutor son imágenes JPG/JPEG, PNG, WEBP y BMP; audios WAV, MP3, M4A, OGG y FLAC; y videos MP4, MOV, AVI, MKV y WEBM.

Revisar `configuration_issues` y los casos `PENDING` antes de ejecutar. Si hay varios archivos del mismo tipo, sólo se asocian automáticamente cuando sus nombres establecen un orden inequívoco. El orden definitivo es el de la lista `evidence` de `cases.json`.

Para resolver un pendiente, completar cada `evidence[].file` con el nombre exacto de su archivo en esa carpeta, revisar el orden, limpiar `configuration_issues` y marcar `configuration_status` como `READY`. El ejecutor vuelve a verificar los archivos y las expectativas aprobadas. Las anotaciones manuales de suficiencia pueden agregarse como `sufficient_motion: true/false` o como un objeto que relacione cada señal de movimiento con un booleano; no se deducen de los umbrales Java.

El catálogo activo contiene **40 casos**: **3.4 fue excluido** por indicación del usuario, sin renumerar los demás. El caso **6.4** conserva el orden explícito imagen conocida → imagen con cobertura facial → audio, configurado en el archivo privado después de incorporar la imagen faltante. Una regeneración por inventario puede dejarlo pendiente si sus nombres no tienen numeración inequívoca; conservar esa asociación manual.

`validation-data/` está ignorado por Git. Mantener allí evidencias, configuración privada, auditorías y resultados. El código del catálogo puede versionarse; sus nombres reales de archivos y UUID permanecen en los archivos locales.

## Comprobación sin altas ni evidencias

```bash
python3 scripts/run-validation.py --check
python3 scripts/run-validation.py --check --case 5.3
python3 scripts/run-validation.py --check --category 5
```

Sin selector, `--check` revisa los 40 casos. Comprueba configuración, archivos, disponibilidad de los servicios y estado de dispositivos/personas mediante lecturas. No envía evidencias, no genera embeddings ni realiza reconocimiento facial y no crea registros. Si H2 está vacía después de un reinicio, informa que falta inicializarla; la inicialización se realiza al ejecutar un lote con `--run`.

## Ejecución de un lote

```bash
python3 scripts/run-validation.py --run --case 5.3
python3 scripts/run-validation.py --run --category 5
python3 scripts/run-validation.py --run --all
```

`--run` requiere seleccionar un caso, una categoría o todos. Para otra configuración local:

```bash
python3 scripts/run-validation.py --config /ruta/cases.json --run --case 5.3
```

Las rutas de configuración se resuelven respecto del archivo JSON. Antes de enviar evidencias, se reutiliza el inicializador idempotente existente para comprobar o crear las dos personas y sus embeddings reales. Esa fase puede realizar inferencias de inscripción y verificación; no forma parte de las interacciones de evaluación.

Cada lote recibe un `executionId` nuevo y cada caso un `trackId` UUID nuevo. Dentro del caso se conservan dispositivo y track, y las evidencias se envían secuencialmente en el orden configurado. El ejecutor continúa con las evidencias previstas aunque el veredicto llegue a `DANGER` y registra cualquier rechazo.

Los casos pendientes se conservan como `PENDING`, sin enviarlos; los demás casos seleccionados y completos pueden ejecutarse. Los errores y resultados parciales permanecen registrados y producen una salida distinta de cero. Ante un timeout no se repite automáticamente la solicitud: el servidor podría haberla confirmado. Las evidencias restantes del mismo caso quedan registradas como no enviadas para preservar su orden. Una nueva ejecución del caso utiliza otro track.

Una interrupción con Ctrl-C conserva las evidencias y auditorías ya recopiladas, marca la solicitud en curso como incierta y los casos restantes como no ejecutados; termina con código 130. Una configuración pendiente o un lote incompleto devuelve código 1.

No se ejecutó el lote real completo durante la implementación.

La salida cero indica que se terminó de recopilar el lote con procesamiento íntegro. No implica que todas las clasificaciones coincidan con las fichas: revisar `analysis.comparison_status` (`MATCH`, `MISMATCH`, `REVIEW_REQUIRED` o `INCOMPLETE`) y las comparaciones por evidencia.

## Archivos de resultados

```text
validation-data/resultados/{executionId}/
  manifest.json
  cases.snapshot.json
  seed.snapshot.json
  caso-5.3/
    resultado.json
    auditorias/
      {requestId}.json
      {requestId}/ml-001.json.gz
```

El lote conserva su configuración, UUID, archivos y orden, hashes SHA-256, timestamps, duración, respuestas HTTP, headers de auditoría y errores. Cada caso guarda señales por step, calidad de análisis y evolución del veredicto y la severidad.

Se recuperan y verifican los JSON y gzip del recorder: identificadores, referencias, tamaños y hashes. Se copian sin modificar sus bytes al resultado definitivo. La copia permite revisar el lote después de vaciar H2 o retirar el directorio de auditorías original. Si falta una auditoría o es inconsistente, se conserva el resultado incompleto y su error.

La integridad del procesamiento y la comparación semántica se registran por separado. El lote puede terminar `COMPLETED` y tener discrepancias en `analysis.comparison_status`; revisar ese campo en cada resultado. `RECORDED` y `COMPLETE` no indican que el sistema haya acertado la clasificación. Los análisis `FAILED` e `INSUFFICIENT` se conservan; una señal condicionada no se convierte automáticamente en omisión si no puede establecerse la suficiencia del análisis.

Las señales de ausencia de riesgo requieren análisis válido. Las expectativas de movimiento conservan la condición `SUFFICIENT_MOTION`; los puntos metodológicos de revisión manual, incluidos 6.2 y la segunda imagen de 6.4, no se convierten en exigencias automáticas. Se controlan `Firearm` en 5.4 y `Melee_Weapon` en 5.5 a partir de los resultados visuales originales.

Las señales adicionales se guardan para revisión contextual. Sólo se contrastan como injustificadas aquellas indicadas explícitamente en la configuración; una lista vacía no autoriza a clasificar todas las adicionales como falsos positivos. Las transcripciones se conservan para diagnóstico: el ejecutor no calcula WER ni compara literalmente el audio con su transcripción.

## Pruebas automatizadas

```bash
python3 -m unittest discover -s scripts/tests -v
```

Las pruebas usan archivos y registros de auditoría sintéticos para comprobar configuración, orden, aislamiento de tracks, errores y persistencia de resultados, sin ejecutar inferencias reales.
