# Identity Hub: Casos de uso y guia de pruebas

## Introduccion

Este documento describe los **9 casos de uso** disenados para demostrar y validar el funcionamiento de la Identity API del Eclipse EDC Identity Hub. Cada caso de uso simula un escenario real que una organizacion encontraria al operar dentro de un dataspace descentralizado.

Los casos de uso estan implementados como una **coleccion Postman** ejecutable, con scripts de automatizacion que encadenan las peticiones y validan los resultados.

### Ficheros relacionados

| Fichero | Descripcion |
|---------|-------------|
| `postman/IdentityHub-DID-Lifecycle.postman_collection.json` | Coleccion Postman con todos los CUs implementados |
| `docs/totbits/did-lifecycle-and-resolution.md` | Ciclo de vida del DID y resolucion |
| `docs/totbits/identity-api-referencia.md` | Referencia completa de endpoints con roles y codigos |
| `docs/totbits/identity-api-openapi.yaml` | Especificacion OpenAPI unificada con ejemplos |

### Como ejecutar las pruebas

1. Compilar el launcher de desarrollo:
   ```bash
   ./gradlew :launcher:identityhub-dev:shadowJar
   ```

2. Arrancar el Identity Hub:
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

3. Copiar el API Key del super-user de los logs

4. Importar la coleccion Postman y pegar el token en la variable `superUserToken`

5. Ejecutar el **Collection Runner** de Postman en orden secuencial

---

## CU1: Onboarding de un nuevo participante (paso a paso)

### Que se pretende demostrar

Este caso de uso demuestra el flujo completo de **alta de una organizacion** en el dataspace, separando la creacion de la activacion para permitir una verificacion intermedia. Es el flujo recomendado en produccion donde un administrador quiere revisar la configuracion antes de hacer visible la identidad.

### Escenario

Una nueva organizacion (acme-corp) se une al dataspace. El administrador crea su contexto de participante con su par de claves y su DID, pero **no lo activa inmediatamente**. Primero verifica que el DID se ha generado correctamente y luego lo activa, lo que publica automaticamente su identidad.

### Que se valida

1. **Creacion del contexto**: Se genera el `ParticipantContext`, el par de claves EC P-256 y el DID Document
2. **Estado intermedio**: El DID queda en estado `GENERATED` — existe pero no es resoluble publicamente
3. **Activacion y publicacion**: Al activar el participante, el DID transiciona automaticamente a `PUBLISHED`
4. **DID Document W3C**: El documento publicado contiene los `verificationMethod` y `service` correctos

### Flujo de peticiones

| Paso | Peticion | Que verifica |
|------|----------|--------------|
| 1.1 | `POST /participants/` con `active: false` | Creacion exitosa, se obtiene apiKey |
| 1.2 | `POST /participants/{id}/dids/state` | DID esta en `GENERATED` |
| 1.3 | `POST /participants/{id}/state?isActive=true` | Activacion exitosa (204) |
| 1.4 | `POST /participants/{id}/dids/state` | DID esta en `PUBLISHED` |
| 1.5 | `POST /participants/{id}/dids/query` | DID Document tiene verificationMethod y service |

### Mecanismo interno

```
POST /participants/ (active=false)
    |
    v
ParticipantContext: CREATED
KeyPair: ACTIVE (EC P-256 generado)
DidDocument: GENERATED (no publicado)
    |
POST /state?isActive=true
    |
    v
ParticipantContext: ACTIVATED
    |-- evento ParticipantContextUpdated -->
    |
    v
DidDocumentServiceImpl.publish(did)
    |
    v
LocalDidPublisher: DID -> PUBLISHED
```

---

## CU2: Onboarding directo (auto-publish)

### Que se pretende demostrar

Este caso de uso demuestra que se puede realizar **todo el onboarding en una sola peticion** cuando no se necesita verificacion intermedia. Es util para entornos de desarrollo, pruebas automatizadas u onboarding masivo.

### Escenario

Se crea un participante (fast-corp) con `active: true`, lo que provoca que toda la cadena se ejecute automaticamente: creacion del contexto, generacion de claves, creacion del DID Document, activacion y publicacion.

### Que se valida

1. **Una sola peticion**: Todo el flujo de CU1 ocurre en una sola llamada HTTP
2. **Publicacion inmediata**: El DID pasa directamente a `PUBLISHED` sin estado intermedio visible

### Flujo de peticiones

| Paso | Peticion | Que verifica |
|------|----------|--------------|
| 2.1 | `POST /participants/` con `active: true` | Creacion exitosa, se obtiene apiKey |
| 2.2 | `POST /participants/{id}/dids/state` | DID esta directamente en `PUBLISHED` |

### Diferencia con CU1

