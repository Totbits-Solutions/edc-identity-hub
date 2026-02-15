# Identity Hub API: Casos de uso y guia de endpoints

## Requisitos previos

### Launcher de desarrollo

Identity Hub no tiene bootstrap de super-user por defecto. Usa el launcher `identityhub-dev` que crea automaticamente un admin al arrancar:

```bash
./gradlew :launcher:identityhub-dev:shadowJar

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

Al arrancar, los logs muestran la API Key del super-user. Esa clave es necesaria para todas las operaciones.

### Autenticacion

Todas las peticiones requieren la cabecera `x-api-key` con el token del participante. El formato del token es:

```
base64(participantContextId) + "." + base64(randomBytes)
```

Los IDs de participante en las URLs van codificados en Base64.

---

## Caso de uso 1: Onboarding de un nuevo participante

**Escenario:** Una nueva organizacion se une al dataspace. El administrador crea su contexto, genera sus claves, y publica su identidad.

### Paso 1 — Crear el participante (inactivo)

```
POST /v1alpha/participants/
x-api-key: <super-user-token>
```

```json
{
  "participantContextId": "acme-corp",
  "did": "did:web:acme-corp",
  "active": false,
  "roles": [],
  "keys": [{
    "keyId": "acme-key-1",
    "privateKeyAlias": "acme-alias-1",
    "keyGeneratorParams": { "algorithm": "EC", "curve": "secp256r1" },
    "active": true
  }],
  "serviceEndpoints": [{
    "id": "credential-service",
    "type": "CredentialService",
    "serviceEndpoint": "http://acme.example.com/api/credentials"
  }]
}
```

**Que sucede internamente:**
- Se crea el `ParticipantContext` en estado `CREATED`
- Se genera el par de claves EC P-256
- Se crea el `DidDocument` con el `verificationMethod` y el `serviceEndpoint`
- El DID queda en estado `GENERATED` (no publicado)
- Se devuelve un `apiKey` unico para este participante

**Respuesta:**

```json
{
  "apiKey": "YWNtZS1jb3Jw.FEFndF7c9gjF...",
  "clientId": "did:web:acme-corp",
  "clientSecret": "rA3DHkJhkZPV..."
}
```

### Paso 2 — Verificar el estado del DID

```
POST /v1alpha/participants/{participantIdBase64}/dids/state
x-api-key: <super-user-token>
```

```json
{ "did": "did:web:acme-corp" }
```

**Respuesta:** `"GENERATED"` — el DID existe pero no es resoluble publicamente.

### Paso 3 — Activar el participante

```
POST /v1alpha/participants/{participantIdBase64}/state?isActive=true
x-api-key: <super-user-token>
```

**Que sucede internamente:**
- `ParticipantContext` transiciona a `ACTIVATED`
- Se emite evento `ParticipantContextUpdated`
- `DidDocumentServiceImpl` escucha el evento y llama a `publish(did)` para todos los DIDs del participante
- `LocalDidPublisher` transiciona el DID a `PUBLISHED`
- El DID Document queda accesible via HTTP

### Paso 4 — Verificar que el DID esta publicado

```
POST /v1alpha/participants/{participantIdBase64}/dids/state
```

**Respuesta:** `"PUBLISHED"`

### Paso 5 — Consultar el DID Document completo

```
POST /v1alpha/participants/{participantIdBase64}/dids/query
```

```json
{ "offset": 0, "limit": 50 }
```

**Respuesta:** Array con el `DidDocument` W3C, incluyendo `verificationMethod` y `service`.

---

## Caso de uso 2: Onboarding directo (auto-publish)

**Escenario:** Se quiere crear un participante y que su DID sea visible inmediatamente.

```
POST /v1alpha/participants/
x-api-key: <super-user-token>
```

```json
{
  "participantContextId": "fast-corp",
  "did": "did:web:fast-corp",
  "active": true,
  "roles": [],
  "keys": [{
    "keyId": "fast-corp-key",
    "privateKeyAlias": "fast-corp-alias",
    "keyGeneratorParams": { "algorithm": "EC", "curve": "secp256r1" },
    "active": true
  }],
  "serviceEndpoints": [{
    "id": "credential-service",
    "type": "CredentialService",
    "serviceEndpoint": "http://fast-corp.example.com/api/credentials"
  }]
}
```

Con `active: true`, todo sucede en una sola peticion: creacion + generacion de claves + publicacion del DID.

---

## Caso de uso 3: Rotacion de claves

**Escenario:** La politica de seguridad exige rotar las claves criptograficas cada 6 meses. Se rota la clave activa y se genera una sucesora.

### Paso 1 — Listar las claves actuales

```
GET /v1alpha/participants/{participantIdBase64}/keypairs
x-api-key: <token>
```

**Respuesta:** Array de `KeyPairResource` con sus IDs y estados.

### Paso 2 — Rotar la clave

```
POST /v1alpha/participants/{participantIdBase64}/keypairs/{keyPairId}/rotate
x-api-key: <token>
```

```json
{
  "keyId": "acme-key-rotated",
  "privateKeyAlias": "acme-alias-rotated",
  "keyGeneratorParams": { "algorithm": "EC", "curve": "secp256r1" },
  "active": true
}
```

**Que sucede internamente:**
- La clave antigua pasa a estado `ROTATED`
- Su clave privada se destruye del vault
- Se crea una nueva clave sucesora
- Se emite evento `KeyPairActivated`
- `DidDocumentServiceImpl` escucha el evento y anade el nuevo `verificationMethod` al DID Document
- Se auto-publica el DID Document actualizado

### Paso 3 — Revocar la clave antigua (opcional)

```
POST /v1alpha/participants/{participantIdBase64}/keypairs/{keyPairId}/revoke
x-api-key: <token>
```

```json
{
  "keyId": "acme-key-successor",
  "privateKeyAlias": "acme-alias-successor",
  "keyGeneratorParams": { "algorithm": "EC", "curve": "secp256r1" },
  "active": true
}
```

**Que sucede internamente:**
- La clave pasa de `ROTATED` a `REVOKED`
- Se emite evento `KeyPairRevoked`
- `DidDocumentServiceImpl` elimina el `verificationMethod` antiguo del DID Document

---

## Caso de uso 4: Gestion de service endpoints

**Escenario:** Un participante necesita anunciar un nuevo servicio en su DID Document, o actualizar la URL de uno existente.

### Anadir un endpoint

```
POST /v1alpha/participants/{participantIdBase64}/dids/{did}/endpoints?autoPublish=true
x-api-key: <token>
```

```json
{
  "id": "issuance-service",
  "type": "CredentialIssuance",
  "serviceEndpoint": "http://acme.example.com/api/issuance"
}
```

El parametro `autoPublish=true` hace que el DID Document se re-publique inmediatamente con el nuevo endpoint.

### Actualizar un endpoint

```
PATCH /v1alpha/participants/{participantIdBase64}/dids/{did}/endpoints?autoPublish=true
x-api-key: <token>
```

```json
{
  "id": "issuance-service",
  "type": "CredentialIssuance",
  "serviceEndpoint": "http://acme.example.com/api/issuance/v2"
}
```

### Eliminar un endpoint

```
DELETE /v1alpha/participants/{participantIdBase64}/dids/{did}/endpoints?serviceId=issuance-service&autoPublish=true
x-api-key: <token>
```

---

## Caso de uso 5: Desactivacion temporal de un participante

**Escenario:** Un participante necesita dejar de operar temporalmente (mantenimiento, incidente de seguridad, etc).

### Desactivar

```
POST /v1alpha/participants/{participantIdBase64}/state?isActive=false
x-api-key: <super-user-token>
```

**Que sucede internamente:**
- `ParticipantContext` transiciona a `DEACTIVATED`
- Se emite evento `ParticipantContextUpdated`
- `DidDocumentServiceImpl` escucha y llama a `unpublish(did)` para todos los DIDs
- El DID Document ya no es resoluble publicamente
- Las credenciales del participante ya no son verificables

### Reactivar

```
POST /v1alpha/participants/{participantIdBase64}/state?isActive=true
x-api-key: <super-user-token>
```

El DID se re-publica automaticamente.

---

## Caso de uso 6: Baja definitiva de un participante

**Escenario:** Una organizacion sale del dataspace permanentemente.

```
DELETE /v1alpha/participants/{participantIdBase64}
x-api-key: <super-user-token>
```

**Que sucede internamente (en cascada):**
1. Se despublican todos los DIDs
2. Se revocan todos los KeyPairs
3. Se eliminan todas las VerifiableCredentials
4. Se elimina el ParticipantContext
5. Se elimina el API key del vault

Esta operacion es **irreversible**.

---

## Caso de uso 7: Administracion multi-tenant

**Escenario:** El administrador necesita una vision global de todos los participantes y sus recursos.

### Listar todos los participantes

```
GET /v1alpha/participants?offset=0&limit=50
x-api-key: <super-user-token>
```

### Listar todos los DIDs (cross-tenant)

```
GET /v1alpha/dids?offset=0&limit=50
x-api-key: <super-user-token>
```

### Listar todos los KeyPairs (cross-tenant)

```
GET /v1alpha/keypairs?offset=0&limit=50
x-api-key: <super-user-token>
```

Estos endpoints requieren rol `admin`.

---

## Caso de uso 8: Regeneracion de token API (compromiso de credenciales)

**Escenario:** Se sospecha que el API key de un participante ha sido comprometido.

```
POST /v1alpha/participants/{participantIdBase64}/token
x-api-key: <token-actual>
```

**Respuesta:** Nuevo API key en texto plano. El token anterior queda invalidado inmediatamente.

---

## Caso de uso 9: Gestion de roles

**Escenario:** Se necesita otorgar privilegios de administrador a otro participante.

```
PUT /v1alpha/participants/{participantIdBase64}/roles
x-api-key: <super-user-token>
```

```json
["admin"]
```

**Importante:** Esto es un reemplazo absoluto — todos los roles que debe tener el participante deben ir en el array.

---

## Referencia rapida de endpoints

### Participant Context API

| Metodo | Path | Roles requeridos | Descripcion |
|--------|------|------------------|-------------|
| POST | `/v1alpha/participants/` | admin, provisioner | Crear participante |
| GET | `/v1alpha/participants/{id}` | admin, participant, provisioner | Obtener participante |
| GET | `/v1alpha/participants` | admin, provisioner | Listar todos |
| POST | `/v1alpha/participants/{id}/state?isActive=` | admin, participant, provisioner | Activar/desactivar |
| POST | `/v1alpha/participants/{id}/token` | admin, participant, provisioner | Regenerar API key |
| PUT | `/v1alpha/participants/{id}/roles` | admin, provisioner | Actualizar roles |
| DELETE | `/v1alpha/participants/{id}` | admin, participant, provisioner | Eliminar participante |

### DID Management API

| Metodo | Path | Roles requeridos | Descripcion |
|--------|------|------------------|-------------|
| POST | `/v1alpha/participants/{id}/dids/publish` | admin, participant | Publicar DID |
| POST | `/v1alpha/participants/{id}/dids/unpublish` | admin, participant | Despublicar DID |
| POST | `/v1alpha/participants/{id}/dids/query` | admin, participant | Buscar DIDs |
| POST | `/v1alpha/participants/{id}/dids/state` | admin, participant, provisioner | Estado del DID |
| GET | `/v1alpha/dids` | admin, provisioner | Todos los DIDs (global) |
| POST | `/v1alpha/participants/{id}/dids/{did}/endpoints` | admin, participant, provisioner | Anadir endpoint |
| PATCH | `/v1alpha/participants/{id}/dids/{did}/endpoints` | admin, participant, provisioner | Reemplazar endpoint |
| DELETE | `/v1alpha/participants/{id}/dids/{did}/endpoints` | admin, participant, provisioner | Eliminar endpoint |

### KeyPair API

| Metodo | Path | Roles requeridos | Descripcion |
|--------|------|------------------|-------------|
| GET | `/v1alpha/participants/{id}/keypairs` | admin, participant, provisioner | Listar keypairs |
| GET | `/v1alpha/participants/{id}/keypairs/{kpId}` | admin, participant, provisioner | Obtener keypair |
| GET | `/v1alpha/keypairs` | admin, provisioner | Todos los keypairs (global) |
| PUT | `/v1alpha/participants/{id}/keypairs` | admin, participant, provisioner | Anadir keypair |
| POST | `/v1alpha/participants/{id}/keypairs/{kpId}/activate` | admin, participant, provisioner | Activar keypair |
| POST | `/v1alpha/participants/{id}/keypairs/{kpId}/rotate` | admin, participant, provisioner | Rotar keypair |
| POST | `/v1alpha/participants/{id}/keypairs/{kpId}/revoke` | admin, participant, provisioner | Revocar keypair |

### Codigos de respuesta

| Codigo | Significado |
|--------|-------------|
| 200 | OK, respuesta con cuerpo |
| 201 | Recurso creado |
| 204 | Operacion exitosa sin contenido |
| 400 | Peticion malformada o error de validacion |
| 401 | Sin autenticacion o token invalido |
| 403 | Sin autorizacion (no es propietario del recurso) |
| 404 | Recurso no encontrado |
| 409 | Conflicto (recurso ya existe) |

---

## Especificacion OpenAPI (Swagger)

La especificacion OpenAPI unificada con todos los endpoints y ejemplos de cada caso de uso esta disponible en:

```
docs/developer/openapi/identity-api-use-cases.yaml
```

Esta especificacion incluye:
- Todos los endpoints de las 4 APIs: Participant Context, DID, KeyPair, Verifiable Credentials
- Ejemplos de request/response para cada caso de uso (CU1-CU9)
- Descripcion detallada de lo que sucede internamente en cada operacion
- Esquemas completos de todos los modelos de datos
- Definicion del esquema de autenticacion (`x-api-key`)

### Visualizar con Swagger UI

Para visualizar la especificacion:

**Opcion 1: Swagger Editor online**
1. Abrir [editor.swagger.io](https://editor.swagger.io)
2. Pegar el contenido de `identity-api-use-cases.yaml`

**Opcion 2: Swagger UI local con Docker**
```bash
docker run -p 8080:8080 \
  -e SWAGGER_JSON=/spec/identity-api-use-cases.yaml \
  -v $(pwd)/docs/developer/openapi:/spec \
  swaggerapi/swagger-ui
```
Acceder a `http://localhost:8080`

**Opcion 3: Generar specs desde Gradle (auto-generadas)**
```bash
./gradlew resolve
```
Los specs auto-generados se encuentran en `build/docs/openapi/` de cada modulo API.

---

## Coleccion Postman

La coleccion completa con todos estos casos de uso esta disponible en:

```
postman/IdentityHub-DID-Lifecycle.postman_collection.json
```

Importar en Postman y configurar la variable `superUserToken` con el valor obtenido de los logs al arrancar el launcher `identityhub-dev`.
