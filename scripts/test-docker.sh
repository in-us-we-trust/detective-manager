#!/usr/bin/env sh

set -u

COMPOSE_FILE="docker-compose.test.yml"
LOG_FILE=$(mktemp "${TMPDIR:-/tmp}/detective-manager-tests.XXXXXX.log")
KEEP_LOG=false

cleanup() {
  status=${1:-$?}
  cleanup_status=0

  trap - EXIT INT TERM

  printf "\n[3/3] Limpieza\n"
  printf "  Deteniendo contenedores y eliminando la base de test... "
  if docker compose -f "$COMPOSE_FILE" down -v --remove-orphans >>"$LOG_FILE" 2>&1; then
    printf "[OK]\n"
  else
    cleanup_status=$?
    KEEP_LOG=true
    printf "[ERROR]\n"
  fi

  if [ "$status" -eq 0 ] && [ "$cleanup_status" -ne 0 ]; then
    status=$cleanup_status
  fi

  if [ "$KEEP_LOG" = true ]; then
    printf "  Log tecnico completo: %s\n" "$LOG_FILE"
  else
    rm -f "$LOG_FILE"
  fi

  if [ "$status" -eq 0 ]; then
    printf "\nResultado final: TODOS LOS TESTS PASARON\n"
  else
    printf "\nResultado final: LA EJECUCION FALLO\n"
  fi

  exit "$status"
}

show_error_details() {
  KEEP_LOG=true
  printf "\n  Detalle del error:\n"
  grep -E '\[ERROR\]|BUILD FAILURE|Failures:|Errors:|Caused by:|AssertionError' "$LOG_FILE" \
    | tail -n 40 \
    | sed 's/^/    /' || true
}

print_detected_tests() {
  find src/test/java -type f \
    \( -name 'Test*.java' -o -name '*Test.java' -o -name '*Tests.java' -o -name '*TestCase.java' \) \
    -print \
    | sort \
    | while IFS= read -r test_file; do
      test_name=${test_file##*/}
      printf "    - %s\n" "${test_name%.java}"
    done
}

print_test_results() {
  awk '
    /Tests run:/ && / -- in / {
      summary = $0
      sub(/^.*Tests run: /, "", summary)
      split(summary, parts, " -- in ")

      stats = parts[1]
      sub(/, Time elapsed:.*/, "", stats)
      sub(/,/, " tests,", stats)
      gsub(/Failures:/, "fallos:", stats)
      gsub(/Errors:/, "errores:", stats)
      gsub(/Skipped:/, "omitidos:", stats)

      class_name = parts[2]
      sub(/^.*\./, "", class_name)

      status = (stats ~ /fallos: 0/ && stats ~ /errores: 0/) ? "OK" : "ERROR"
      printf "  [%s] %s: %s\n", status, class_name, stats
    }
  ' "$LOG_FILE"
}

print_total_result() {
  awk '
    /Tests run:/ && !/ -- in / {
      summary = $0
    }
    END {
      sub(/^.*Tests run: /, "", summary)
      gsub(/Failures:/, "fallos:", summary)
      gsub(/Errors:/, "errores:", summary)
      gsub(/Skipped:/, "omitidos:", summary)
      if (summary != "") {
        printf "  Total: %s\n", summary
      }
    }
  ' "$LOG_FILE"
}

trap 'cleanup $?' EXIT
trap 'exit 130' INT TERM

printf "Detective Manager - suite de tests Docker\n"
printf "==========================================\n"

printf "\n[1/3] Preparacion\n"
printf "  Construyendo la imagen de tests... "
if docker compose -f "$COMPOSE_FILE" build detective-manager-test >>"$LOG_FILE" 2>&1; then
  printf "[OK]\n"
else
  printf "[ERROR]\n"
  show_error_details
  exit 1
fi

printf "  Levantando PostgreSQL de test... "
if docker compose -f "$COMPOSE_FILE" up -d --wait db-test >>"$LOG_FILE" 2>&1; then
  printf "[OK]\n"
else
  printf "[ERROR]\n"
  show_error_details
  exit 1
fi

printf "  Base de datos disponible y saludable... [OK]\n"

printf "\n[2/3] Ejecucion\n"
printf "  Clases de test detectadas:\n"
print_detected_tests
printf "  Ejecutando Spotless, tests y control de cobertura... "

if docker compose -f "$COMPOSE_FILE" run --rm --no-deps detective-manager-test \
  >>"$LOG_FILE" 2>&1; then
  printf "[OK]\n\n"
  print_test_results
  print_total_result

  if grep -q "All coverage checks have been met" "$LOG_FILE"; then
    printf "  [OK] Cobertura minima de JaCoCo alcanzada\n"
  fi
else
  test_status=$?
  printf "[ERROR]\n\n"
  print_test_results
  print_total_result
  show_error_details
  exit "$test_status"
fi