| Aspecto | CU1 (paso a paso) | CU2 (directo) |
|---------|-------------------|----------------|
| Peticiones necesarias | 3 (crear + activar + verificar) | 1 (crear) |
| Estado intermedio visible | Si (GENERATED) | No |
| Control del administrador | Alto | Bajo |
| Uso recomendado | Produccion | Desarrollo/pruebas |

---

## CU3: Rotacion de claves criptograficas

### Que se pretende demostrar

Este caso de uso demuestra el ciclo completo de **rotacion y revocacion de claves**, mostrando como el DID Document se actualiza automaticamente en cada paso. Es fundamental para el cumplimiento de politicas de seguridad que exigen rotacion periodica de claves.

### Escenario

La politica de seguridad exige rotar las claves cada cierto periodo. Se rota la clave activa (generando una sucesora), se verifica que el DID Document se actualizo, y opcionalmente se revoca la clave antigua para eliminar su verificationMethod del DID.

### Que se valida

1. **Rotacion**: La clave antigua pasa a `ROTATED`, su clave privada se destruye, y se genera una sucesora
2. **Actualizacion del DID**: El nuevo `verificationMethod` aparece automaticamente en el DID Document
3. **Coexistencia**: Tras la rotacion, ambos verificationMethods (antiguo y nuevo) coexisten en el DID
4. **Revocacion**: Al revocar, el verificationMethod antiguo se elimina del DID Document

### Flujo de peticiones

| Paso | Peticion | Que verifica |
|------|----------|--------------|
| 3.1 | `GET /participants/{id}/keypairs` | Obtiene keyPairId de la clave activa |
| 3.2 | `POST /keypairs/{id}/rotate` | Rotacion exitosa (204) |
| 3.3 | `POST /dids/query` | DID tiene 2 verificationMethods |
| 3.4 | `GET /participants/{id}/keypairs` | 2 claves: una ACTIVE, una ROTATED |
| 3.5 | `POST /keypairs/{id}/revoke` | Revocacion exitosa, verificationMethod antiguo eliminado |

### Estados de las claves

```
              rotate                 revoke
ACTIVE ──────────────> ROTATED ──────────────> REVOKED
  |                      |                       |
  | Puede firmar         | NO puede firmar       | NO puede firmar
  | VM en DID: Si        | VM en DID: Si         | VM en DID: No
  |                      | Privada destruida      |
```

### Mecanismo interno

```
POST /keypairs/{id}/rotate
    |
    v
Clave antigua: ACTIVE -> ROTATED (privada destruida del vault)
Nueva clave: ACTIVE (generada con keyGeneratorParams)
    |-- evento KeyPairActivated -->
    |
    v
DidDocumentServiceImpl: anade nuevo verificationMethod
DID Document re-publicado automaticamente

POST /keypairs/{id}/revoke
    |
    v
Clave rotada: ROTATED -> REVOKED
    |-- evento KeyPairRevoked -->
    |
    v
DidDocumentServiceImpl: ELIMINA verificationMethod antiguo
DID Document re-publicado automaticamente
```

---

## CU4: Gestion de service endpoints

### Que se pretende demostrar

Este caso de uso demuestra las operaciones CRUD sobre los **service endpoints** del DID Document. Los service endpoints permiten a un participante anunciar publicamente las URLs de sus servicios (credenciales, issuance, etc.).

### Escenario

Un participante necesita anunciar un nuevo servicio de issuance en su DID Document, despues actualizar su URL a una nueva version, y finalmente eliminar el servicio cuando ya no esta disponible.

### Que se valida

1. **Anadir**: Un nuevo service aparece en el DID Document
2. **Actualizar**: La URL del servicio se actualiza correctamente
3. **Eliminar**: El service desaparece del DID Document
4. **autoPublish**: Con `autoPublish=true`, los cambios se reflejan inmediatamente en el DID publicado

### Flujo de peticiones

| Paso | Peticion | Que verifica |
|------|----------|--------------|
| 4.1 | `POST /dids/{did}/endpoints?autoPublish=true` | Endpoint anadido (204) |
| 4.2 | `POST /dids/query` | DID tiene 2 services (credential + issuance) |
| 4.3 | `PATCH /dids/{did}/endpoints?autoPublish=true` | URL actualizada (204) |
| 4.4 | `DELETE /dids/{did}/endpoints?serviceId=...&autoPublish=true` | Endpoint eliminado (204) |
| 4.5 | `POST /dids/query` | DID vuelve a tener solo 1 service |

### Parametro autoPublish

| Valor | Comportamiento |
|-------|---------------|
| `true` | El DID Document se re-publica inmediatamente tras el cambio |
| `false` (o ausente) | El cambio se guarda en la BD pero no se refleja publicamente hasta la proxima publicacion |

