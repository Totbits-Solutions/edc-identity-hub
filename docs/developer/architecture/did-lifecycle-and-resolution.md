# Ciclo de vida del DID en Identity Hub: estados, eventos y resolución

## Diagrama de estados

```
┌──────────┐   store()    ┌─────────────┐   publish()    ┌─────────────┐   unpublish()   ┌──────────────┐
│ INITIAL  │─────────────▶│  GENERATED  │──────────────▶│  PUBLISHED  │───────────────▶│ UNPUBLISHED  │
│  (100)   │              │    (200)    │               │    (300)    │                │    (400)     │
└──────────┘              └─────────────┘               └─────────────┘                └──────────────┘
 En memoria                En BD, no                    Accesible por HTTP              Retirado del VDR,
 todavía                   publicado                    (DidWebController)              ya no resoluble
```

- **INITIAL (100):** El `DidResource` se ha creado en memoria pero no se ha persistido.
- **GENERATED (200):** Almacenado en base de datos. No publicado en ningún VDR.
- **PUBLISHED (300):** Publicado y accesible vía HTTP. El `DidWebController` sirve el documento.
- **UNPUBLISHED (400):** Retirado del VDR. Ya no es resoluble externamente.

**Restriccion:** No se puede eliminar (`deleteById`) un DID en estado `PUBLISHED`. Primero hay que hacer `unpublish`.

## Modelo DidResource

Campos principales:

| Campo | Tipo | Descripcion |
|-------|------|-------------|
| `did` | String | Identificador DID (ej: `did:web:example.com`) |
| `state` | int | Codigo del estado actual (100, 200, 300, 400) |
| `document` | DidDocument | El DID Document W3C |
| `participantContextId` | String | Contexto de participante al que pertenece |
| `stateTimestamp` | long | Timestamp del ultimo cambio de estado |
| `createTimestamp` | long | Timestamp de creacion |

## Flujo completo: desde la creacion del participante hasta la resolucion HTTP

```
 ┌─────────────────────────────────────────────────────────────┐
 │  1. Se crea un ParticipantContext (via Identity API)        │
 └──────────────────────────┬──────────────────────────────────┘
                            │ emite evento ParticipantContextCreated
                            ▼
 ┌─────────────────────────────────────────────────────────────┐
 │  2. ParticipantContextEventCoordinator                      │
 │     - Extrae el DID del manifest                            │
 │     - Construye un DidDocument con los service endpoints    │
 │     - Llama a DidDocumentService.store(doc, participantId)  │
 │     - Estado: INITIAL → GENERATED                           │
 └──────────────────────────┬──────────────────────────────────┘
                            │ si manifest.isActive() == true
                            ▼
 ┌─────────────────────────────────────────────────────────────┐
 │  3. Se activa el ParticipantContext                          │
 │     → emite evento ParticipantContextUpdated (ACTIVATED)    │
 └──────────────────────────┬──────────────────────────────────┘
                            │ DidDocumentServiceImpl escucha este evento
                            ▼
 ┌─────────────────────────────────────────────────────────────┐
 │  4. DidDocumentServiceImpl.publish(did)                     │
 │     - Verifica que el ParticipantContext este ACTIVATED      │
 │     - Busca el publisher en DidDocumentPublisherRegistry    │
 │       (extrae el metodo del DID, ej: "did:web" → busca     │
 │        el publisher registrado para "did:web")              │
 │     - Delega a LocalDidPublisher.publish(did)               │
 └──────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
 ┌─────────────────────────────────────────────────────────────┐
 │  5. LocalDidPublisher.publish(did)                          │
 │     - Busca el DidResource en el store                      │
 │     - Transiciona estado: GENERATED → PUBLISHED             │
 │     - Actualiza el store                                    │
 │     - Notifica listeners → emite DidDocumentPublished event │
 └──────────────────────────┬──────────────────────────────────┘
                            │
                            ▼
 ┌─────────────────────────────────────────────────────────────┐
 │  6. DidWebController sirve el documento por HTTP            │
 │                                                             │
 │  GET https://example.com/.well-known/did.json               │
 │     - Parsea la URL → convierte a DID                       │
 │     - Busca en el store documentos con estado PUBLISHED     │
 │     - Devuelve el DidDocument JSON                          │
 └─────────────────────────────────────────────────────────────┘
```

## Eventos que modifican el DID durante su vida

`DidDocumentServiceImpl` escucha 3 tipos de eventos:

```
┌────────────────────────────┐     ┌──────────────────────────────────────────┐
│ ParticipantContextUpdated  │────▶│ ACTIVATED  → publish() todos los DIDs    │
│                            │     │ DEACTIVATED → unpublish() todos los DIDs │
└────────────────────────────┘     └──────────────────────────────────────────┘

┌────────────────────────────┐     ┌──────────────────────────────────────────┐
│ KeyPairActivated           │────▶│ Anade la clave publica como              │
│                            │     │ verificationMethod al DidDocument        │
│                            │     │ + auto-publish                           │
└────────────────────────────┘     └──────────────────────────────────────────┘

┌────────────────────────────┐     ┌──────────────────────────────────────────┐
│ KeyPairRevoked             │────▶│ Elimina el verificationMethod            │
│                            │     │ del DidDocument                          │
└────────────────────────────┘     └──────────────────────────────────────────┘
```

## Eliminacion del participante

```
ParticipantContextDeleting evento
        │
        ▼
  unpublish(did)          → PUBLISHED → UNPUBLISHED
        │
        ▼
  deleteById(did)         → Elimina del store completamente
        │
        ▼
  Revoca todos los KeyPairs
```

## Resolucion did:web

Cuando un tercero quiere resolver un DID:

```
GET https://example.com:8080/path/.well-known/did.json
                          │
                DidWebController recibe la request
                          │
                DidWebParser convierte la URL:
                  1. Quita "did.json"
                  2. Quita "/.well-known"
                  3. Reemplaza "/" por ":"
                  4. URL-encode del host:port
                  5. Resultado: did:web:example.com%3A8080:path
                          │
                Busca en el store: estado == PUBLISHED
                y did == "did:web:example.com%3A8080:path"
                          │
                Devuelve el DidDocument JSON (o null)
```

## API REST para gestion de DIDs

Base path: `/unstable/participants/{participantContextId}/dids`

| Metodo | Path | Que hace |
|--------|------|----------|
| POST | `/publish` | Publica un DID |
| POST | `/unpublish` | Despublica un DID |
| POST | `/query` | Busca DIDs con filtros |
| POST | `/state` | Obtiene el estado actual |
| POST | `/{did}/endpoints` | Anade service endpoint |
| PATCH | `/{did}/endpoints` | Reemplaza service endpoint |
| DELETE | `/{did}/endpoints` | Elimina service endpoint |

Los endpoints de `endpoints` soportan un parametro `autoPublish`: si esta activo, re-publica automaticamente despues de modificar el documento.

Endpoint global de admin: `GET /unstable/dids/` — devuelve todos los DIDs (con paginacion).

## Extensibilidad: otros metodos DID

La unica implementacion incluida es `did:web` via `LocalDidPublisher`. Para soportar otros metodos (`did:ion`, `did:key`, blockchain, etc.), se implementa la interfaz `DidDocumentPublisher` y se registra en el `DidDocumentPublisherRegistry`:

```java
// En tu extension personalizada:
registry.addPublisher("did:ion", new MiIonDidPublisher());
```

Solo puede haber un publisher por metodo DID.

## Ubicacion del codigo fuente

| Componente | Ruta |
|-----------|------|
| DidResource (modelo) | `spi/did-spi/.../model/DidResource.java` |
| DidState (enum de estados) | `spi/did-spi/.../model/DidState.java` |
| DidDocumentPublisher (interfaz SPI) | `spi/did-spi/.../DidDocumentPublisher.java` |
| DidDocumentPublisherRegistry (interfaz) | `spi/did-spi/.../DidDocumentPublisherRegistry.java` |
| DidDocumentService (interfaz) | `spi/did-spi/.../DidDocumentService.java` |
| DidResourceStore (persistencia) | `spi/did-spi/.../store/DidResourceStore.java` |
| DidDocumentServiceImpl (logica central) | `core/identity-hub-did/.../DidDocumentServiceImpl.java` |
| DidDocumentPublisherRegistryImpl | `core/identity-hub-did/.../DidDocumentPublisherRegistryImpl.java` |
| DidServicesExtension (wiring) | `core/identity-hub-did/.../DidServicesExtension.java` |
| LocalDidPublisher (impl did:web) | `extensions/did/local-did-publisher/.../LocalDidPublisher.java` |
| DidWebController (HTTP endpoint) | `extensions/did/local-did-publisher/.../DidWebController.java` |
| DidWebParser (URL a DID) | `spi/did-spi/.../DidWebParser.java` |
| ParticipantContextEventCoordinator | `core/identity-hub-participants/.../ParticipantContextEventCoordinator.java` |
| DidManagementApiController (REST API) | `extensions/api/identity-api/did-api/.../DidManagementApiController.java` |
| Eventos DID (observable/listener) | `spi/did-spi/.../events/` |
| DidDocumentListenerImpl (emite eventos) | `extensions/did/local-did-publisher/.../DidDocumentListenerImpl.java` |
