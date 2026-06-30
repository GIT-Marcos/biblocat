# Sistema de Catalogación de Biblioteca Personal (SDD)

Este es el SDD general del sistema. Para detalle sobre cada implementación de sus módulos (API, front-end, agente)
revisar sus respectivos SDD.

## Versión

1.0

## Estado

Definición inicial para implementación

---

# 1. Propósito

Este sistema permite catalogar y gestionar una biblioteca personal de archivos PDF y EPUB almacenados localmente en el
sistema de archivos.

El sistema no almacena archivos, únicamente metadatos y referencias al filesystem.

---

# 2. Principios de arquitectura

## 2.1 Separación de responsabilidades

- API → lógica de dominio, persistencia y exposición REST
- Agent → sincronización con filesystem local
- Frontend → interfaz de usuario
- Base de datos → almacenamiento de metadatos

---

## 2.2 Fuente de verdad

El sistema de archivos local es la única fuente de verdad para los documentos.

La base de datos refleja el estado del filesystem.

---

## 2.3 Comunicación entre componentes

- Agent ↔ API: HTTP REST
- Frontend ↔ API: HTTP REST
- Frontend ↛ Agent: prohibido
- API ↛ filesystem: prohibido

---

## 2.4 Arquitectura monorepo

El proyecto se organiza como un monorepo:

biblocat/
├── api/
├── agent/
├── front/
├── docs/
└── infra/

Cada componente es un proyecto independiente.

---

# 3. Objetivos del sistema

## 3.1 Funcionales

- Catalogar archivos PDF y EPUB desde filesystem local
- Detectar cambios en tiempo real (create, delete, rename)
- Permitir búsqueda por:
    - nombre
    - autor
    - etiquetas
    - tipo documental
- Gestión manual de:
    - autores
    - tags
    - tipos documentales
- Reconciliación manual del sistema

---

## 3.2 No funcionales

- Arquitectura simple y mantenible
- Sin microservicios complejos
- Sin búsqueda full-text
- Sin embeddings ni IA
- API como única fuente de lógica de negocio
- Baja complejidad operativa
- Bajo consumo de recursos

---

# 4. Componentes del sistema

---

## 4.1 API (Spring Boot)

### Responsabilidades

- Persistencia en PostgreSQL
- Gestión del modelo de dominio
- Exposición REST API
- Gestión de sincronización con agent
- Ejecución de migraciones con Flyway
- Validación de datos

### Restricciones

- No accede al filesystem
- No ejecuta WatchService
- No contiene lógica de sincronización local

---

## 4.2 Agent (Java 21)

### Responsabilidades

- Monitoreo del filesystem (WatchService)
- Escaneo completo de la biblioteca
- Detección de cambios (create, delete, rename)
- Interpretación de estructura de carpetas
- Comunicación HTTP con API

### Restricciones

- No accede a base de datos
- No contiene lógica de dominio
- No expone interfaz de usuario

---

## 4.3 Frontend (React + Vite)

### Responsabilidades

- Interfaz de usuario
- Visualización de documentos
- Gestión de tags, autores y tipos
- Reconciliación manual

### Restricciones

- No contiene lógica de negocio
- No accede al filesystem
- No se comunica directamente con el agent

---

## 4.4 Base de datos (PostgreSQL)

- Gestionada mediante Flyway dentro de la API
- No es un proyecto independiente
- Almacenamiento pasivo

---

# 5. Modelo de dominio

---

## 5.1 sources (entidad principal)

sources
-------
path (PK)
name
author_id (nullable)
source_type_id
year (nullable)
edition (nullable)
active (boolean)
created_at
updated_at

### Reglas

- `path` es identificador único global
- `name` no es único
- `active = false` indica archivo eliminado del filesystem

---

## 5.2 authors

authors
-------

- id
- name (unique)

---

## 5.3 tags

tags
----

- id
- name (unique)

---

## 5.4 source_types

source_types
------------

- id
- name (unique)

---

## 5.5 source_tags

source_tags
-----------

- source_path (FK)
- tag_id (FK)
- Primary key: (source_path, tag_id)

---

# 6. Reglas del Agent

---

## 6.1 Estructura del filesystem

Biblioteca/<Autor>/<Archivo>

- Nivel 1: autor
- Nivel 2: archivo

---

## 6.2 Archivos sin autor

Biblioteca/documento.pdf

→ author = null

---

## 6.3 Carpetas vacías

Biblioteca/Kant/

→ crea autor sin sources

---

## 6.4 Identidad del documento

El identificador único del sistema es:

path

---

## 6.5 Sincronización

- CREATE → INSERT source
- DELETE → source.active = false
- RENAME/MOVE → DELETE + CREATE (V1)

---

# 7. Sincronización

---

## 7.1 Tiempo real (WatchService)

El agent detecta cambios en el filesystem y los envía a la API.

---

## 7.2 Escaneo completo

Reconstrucción total del estado del filesystem. Se hace al iniciar el agente por primera vez.

---

## 7.3 Reconciliación manual

Frontend → API → Agent → Full scan. El usuario puede activarla desde el front-end o desde el agente.

---

# 8. Reglas de diseño del sistema

Si requiere filesystem → Agent
Si requiere dominio o persistencia → API
Si requiere interfaz de usuario → Frontend

---

# 9. Evolución del sistema

---

## V1 (actual)

- Catálogo básico
- Sincronización con filesystem
- Tags manuales

---

## V2

- *por definir.

---

# 10. Convenciones del monorepo

library-catalog/
├── api/        (Spring Boot + Flyway)
├── agent/      (Java 21 daemon)
├── frontend/   (React + Vite)
├── docs/       (SDD + ADRs)
└── infra/      (docker, scripts, CI/CD)

---

# 11. Regla de oro del sistema

El filesystem es la verdad.  
La API es la interpretación.  
El frontend es la visualización.  
El agent es el sensor.