---

## CU5: Desactivacion temporal de un participante

### Que se pretende demostrar

Este caso de uso demuestra que un participante puede ser **desactivado temporalmente** (por mantenimiento, incidente de seguridad, etc.) y despues **reactivado**, recuperando completamente su identidad publica. Demuestra la reversibilidad del proceso.

### Escenario

Un participante necesita dejar de operar temporalmente. Se desactiva, lo que despublica automaticamente todos sus DIDs (dejandolos irresolubles). Cuando el incidente se resuelve, se reactiva y los DIDs vuelven a publicarse.

### Que se valida

1. **Desactivacion**: El ParticipantContext pasa a `DEACTIVATED` y los DIDs a `UNPUBLISHED`
2. **Impacto**: Las credenciales del participante dejan de ser verificables por terceros
3. **Reactivacion**: El ParticipantContext vuelve a `ACTIVATED` y los DIDs a `PUBLISHED`
4. **Reversibilidad**: El ciclo completo deactivate/reactivate es completamente reversible

### Flujo de peticiones

| Paso | Peticion | Que verifica |
|------|----------|--------------|
| 5.1 | `POST /state?isActive=false` | Desactivacion exitosa (204) |
| 5.2 | `POST /dids/state` | DID esta en `UNPUBLISHED` |
| 5.3 | `POST /state?isActive=true` | Reactivacion exitosa (204) |
| 5.4 | `POST /dids/state` | DID esta en `PUBLISHED` de nuevo |

### Mecanismo interno

```
POST /state?isActive=false                POST /state?isActive=true
    |                                         |
    v                                         v
ACTIVATED -> DEACTIVATED                 DEACTIVATED -> ACTIVATED
    |                                         |
    |-- ParticipantContextUpdated -->         |-- ParticipantContextUpdated -->
    |                                         |
    v                                         v
unpublish(did) para todos los DIDs       publish(did) para todos los DIDs
DID: PUBLISHED -> UNPUBLISHED            DID: UNPUBLISHED -> PUBLISHED
```

---

## CU6: Baja definitiva de un participante

### Que se pretende demostrar

Este caso de uso demuestra la **eliminacion permanente** de un participante y todos sus recursos asociados. Es una operacion irreversible que simula la salida de una organizacion del dataspace.

### Escenario

Una organizacion (fast-corp) sale del dataspace permanentemente. Se elimina su ParticipantContext, lo que dispara una eliminacion en cascada de todos sus recursos.

### Que se valida

1. **Eliminacion en cascada**: Una sola peticion DELETE elimina todo
2. **Irreversibilidad**: El participante ya no existe (404 en consultas posteriores)
3. **Recursos eliminados**: DIDs despublicados, claves revocadas, credenciales eliminadas, API key borrada del vault

### Flujo de peticiones

| Paso | Peticion | Que verifica |
|------|----------|--------------|
| 6.1 | `DELETE /participants/{id}` | Eliminacion exitosa (204) |
| 6.2 | `GET /participants/{id}` | 404 Not Found |

### Cascada de eliminacion

```
DELETE /participants/{id}
    |
    +-- 1. Despublicar todos los DIDs
    |
    +-- 2. Revocar todos los KeyPairs
    |
    +-- 3. Eliminar todas las VerifiableCredentials
    |
    +-- 4. Eliminar el ParticipantContext
    |
    +-- 5. Eliminar el API key del vault
```

---

## CU7: Administracion multi-tenant

### Que se pretende demostrar

Este caso de uso demuestra los **endpoints de administracion cross-tenant** que permiten a un administrador tener una vision global de todo el sistema. Estos endpoints requieren el rol `admin`.

### Escenario

El administrador del Identity Hub necesita auditar el estado de todos los participantes, DIDs, claves y credenciales del sistema.

### Que se valida

1. **Vision global**: Se pueden consultar todos los recursos de todos los participantes
2. **Autorizacion**: Estos endpoints solo funcionan con un token que tenga rol `admin`
3. **Paginacion**: Los endpoints soportan `offset` y `limit`

### Flujo de peticiones

| Paso | Peticion | Que verifica |
|------|----------|--------------|
| 7.1 | `GET /participants?offset=0&limit=50` | Lista todos los participantes |
| 7.2 | `GET /dids?offset=0&limit=50` | Lista todos los DIDs del sistema |
| 7.3 | `GET /keypairs?offset=0&limit=50` | Lista todos los KeyPairs del sistema |
| 7.4 | `GET /credentials?offset=0&limit=50` | Lista todas las credenciales del sistema |

### Diferencia con los endpoints por participante

