# Inicialización reproducible de H2

H2 permanece en memoria. Ejecutar el inicializador antes de cada lote y después de reiniciar Detective Manager, con ambos servicios disponibles y el perfil `validacion` activo. Requiere Python 3.9 o posterior en macOS o Linux; usa solamente la biblioteca estándar y `fcntl` para el bloqueo local. Las altas y verificaciones faciales usan los endpoints reales de ML Service.

Si todavía no existe una configuración privada, prepararla sin reemplazar una ya configurada:

```bash
mkdir -p validation-data
cp validation/seed.example.json validation-data/seed.local.json
```

Editar solamente `validation-data/seed.local.json`:

- `manager_base_url` y `ml_base_url`: URLs accesibles desde el equipo que ejecuta Python. La URL de ML debe corresponder a la misma instancia usada por Detective Manager.
- `devices`: exactamente dos dispositivos con UUID fijos y distintos en `id`.
- `label`: etiqueta del dispositivo; se guarda en `serialNumber`, porque la API no tiene un campo de nombre de dispositivo.
- `person_name`: persona conocida exclusiva de ese dispositivo.
- `images`: lista no vacía de rutas a las imágenes de referencia de esa persona. Las rutas relativas se resuelven respecto de la carpeta del JSON; también se admiten rutas absolutas o con `~`.

La plantilla contiene identificadores sintéticos y listas vacías de imágenes. Es deliberadamente incompleta: completar las rutas reales cuando estén disponibles. La configuración privada, las imágenes y los resultados deben permanecer en `validation-data/` o fuera del repositorio; `validation/*.local.json` y `validation/reference-images/` también están ignorados. No agregar nombres reales, imágenes, base64 ni embeddings a Git.

Comprobar archivos sin conectarse a los servicios ni crear registros:

```bash
python3 scripts/initialize-validation.py --config validation-data/seed.local.json --check-config
```

Comprobar configuración, servicios, estado existente y referencias mediante ML real, sin crear registros en Detective Manager:

```bash
python3 scripts/initialize-validation.py --config validation-data/seed.local.json --check
```

Si H2 está vacío o faltan altas, `--check` termina con error de inicialización pendiente y código distinto de cero: confirma que todavía falta ejecutar la inicialización, sin crear registros. Un error de configuración, servicio o consistencia también se informa con código distinto de cero.

Cuando estén disponibles las referencias y se haya revisado la configuración, inicializar:

```bash
python3 scripts/initialize-validation.py --config validation-data/seed.local.json
```

El script carga todas las referencias antes de crear registros. Si falta una imagen o está vacía, termina con un error y no realiza altas. Verifica ambos servicios, la exclusividad de las asociaciones de personas por dispositivo y la correspondencia entre imágenes y embeddings. El reconocimiento real comprueba las referencias contra los embeddings de su persona esperada. Los registros correctos se reutilizan; ante duplicados o inconsistencias termina con error, sin reparar ni borrar datos automáticamente. No modifica reglas, modelos, umbrales ni el procesamiento de eventos.

Compatible con Python 3.9 o posterior. La comprobación de `createdAt` admite las fechas ISO de Java con hasta nueve decimales, sin alterar los registros ni las fechas almacenadas.

Las ejecuciones deben ser secuenciales. El bloqueo local impide ejecutar simultáneamente el inicializador con el mismo archivo de configuración; no coordina configuraciones distintas ni procesos en otras máquinas. La API no ofrece una transacción conjunta para los dos dispositivos: un fallo de red puede dejar una parte ya completada. Los POST no se reintentan automáticamente después de un timeout; revisar el error y ejecutar nuevamente para releer el estado. El código de salida distinto de cero indica que no se confirmó una inicialización completa.

Las referencias sirven para inscribir y verificar identidades. Para medir reconocimiento cuantitativo, usar material de evaluación independiente; el reconocimiento de las propias imágenes de inscripción no mide la generalización del modelo. No se ha realizado el registro definitivo mientras las referencias reales no estén disponibles.

## Pruebas sin referencias reales

```bash
python3 -m unittest discover -s scripts/tests -v
```

Estas pruebas verifican configuración, archivos faltantes, asociaciones e integridad de los registros, planificación sin escrituras y errores del transporte HTTP. No ejecutan inscripción ni reconocimiento facial y no simulan decisiones del reconocedor. La generación de embeddings, el reconocimiento real y la inicialización definitiva quedan pendientes hasta disponer de las referencias y los servicios reales.
