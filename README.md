# EDC Identity Hub

This repository contains an implementation for
the [Decentralized Claims Protocol (DCP) specification](https://projects.eclipse.org/projects/technology.dataspace-dcp).
In short, IdentityHub contains multiple VerifiableCredentials and
makes them available to authorized parties as VerifiablePresentations. It also receives VerifiableCredentials issued by
an issuer and stores them. Convenience features like automatic credential renewal and re-issuance are also included.
This functionality is sometimes referred to as "wallet".

IdentityHub makes heavy use of EDC components for core functionality, specifically those of
the [connector](https://github.com/eclipse-edc/Connector) for extension loading, runtime bootstrap, configuration, API
handling etc., while adding specific functionality using the EDC
extensibility mechanism.

Here, developers find everything necessary to build and run a basic "vanilla" version of IdentityHub.

---

## Guia de exploracion y casos de uso (TotBits)

Este repositorio incluye un entorno de desarrollo, documentacion y una coleccion Postman para explorar y probar la Identity API del Identity Hub. A continuacion se explica como arrancar el sistema y ejecutar los 9 casos de uso.

### Documentacion generada

| Fichero                                                                        | Descripcion                                                                             |
| ------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------- |
| [Casos de uso (detallado)](docs/totbits/identity-hub-casos-de-uso.md)          | Descripcion completa de los 9 casos de uso con diagramas de flujo y mecanismos internos |
| [Ciclo de vida del DID](docs/totbits/did-lifecycle-and-resolution.md)          | Estados, transiciones, eventos y codigo fuente del sistema DID                          |
| [Referencia de endpoints](docs/totbits/identity-api-referencia.md)             | Tabla completa de endpoints con roles, metodos HTTP y codigos de respuesta              |
| [OpenAPI spec](docs/totbits/identity-api-openapi.yaml)                         | Especificacion OpenAPI 3.0 unificada con ejemplos para cada caso de uso                 |
| [Coleccion Postman](postman/IdentityHub-DID-Lifecycle.postman_collection.json) | Coleccion importable con scripts de automatizacion                                      |

### Problema: el bootstrap del super-user

Identity Hub no tiene bootstrap de super-user por defecto. Todas las peticiones HTTP requieren autenticacion via `x-api-key`, pero al arrancar el sistema no existen usuarios. Los tests e2e del proyecto original evitan este problema llamando directamente a los servicios Java.

Para resolverlo, creamos un **launcher de desarrollo** (`identityhub-dev`) que incluye una extension (`SuperUserSeedExtension`) que crea automaticamente un participante con rol admin al arrancar y muestra su API Key en los logs.

### Arrancar el servidor de desarrollo

#### 1. Compilar el launcher

```bash
./gradlew :launcher:identityhub-dev:shadowJar
```

#### 2. Arrancar el Identity Hub

```bash
java -Dweb.http.port=8181 \
     -Dweb.http.path="/api" \
     -Dweb.http.identity.port=8182 \
     -Dweb.http.identity.path="/api/identity" \
     -Dweb.http.credentials.port=10001 \
     -Dweb.http.credentials.path="/api/credentials" \
     -Dedc.iam.did.web.use.https=false \
     -Dedc.iam.sts.publickey.id=test-public-key \
     -Dedc.iam.sts.privatekey.alias=super-user-alias \
     -Dedc.encryption.strict=false \
     -jar launcher/identityhub-dev/build/libs/identity-hub-dev.jar
```

#### 3. Copiar el API Key del super-user

Al arrancar, los logs muestran:

```
[SuperUserSeedExtension] Super-user API Key: YWNtZS1jb3Jw.xxxxx...
```

Copiar ese valor. Es necesario para todas las operaciones.

#### APIs expuestas

| API         | URL                                      | Descripcion                               |
| ----------- | ---------------------------------------- | ----------------------------------------- |
| Management  | `http://localhost:8181/api`              | Health check, gestion general             |
| Identity    | `http://localhost:8182/api/identity`     | Participantes, DIDs, claves, credenciales |
| Credentials | `http://localhost:10001/api/credentials` | Presentation API (DCP)                    |

### Autenticacion

Todas las peticiones a la Identity API requieren la cabecera `x-api-key` con el token del participante.

El formato del token es:

```
base64(participantContextId) + "." + base64(randomBytes)
```

Los IDs de participante en las URLs van codificados en **Base64**. La coleccion Postman calcula esto automaticamente.

### Importar la coleccion Postman

1. Abrir Postman
2. Importar el fichero `postman/IdentityHub-DID-Lifecycle.postman_collection.json`
3. Pegar el API Key del super-user en la variable `superUserToken`
4. Ejecutar primero el **Health Check** (calcula las variables Base64)
5. Ejecutar los casos de uso en orden con el **Collection Runner**

### Casos de uso

La coleccion Postman contiene 9 casos de uso organizados como carpetas. Cada carpeta tiene su propia documentacion visible en Postman, con descripciones detalladas del escenario, flujo, mecanismo interno y validaciones.

#### CU1: Onboarding paso a paso

**Que demuestra:** El flujo completo de alta de una organizacion, separando creacion y activacion para permitir verificacion intermedia.

```
1.1 POST /participants/ (active=false)    --> ParticipantContext CREATED, DID GENERATED
1.2 POST /dids/state                      --> Verificar: DID en GENERATED
1.3 POST /state?isActive=true             --> ParticipantContext ACTIVATED, DID PUBLISHED
1.4 POST /dids/state                      --> Verificar: DID en PUBLISHED
1.5 POST /dids/query                      --> Ver DID Document completo (verificationMethod + service)
```

Al activar el participante, `DidDocumentServiceImpl` escucha el evento `ParticipantContextUpdated` y llama a `publish(did)` automaticamente.

#### CU2: Onboarding directo (auto-publish)

**Que demuestra:** Todo el onboarding en una sola peticion, usando `active: true`.

```
2.1 POST /participants/ (active=true)     --> Todo en una sola peticion
2.2 POST /dids/state                      --> Verificar: DID directamente en PUBLISHED
```

Diferencia con CU1: no hay estado intermedio `GENERATED` visible. Ideal para desarrollo y pruebas.

#### CU3: Rotacion de claves criptograficas

**Que demuestra:** Rotacion y revocacion de claves, con actualizacion automatica del DID Document.

```
3.1 GET  /keypairs                        --> Obtener keyPairId de la clave activa
3.2 POST /keypairs/{id}/rotate            --> Clave antigua ROTATED, nueva ACTIVE
3.3 POST /dids/query                      --> DID tiene 2 verificationMethods
3.4 GET  /keypairs                        --> Ver ambas claves y sus estados
3.5 POST /keypairs/{id}/revoke            --> Clave REVOKED, verificationMethod antiguo eliminado del DID
```

Estados de claves: `ACTIVE` (firma, VM en DID) --> `ROTATED` (no firma, VM en DID) --> `REVOKED` (no firma, VM eliminado).

#### CU4: Gestion de service endpoints

**Que demuestra:** Operaciones CRUD sobre los service endpoints del DID Document.

```
4.1 POST   /dids/{did}/endpoints?autoPublish=true   --> Anadir endpoint
4.2 POST   /dids/query                              --> Verificar: 2 services en DID
4.3 PATCH  /dids/{did}/endpoints?autoPublish=true   --> Actualizar URL
4.4 DELETE /dids/{did}/endpoints?serviceId=...       --> Eliminar endpoint
4.5 POST   /dids/query                              --> Verificar: solo 1 service
```

Con `autoPublish=true`, los cambios se reflejan inmediatamente en el DID publicado.

#### CU5: Desactivacion temporal

**Que demuestra:** Un participante puede desactivarse y reactivarse, con despublicacion/re-publicacion automatica de DIDs.

```
5.1 POST /state?isActive=false            --> DEACTIVATED, DID UNPUBLISHED
5.2 POST /dids/state                      --> Verificar: DID en UNPUBLISHED
5.3 POST /state?isActive=true             --> ACTIVATED, DID PUBLISHED
5.4 POST /dids/state                      --> Verificar: DID en PUBLISHED de nuevo
```

El ciclo es completamente reversible. Al desactivar, las credenciales dejan de ser verificables por terceros.

#### CU6: Baja definitiva

**Que demuestra:** Eliminacion permanente e irreversible con cascada de limpieza.

```
6.1 DELETE /participants/{id}             --> Eliminacion en cascada
6.2 GET    /participants/{id}             --> 404 Not Found
```

Cascada: despublicar DIDs, revocar claves, eliminar credenciales, eliminar contexto, eliminar API key del vault.

#### CU7: Administracion multi-tenant

**Que demuestra:** Endpoints cross-tenant para vision global del sistema (requiere rol admin).

```
7.1 GET /participants?offset=0&limit=50   --> Todos los participantes
7.2 GET /dids?offset=0&limit=50           --> Todos los DIDs
7.3 GET /keypairs?offset=0&limit=50       --> Todos los KeyPairs
7.4 GET /credentials?offset=0&limit=50    --> Todas las credenciales
```

#### CU8: Regeneracion de token API

**Que demuestra:** Un participante puede regenerar su propio API key (self-service) cuando se sospecha un compromiso.

```
8.1 POST /participants/{id}/token         --> Nuevo token, anterior invalidado inmediatamente
```

Usa `x-api-key` del participante, no del super-user.

#### CU9: Gestion de roles

**Que demuestra:** Asignacion y revocacion de roles. La actualizacion es un reemplazo absoluto (no incremental).

```
9.1 PUT /participants/{id}/roles ["admin"] --> Otorgar admin
9.2 GET /participants/{id}                 --> Verificar roles
9.3 PUT /participants/{id}/roles []        --> Quitar todos los roles
```

### Transiciones de estado cubiertas

```
ParticipantContext:   CREATED --> ACTIVATED --> DEACTIVATED --> ACTIVATED --> (DELETE)
DID Document:         GENERATED --> PUBLISHED --> UNPUBLISHED --> PUBLISHED
KeyPair:              ACTIVE --> ROTATED --> REVOKED
```

---

## Documentation

Base documentation can be found on the [documentation website](https://eclipse-edc.github.io). \
Developer documentation can be found under [docs/developer](docs/developer/README.md), \
where the main concepts and decisions are captured as [decision records](docs/developer/decision-records/README.md).

## Security Warning

Older versions of IdentityHub (in particular <= 0.3.1 ) **must not be used anymore**, as they were intended for
proof-of-concept
purposes only and may contain **significant security vulnerabilities** (for example missing authn/authz on the API) and
possibly
others.
**Please always use the latest version of IdentityHub.**

## Quick start

A basic launcher configured with in-memory stores (i.e. no persistent storage) can be
found [here](launcher/identityhub). There are
two ways of running IdentityHub:

1. As native Java process
2. Inside a Docker image

### Build the `*.jar` file

```bash
./gradlew :launcher:identityhub:shadowJar
```

### Start IdentityHub as Java process

Once the jar file is built, IdentityHub can be launched using this shell command:

```bash
java -Dweb.http.credentials.port=10001 \
     -Dweb.http.credentials.path="/api/credentials" \
     -Dweb.http.port=8181 \
     -Dweb.http.path="/api" \
     -Dweb.http.identity.port=8182 \
     -Dweb.http.identity.path="/api/identity" \
     -jar launcher/identityhub/build/libs/identity-hub.jar
```

this will expose the Presentation API at `http://localhost:10001/api/presentation` and the Identity API
at `http://localhost:8182/api/identity`. More information about IdentityHub's APIs can be
found [here](docs/developer/architecture/identityhub-apis.md)

### Create the Docker image

```bash
docker build -t identity-hub ./launcher/identityhub
```

### Start the Identity Hub

```bash
docker run -d --rm --name identityhub \
            -e "WEB_HTTP_IDENTITY_PORT=8182" \
            -e "WEB_HTTP_IDENTITY_PATH=/api/identity" \
            -e "WEB_HTTP_PRESENTATION_PORT=10001" \
            -e "WEB_HTTP_PRESENTATION_PATH=/api/presentation" \
            -e "EDC_IAM_STS_PRIVATEKEY_ALIAS=privatekey-alias" \
            -e "EDC_IAM_STS_PUBLICKEY_ID=publickey-id" \
            identity-hub:latest
```

## Architectural concepts of IdentityHub

Key architectural concepts are
outlined [here](docs/developer/architecture/decentralized-claims-protocol/identity.hub.architecture.md).

## Module structure of IdentityHub

IdentityHub's module structure and key SPIs is
described [here](docs/developer/architecture/decentralized-claims-protocol/identity-hub-modules.md).

_Please note that some classes or functionalities mentioned there may not yet have been implemented, for example
automatic credential renewal._

## API overview of IdentityHub

IdentityHub exposes several APIs that are described in more
detail [here](docs/developer/architecture/identityhub-apis.md).

## Future work

- Implementation of the Credential Issuance Protocol
- Support for VC Presentation Definition
- Support for VC Data Model 2.0

## References

- Decentralized Claims Protocol (DCP): https://projects.eclipse.org/projects/technology.dataspace-dcp
- VerifiableCredentials Data Model: https://www.w3.org/TR/vc-data-model/ (currently supported)
  and https://www.w3.org/TR/vc-data-model-2.0/ (planned)
- EDC Connector: https://github.com/eclipse-edc/Connector

## Contributing

See [how to contribute](https://github.com/eclipse-edc/eclipse-edc.github.io/blob/main/CONTRIBUTING.md).