| Endpoint | Alcance | Rol requerido |
|----------|---------|---------------|
| `GET /participants/{id}/keypairs` | Solo del participante | participant, admin |
| `GET /keypairs` | **Todos** los del sistema | admin |

---

## CU8: Regeneracion de token API (compromiso de credenciales)

### Que se pretende demostrar

Este caso de uso demuestra como un participante puede **regenerar su API key** cuando se sospecha que ha sido comprometida. El token anterior se invalida inmediatamente.

### Escenario

Se sospecha que el API key del participante acme-corp ha sido comprometido (filtracion, log expuesto, etc.). El participante regenera su token para invalidar el anterior.

### Que se valida

1. **Regeneracion**: Se genera un nuevo token y se devuelve en texto plano
2. **Invalidacion inmediata**: El token anterior deja de funcionar al instante
3. **Self-service**: El participante puede regenerar su propio token (no necesita el super-user)

### Flujo de peticiones

| Paso | Peticion | Que verifica |
|------|----------|--------------|
| 8.1 | `POST /participants/{id}/token` con `x-api-key: {{participantToken}}` | Nuevo token generado (200) |

### Nota importante

Esta peticion usa el **token del propio participante** (`participantToken`), no el del super-user. Esto demuestra que un participante puede gestionar su propia seguridad sin depender del administrador.

---

## CU9: Gestion de roles

### Que se pretende demostrar

Este caso de uso demuestra como gestionar los **roles de autorizacion** de un participante. Los roles determinan a que endpoints tiene acceso.

### Escenario

Se necesita otorgar privilegios de administrador a un participante, verificar que los tiene, y luego quitarselos.

### Que se valida

1. **Asignacion**: Se puede otorgar el rol `admin` a un participante
2. **Verificacion**: El campo `roles` del participante refleja el cambio
3. **Revocacion**: Se pueden quitar todos los roles
4. **Reemplazo absoluto**: El array de roles enviado reemplaza completamente los roles existentes (no es incremental)

### Flujo de peticiones

| Paso | Peticion | Que verifica |
|------|----------|--------------|
| 9.1 | `PUT /participants/{id}/roles` con `["admin"]` | Roles actualizados (204) |
| 9.2 | `GET /participants/{id}` | Participante tiene rol `admin` |
| 9.3 | `PUT /participants/{id}/roles` con `[]` | Roles eliminados (204) |

### Roles disponibles

| Rol | Acceso |
|-----|--------|
| `admin` | Todos los endpoints, incluyendo cross-tenant y gestion de otros participantes |
| (sin roles) | Solo sus propios recursos (sus DIDs, sus claves, sus credenciales) |

---

## Resumen de cobertura

### Endpoints cubiertos por caso de uso

| Endpoint | CU1 | CU2 | CU3 | CU4 | CU5 | CU6 | CU7 | CU8 | CU9 |
|----------|-----|-----|-----|-----|-----|-----|-----|-----|-----|
| `POST /participants/` | X | X | | | | | | | |
| `GET /participants/{id}` | | | | | | | | | X |
| `GET /participants` | | | | | | | X | | |
| `POST /participants/{id}/state` | X | | | | X | | | | |
| `POST /participants/{id}/token` | | | | | | | | X | |
| `PUT /participants/{id}/roles` | | | | | | | | | X |
| `DELETE /participants/{id}` | | | | | | X | | | |
| `POST /dids/state` | X | X | | | X | | | | |
| `POST /dids/query` | X | | X | X | | | | | |
| `POST /dids/publish` | | | | | | | | | |
| `POST /dids/unpublish` | | | | | | | | | |
| `GET /dids` | | | | | | | X | | |
| `POST /dids/{did}/endpoints` | | | | X | | | | | |
| `PATCH /dids/{did}/endpoints` | | | | X | | | | | |
| `DELETE /dids/{did}/endpoints` | | | | X | | | | | |
| `GET /participants/{id}/keypairs` | | | X | | | | | | |
| `POST /keypairs/{id}/rotate` | | | X | | | | | | |
| `POST /keypairs/{id}/revoke` | | | X | | | | | | |
| `GET /keypairs` | | | | | | | X | | |
| `GET /credentials` | | | | | | | X | | |

### Transiciones de estado cubiertas

**ParticipantContext:**
```
CREATED --[CU1]--> ACTIVATED --[CU5]--> DEACTIVATED --[CU5]--> ACTIVATED
                                                                    |
                                                              [CU6] DELETE
```

**DID Document:**
```
GENERATED --[CU1]--> PUBLISHED --[CU5]--> UNPUBLISHED --[CU5]--> PUBLISHED
```

**KeyPair:**
```
ACTIVE --[CU3]--> ROTATED --[CU3]--> REVOKED
```
