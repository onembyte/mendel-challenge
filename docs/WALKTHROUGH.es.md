# Recorrido del proyecto y decisiones de diseño

Una narración paso a paso de cómo se construyó este servicio y **por qué** se tomó
cada decisión. Complementa el [README](../README.md) (que es documentación de
referencia) con el razonamiento detrás del código — el material que expondrías en
una revisión de diseño o entrevista.

> This document is also available in [English](WALKTHROUGH.md).

El principio rector en todo momento: **la especificación es el contrato.** Cada
decisión se contrastó con la redacción exacta de la especificación y su ejemplo
resuelto, y donde la especificación guarda silencio tomé una decisión explícita y
documentada en lugar de una accidental.

---

## Índice

1. [Leyendo el problema](#1-leyendo-el-problema)
2. [Elección del stack](#2-elección-del-stack)
3. [Paso 0 — Esqueleto del proyecto, repositorio, CI](#3-paso-0--esqueleto-del-proyecto-repositorio-ci)
4. [Paso 1 — El núcleo de almacenamiento (dominio + repositorio)](#4-paso-1--el-núcleo-de-almacenamiento-dominio--repositorio)
5. [Paso 2 — El endpoint PUT](#5-paso-2--el-endpoint-put)
6. [Paso 3 — GET por tipo](#6-paso-3--get-por-tipo)
7. [Paso 4 — GET sum (el interesante)](#7-paso-4--get-sum-el-interesante)
8. [Paso 5 — Test de aceptación de extremo a extremo](#8-paso-5--test-de-aceptación-de-extremo-a-extremo)
9. [Paso 6 — Dockerización](#9-paso-6--dockerización)
10. [Transversal: cómo se probó](#10-transversal-cómo-se-probó)
11. [Desafíos que encontré (y cómo los diagnostiqué)](#11-desafíos-que-encontré-y-cómo-los-diagnostiqué)
12. [Qué haría con más tiempo](#12-qué-haría-con-más-tiempo)

---

## 1. Leyendo el problema

Antes de escribir código extraje el contrato exacto de la especificación,
incluyendo las partes fáciles de pasar por alto:

- **Tres endpoints**, y las formas de respuesta son precisas: `PUT` devuelve
  `{"status":"ok"}`, `GET types` devuelve un **array JSON plano** `[10, 11]` (no un
  objeto), `GET sum` devuelve `{"sum": <double>}`.
- **La clave del contrato es `parent_id`** (snake_case) tanto en la especificación
  del body (Codigo 1) como en el ejemplo (Codigo 4). La convención de Java es
  `parentId`. Ese desajuste es una trampa — ver paso 2.
- **`sum` es transitivo e incluye la transacción misma.** Lo confirmé recalculando
  el ejemplo a mano: `10 (5000) ← 11 (10000) ← 12 (5000)` da `sum/10 = 20000`
  (las tres) y `sum/11 = 15000` (11 + 12). Así que "sum" significa "el monto de
  este nodo + el monto de cada descendiente".
- **Restricciones:** almacenamiento en memoria, sin SQL, Java 11+, tests de
  integración, dockerizado, claridad de código, arquitectura correcta. **Se
  valora:** TDD, commits incrementales, SOLID, documentación.

> **Decisión:** tratar el ejemplo resuelto como un test de aceptación ejecutable
> (se convirtió en `FullFlowIntegrationTest`), de modo que "¿leí bien la
> especificación?" lo responde un test en verde, no mi memoria.

---

## 2. Elección del stack

**Java 21 + Spring Boot 4.1 + Maven (wrapper).**

- **Java 21** — el último LTS. Satisface "Java 11+" con margen y aporta records
  (usados para el modelo de dominio inmutable y los DTOs) y factorías de
  colecciones modernas.
- **Spring Boot 4.1** — es una elección **forzada y deliberada** que vale la pena
  explicar: start.spring.io **ya no ofrece Boot 3.x** (una petición de 3.x devuelve
  HTTP 400: "compatibility range is >= 4.0.0"). En vez de fijar una versión no
  disponible, apunté a 4.1 y manejé su superficie de migración de forma explícita
  (Jackson 3, los cambios del cliente de test — ver §11). La especificación solo
  requiere "Spring Boot", así que esto cumple.
- **Maven con el wrapper (`./mvnw`)** — para que un revisor no necesite nada
  instalado salvo un JDK; el build es reproducible sin un Maven local. Maven sobre
  Gradle simplemente por ser la opción más aburrida y universalmente legible para
  este tipo de servicio.

> **Compromiso:** usar el Boot más nuevo implicó toparme con detalles de migración
> que yo no conocía. Juzgué que la autenticidad (un proyecto que compila con las
> herramientas de hoy) más la oportunidad de demostrar diagnóstico valía más que
> fijar una versión vieja y cómoda — y fijé `bootVersion=4.1.0` para que el build
> siga siendo reproducible.

---

## 3. Paso 0 — Esqueleto del proyecto, repositorio, CI

**Generado con la API HTTP de Spring Initializr** (no la web) para que los
parámetros exactos queden capturados como un comando repetible:

```bash
curl … https://start.spring.io/starter.tgz \
  -d bootVersion=4.1.0 -d packageName=com.mendel.transactions -d dependencies=web,validation …
```

Dos flags no obvios:

- **`packageName=com.mendel.transactions` es obligatorio.** Sin él, Initializr
  deriva el paquete del artifactId `transactions-service` y produce
  `com.mendel.transactions_service` — un paquete sutilmente incorrecto. Lo fijé.
- **`dependencies=web,validation`** — `web` para la capa REST, `validation` para
  que Bean Validation (`@NotNull`, `@NotBlank`) esté disponible en el DTO de
  request.

**CI se agregó en segundo lugar, antes de cualquier código de feature.** Un
workflow de GitHub Actions de diez líneas (`checkout` → `setup-java 21` →
`./mvnw -B test`) corre en cada push.

> **Decisión:** cablear CI al inicio, no al final. GitHub Actions solo ejecuta
> workflows que existen *en el momento del push*, así que agregarlo temprano hace
> que cada commit siguiente reciba un check verde — el revisor ve un historial
> continuamente verde, señal mucho más fuerte que una única corrida verde en la
> punta.

También limpié el `pom.xml` generado (quité el boilerplate vacío de
`<licenses>`, `<developers>`, `<scm>` que Initializr deja) — pequeño, pero la
claridad de código se evalúa explícitamente.

---

## 4. Paso 1 — El núcleo de almacenamiento (dominio + repositorio)

Es el corazón del servicio, así que se construyó primero y con tests primero.

**`Transaction` es un `record` inmutable** con un `Long parentId` nullable (una
transacción sin padre es una raíz). Un record da inmutabilidad, igualdad por valor
y un constructor compacto (usado para rechazar un `type` nulo) sin costo.

**Una interfaz `TransactionRepository` con una implementación
`InMemoryTransactionRepository`.** Este es el núcleo SOLID/DIP: el servicio depende
de la *interfaz*. Si "sin SQL, en memoria está bien" alguna vez se convierte en
"ahora usá Postgres", solo cambia una clase y nada por encima de ella.

**Dos índices secundarios, mantenidos en la escritura:**

| Estructura | Propósito |
|---|---|
| `store: id → Transaction` | fuente de verdad primaria |
| `typeIndex: type → Set<id>` | `GET types` es una búsqueda directa, no un escaneo |
| `childrenIndex: parentId → Set<id>` | `GET sum` recorre solo un subárbol, no todo |

> **Decisión — pagar en la escritura, no en la lectura.** Mantener índices hace
> que las escrituras trabajen un poco más para que ambas lecturas sean baratas y
> simples. Para un servicio cuya razón de existir son esas dos consultas, ese es el
> balance correcto.

**La decisión de diseño más importante — un camino de escritura `synchronized`:**

`ConcurrentHashMap` es atómico *por mapa*, pero un upsert toca *tres* mapas, y el
chequeo de ciclo es *check-then-act*. Eso es justamente lo que una garantía por
mapa **no** cubre. Dos carreras concretas:

1. Dos actualizaciones concurrentes al mismo id podrían intercalarse y dejar una
   entrada **permanentemente** obsoleta en `typeIndex`/`childrenIndex`.
2. Dos enlaces concurrentes `A→B` y `B→A` podrían cada uno pasar un chequeo de
   ciclo independiente y luego ambos confirmar, creando un ciclo real.

Por eso **todas las escrituras pasan por un único `synchronized upsert(...)`**.
Dentro del lock, en orden: chequear que el padre existe, rechazar auto-padre y
ciclos (recorriendo la cadena de ancestros), quitar el id de sus buckets de índice
*viejos*, agregarlo a los nuevos, y luego ponerlo en el store. **Las lecturas
quedan sin lock** sobre los mapas concurrentes.

> **Decisión — los invariantes viven en el repositorio, no en el servicio.**
> "El padre existe" y la aciclicidad son invariantes de *almacenamiento*. Si el
> servicio chequeara "¿existe el padre?" y luego llamara al repositorio para
> escribir, ese check-then-act haría carrera a través del límite de la capa.
> Aplicarlos *dentro* de la escritura con lock es el único lugar donde realmente
> son seguros. El repositorio lanza excepciones de dominio
> (`TransactionNotFoundException`, `InvalidTransactionException`); mapearlas a HTTP
> es tarea de la capa web.

> **Limitación honesta (prefiero declararla a ocultarla):** como las lecturas no
> tienen lock, una lectura puede observar transitoriamente una escritura en curso.
> Toda escritura *completada* deja el store consistente, y el conjunto `visited`
> del recorrido de suma lo mantiene seguro igualmente. El aislamiento total de
> snapshot en lecturas queda deliberadamente fuera de alcance para un desafío en
> memoria — pero sé exactamente dónde está el límite.

**Los listados por tipo se devuelven ordenados ascendentemente.** El orden de
iteración de un `Set` es indefinido; la especificación no exige un orden, pero
devolver uno *definido* convierte un contrato potencialmente inestable en uno
determinista — y permite que los tests afirmen un array exacto.

---

## 5. Paso 2 — El endpoint PUT

Controlador delgado → servicio → repositorio. El controlador solo enruta y mapea
DTOs; el servicio mapea request→dominio y delega; el repositorio hace el trabajo.

**El DTO de request es donde vive el cumplimiento sutil de la especificación:**

```java
public record TransactionRequest(
    @NotNull Double amount,
    @NotBlank String type,
    @JsonProperty("parent_id") Long parentId) {}
```

Dos decisiones que cada una previene un bug *silencioso*:

- **`@JsonProperty("parent_id")`** enlaza la clave snake_case del contrato. Sin
  ella, Jackson ignora silenciosamente la clave desconocida `parent_id`, `parentId`
  queda en null, cada transacción se vuelve raíz, y `sum/10` devolvería `5000` en
  lugar de `20000` — **sin ningún error en ningún lado**. Elegí una anotación a
  nivel de campo en vez de una estrategia snake_case global porque solo este campo
  difiere; cada otro campo del contrato (`amount`, `type`, `sum`, `status`) es de
  una sola palabra.
- **Tipos wrapper (`Double`, `Long`), no primitivos.** Un `double amount` primitivo
  deserializaría un campo *ausente* a `0.0`, `@NotNull` sobre un primitivo no tiene
  sentido, así que el prometido `400` nunca se dispara y se almacena una
  transacción espuria con monto cero. Un `long parentId` primitivo convertiría cada
  request sin padre en un enlace a la transacción `0`.

**Códigos de error — la especificación no prescribe ninguno, así que me
comprometí con uno por caso** y documenté el razonamiento:

| Caso | Estado | Por qué |
|---|---|---|
| padre inexistente / id desconocido | **404** | la entidad referenciada no existe |
| auto-padre o ciclo | **422** | el body está bien formado pero la operación de grafo es inválida |
| campo faltante/vacío, JSON malformado | **400** | el request en sí es inválido |

Un `@RestControllerAdvice` mapea excepciones a estos códigos con un cuerpo de error
consistente. El `422` (en vez de otro `400`) es la distinción significativa: le
dice al cliente "entendí tu request; el problema es la *operación*".

> **Detalle a destacar:** el auto-padre y los ciclos solo se alcanzan mediante una
> *actualización*. En la creación, el padre debe existir, así que una
> auto-referencia colapsa en el `404` de padre inexistente; el camino `422` solo es
> realmente alcanzable al reapuntar una transacción existente. Los tests lo
> ejercitan de esa forma a propósito.

---

## 6. Paso 3 — GET por tipo

El endpoint más pequeño: devolver la lista ascendente de ids del repositorio como
un **array JSON plano**, array vacío (`200`) para un tipo desconocido. La única
decisión de fondo — el ordenamiento — ya se tomó en la capa de almacenamiento (§4),
razón por la cual el test multi-id puede afirmar un `[11, 12]` exacto sin
inestabilidad.

---

## 7. Paso 4 — GET sum (el interesante)

La suma es un **recorrido en profundidad iterativo** sobre `childrenIndex`,
comenzando desde el objetivo y acumulando montos, protegido por un conjunto
`visited`:

```java
double sum(long id):
    si id no está presente -> 404
    push id; mientras la pila no esté vacía:
        pop current; si ya fue visitado -> saltar
        total += amount(current)
        push cada hijo de current
    devolver total
```

Dos decisiones, cada una defendiendo contra un modo de fallo:

- **Iterativo, no recursivo.** Un recorrido recursivo desbordaría la pila en una
  cadena larga de padres. Hay un **test de cadena de 10.000 de profundidad**
  específicamente para probar que la versión iterativa maneja una profundidad que
  una recursiva no podría.
- **Conjunto `visited`.** El camino de escritura ya garantiza un grafo acíclico,
  pero el recorrido se defiende de todos modos — un seguro barato que además acota
  el trabajo.

> **Decisión — recorrer en tiempo de lectura en vez de precomputar.** Podría
> mantener un total acumulado por nodo y actualizar los ancestros en cada
> escritura. Eso hace `sum` O(1) pero hace cada escritura O(profundidad) y mucho
> más propensa a errores (cada re-parenteo tiene que corregir dos cadenas de
> ancestros). Para un servicio en memoria donde lecturas y escrituras son ambas
> baratas, el recorrido en tiempo de lectura es más simple y obviamente correcto —
> el compromiso correcto para este problema. Lo reconsideraría solo bajo una carga
> intensiva de lecturas medida.

**Sobre la forma de la respuesta:** la especificación tipa `sum` como `double`, así
que el JSON es `{"sum":20000.0}` donde el ejemplo imprime `20000`. Son
numéricamente iguales. Cada test afirma el **valor numérico**, nunca el string —
así un detalle de serialización no puede causar un fallo falso. Por eso también los
tests de integración parsean JSON en vez de comparar strings crudos de respuesta.

---

## 8. Paso 5 — Test de aceptación de extremo a extremo

`FullFlowIntegrationTest` reproduce el ejemplo resuelto de la especificación al pie
de la letra sobre HTTP: crea las tres transacciones, luego afirma
`types/cars == [10]`, `sum/10 == 20000`, `sum/11 == 15000`. Es la prueba final de
que todas las partes componen correctamente — y `sum/10 == 20000` es en sí misma
prueba de extremo a extremo de que el enlace snake_case `parent_id` funciona a
través de JSON real, no solo en un test unitario.

---

## 9. Paso 6 — Dockerización

Un build **multi-etapa**:

- **Etapa de build** (`maven:3.9-eclipse-temurin-21`): copiar `pom.xml` primero y
  correr `dependency:go-offline`, *luego* copiar `src` y empaquetar. Como Docker
  cachea capas, esto significa que un cambio solo de código no re-descarga todo el
  árbol de dependencias — solo se re-ejecuta la recompilación rápida.
- **Etapa de runtime** (`eclipse-temurin:21-jre`): una imagen solo-JRE (más chica,
  sin herramientas de build en el artefacto entregado), corriendo como **usuario no
  root**, exponiendo `8080`.

Un `.dockerignore` mantiene `.git`, `target/` y archivos de IDE fuera del contexto
de build. Verifiqué la imagen construyéndola y reproduciendo el ejemplo de la
especificación con `curl` contra el contenedor en ejecución — incluyendo los casos
de error `404`/`400`/`422`.

> **Decisión — `COPY` selectivo, nunca `COPY . .`.** Copiar solo `pom.xml` y luego
> `src/` garantiza que un `target/` obsoleto del host no pueda filtrarse en la
> imagen y mantiene la capa de dependencias cacheable.

---

## 10. Transversal: cómo se probó

**TDD con un historial de git auditable.** Cada feature son dos commits:
`test: … (red)` agrega tests que fallan más stubs que compilan (para que la suite
falle en *aserciones*, no en compilación), luego `feat: … (green)` es la
implementación mínima. `git log` por lo tanto *muestra* el ritmo red→green — un
único commit "test+feat" aplastado sería indistinguible de escribir los tests
después.

**Dos estilos de test, elegidos deliberadamente:**

- **Tests unitarios** para el repositorio (JUnit + AssertJ puro, sin Spring) —
  rápidos, y fijan los invariantes de almacenamiento directamente.
- **Tests de integración** levantan la app entera en un puerto aleatorio y la
  manejan sobre **HTTP real** (`TestRestTemplate`). Elecciones deliberadas acá:
  - **Enviar strings JSON crudos**, no DTOs del proyecto serializados. Si un test
    serializara el mismo DTO que el servidor deserializa, un desajuste
    `parentId`-vs-`parent_id` haría ida-y-vuelta simétricamente y se ocultaría. JSON
    crudo que coincide con el formato del contrato no puede.
  - **Afirmar sobre JSON parseado** (JSONPath), no igualdad de strings — robusto al
    formato y a `20000` vs `20000.0`.
  - **Una clase base compartida** lleva `@SpringBootTest` y limpia el store en
    `@BeforeEach`. Como cada test comparte una configuración de contexto, Spring
    levanta el servidor **una vez** para toda la suite (rápido), mientras cada test
    igual arranca desde un store vacío. Evité específicamente `@DirtiesContext`, que
    reconstruiría el contexto por clase y sería mucho más lento.

---

## 11. Desafíos que encontré (y cómo los diagnostiqué)

Estos son problemas reales que el Spring Boot más nuevo me lanzó. Cada uno se
diagnosticó a partir del error real, no adivinando:

1. **Boot 3.x ya no está.** Initializr rechaza `bootVersion=3.x`. Consulté su
   endpoint de metadata, vi que solo se ofrecen `4.0.7`/`4.1.0`, y apunté a 4.1.
2. **`TestRestTemplate` no existía como bean.** La primera corrida de integración
   falló con un error de carga de contexto. Causa raíz (del stack trace):
   `NoClassDefFoundError: RestTemplateBuilder`. En Boot 4, el soporte clásico de
   `RestTemplate` se extrajo a un módulo separado `spring-boot-restclient` que el
   starter web ya no incluye, **y** el bean `TestRestTemplate` ahora está detrás de
   un `@AutoConfigureTestRestTemplate` explícito. Solución: agregar el módulo
   (scope test) + la anotación. Encontré ambos inspeccionando la metadata de
   autoconfiguración del jar antes de escribir más código.
3. **`HttpStatus.UNPROCESSABLE_ENTITY` ya no coincidía.** Una aserción de 422 falló
   con `expected UNPROCESSABLE_ENTITY but was UNPROCESSABLE_CONTENT` — Spring
   Framework 7 renombró la constante 422 (redacción de RFC 9110). El código es
   idéntico; cambié a la constante canónica y ahora afirmo 422 **numéricamente**
   para que un futuro renombre no pueda romperlo.
4. **Jackson 3.** Boot 4 trae Jackson 3 (`tools.jackson.*`), pero `@JsonProperty`
   sigue viniendo de `com.fasterxml.jackson.annotation` — así que el import de la
   anotación no cambia, pero cualquier manejo manual de JSON usaría el paquete
   nuevo. Usé JSONPath en los tests para esquivarlo por completo.

> La conclusión que destacaría: ninguno de estos se adivinó. Cada uno vino de leer
> el fallo real, confirmar el mecanismo (contenido del jar, endpoint de metadata,
> stack trace), y luego hacer el arreglo mínimo — y cada uno está documentado en el
> código cerca de donde importa.

---

## 12. Qué haría con más tiempo

Alcance limitado a propósito — me detuve en "correcto, claro, bien probado" en vez
de sobre-pulir un desafío:

- **OpenAPI/Swagger UI** vía springdoc — documentación interactiva de la API a
  costo casi nulo.
- **Un adaptador de persistencia** — la interfaz `TransactionRepository` ya hace
  esto un reemplazo directo; una implementación JPA o Redis no necesitaría cambios
  por encima.
- **Observabilidad** — health/métricas de Actuator, y un log de acceso
  estructurado.
- **Tests basados en propiedades** para la suma sobre DAGs generados
  aleatoriamente, complementando el ejemplo elegido a mano y los casos de árbol
  ramificado.
- **Test de estrés de concurrencia** — martillar upserts concurrentes sobre el
  mismo id y afirmar que ningún índice queda obsoleto, convirtiendo el razonamiento
  de §4 en una garantía ejecutable.
