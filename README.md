# Activation Service

Serviço de ativação de produtos. Um sistema de origem pede a ativação de um produto para um cliente, a aplicação grava a solicitação, responde na hora e depois envia para um provedor externo em segundo plano, tentando de novo quando dá erro temporário.

Stack: Java 21, Spring Boot 3.5 (MVC, Data JPA/Hibernate, Security, Actuator), PostgreSQL 17, Flyway, Maven, Docker. Testes com JUnit 5, Mockito, Testcontainers, WireMock e ArchUnit.

## Sumário

- [Como executar](#como-executar)
- [API](#api)
- [Decisões técnicas](#decisões-técnicas)
- [Testes](#testes)
- [Cenário de análise](#cenário-de-análise)
- [Produção](#produção)
- [Pontos pendentes](#pontos-pendentes)
- [Melhorias futuras](#melhorias-futuras)
- [Uso de IA](#uso-de-ia)

---

## Como executar

Precisa de JDK 21 e Docker rodando. Não precisa instalar o Maven, o projeto usa o Maven Wrapper (`mvnw`).

### Build e testes

```bash
./mvnw verify          # Linux/macOS
mvnw.cmd verify        # Windows
```

Roda os testes unitários e os de integração. Os de integração sobem um PostgreSQL de verdade com Testcontainers, por isso precisam do Docker.

### Subindo tudo com Docker Compose

```bash
docker compose up -d --build
```

| Serviço    | Porta | O que é                                       |
|------------|-------|-----------------------------------------------|
| `app`      | 8080  | A aplicação                                   |
| `postgres` | 5432  | Banco de dados                                |
| `provider` | 8089  | Provedor externo simulado (WireMock)          |
| `auth`     | 8090  | Servidor OAuth 2.0 de testes, emite os tokens |

1. Pegar um token (o servidor de testes aceita qualquer `client_id`/`client_secret`):

```bash
TOKEN=$(curl -s -X POST localhost:8090/default/token \
  -d grant_type=client_credentials -d client_id=sistema-origem -d client_secret=segredo \
  --data-urlencode "scope=activation:read activation:write" | jq -r .access_token)
```

2. Criar e consultar uma solicitação:

```bash
curl -i -X POST localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -s localhost:8080/api/v1/requests/{id} -H "Authorization: Bearer $TOKEN"
```

O provedor simulado responde de acordo com o começo do `productCode`, assim dá pra testar cada cenário:

| `productCode` começa com | Provedor responde              | Status final                              |
|--------------------------|--------------------------------|-------------------------------------------|
| qualquer outro           | 200 depois de 300 ms           | `COMPLETED`                               |
| `INVALID`                | 400                            | `FAILED`, sem tentar de novo              |
| `ERROR`                  | 500                            | Tenta de novo com backoff, depois `FAILED` |
| `SLOW`                   | 200 depois de 10 s (passa do timeout) | Tenta de novo por timeout, depois `FAILED` |
| `BUSY`                   | 503 com `Retry-After: 10`      | Tenta de novo depois de 10 s              |

Health e métricas: `/actuator/health/liveness`, `/actuator/health/readiness` e `/actuator/prometheus`.

Pra rodar só a aplicação fora do Docker: `./mvnw spring-boot:run`, com o Postgres, o provedor e o servidor de auth no ar. Os valores padrão do `application.yml` já apontam pras portas do compose.

### Configuração

Tudo vem por variável de ambiente:

| Variável | Padrão |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/activation`, `activation`, `activation` |
| `PROVIDER_BASE_URL` | `http://localhost:8089` |
| `PROVIDER_CONNECT_TIMEOUT` / `PROVIDER_READ_TIMEOUT` | `2s` / `5s` |
| `PROCESSING_MAX_ATTEMPTS` | `5` |
| `JWT_JWK_SET_URI`, `JWT_ISSUER`, `JWT_AUDIENCE` | servidor de auth do compose |

---

## API

| Método | Rota | Escopo | O que faz |
|---|---|---|---|
| POST | `/api/v1/requests` | `activation:write` | Cria a solicitação |
| GET | `/api/v1/requests/{id}` | `activation:read` | Consulta a solicitação e o status |
| GET | `/api/v1/requests?status=&customerId=&page=&size=` | `activation:read` | Lista paginada, mais recentes primeiro (`size` até 100) |

No POST os três campos são obrigatórios, com até 64 caracteres, e só aceitam letras, números, `.`, `_` e `-`.

Exemplo de resposta (datas em UTC). Além do que o enunciado pede, coloquei `attempts` e `failureReason`:

```json
{
  "id": "0b7f3c1e-5d2a-4c4e-9a51-2f8d7b1e6c90",
  "externalId": "EXT-12345",
  "customerId": "CUST-10001",
  "productCode": "INTERNET-500",
  "status": "PENDING",
  "attempts": 0,
  "createdAt": "2026-10-26T14:03:11Z",
  "updatedAt": "2026-10-26T14:03:11Z"
}
```

Erros saem no formato Problem Details (RFC 9457) com um `traceId`, que é o mesmo valor do header `X-Correlation-Id`.

| Situação | HTTP |
|---|---|
| Dados inválidos, JSON quebrado, parâmetro inválido | 400, com a lista de erros por campo |
| Sem token ou token inválido | 401 |
| Token sem o escopo | 403 |
| Solicitação não existe (ou é de outro consumidor) | 404 |
| `externalId` já usado com dados diferentes | 409 |
| Erro inesperado | 500, mensagem genérica (o detalhe fica só no log) |

Como o envio pro provedor é assíncrono, erro do provedor não vira erro HTTP pra quem criou. Ele aparece no `status` e no `failureReason`.

---

## Decisões técnicas

### Estrutura

Separei por camada, sem complicar:

```
api/           controller, DTOs (records) e tratamento global de erros
application/   regras: criar/consultar, processar, retentativa
domain/        entidade, estados e repositório
provider/      cliente HTTP do provedor externo
config/        segurança e relógio
observability/ correlation id
```

A camada `application` define a interface `ActivationProvider` e o pacote `provider` implementa. Assim a regra de processamento não depende de HTTP e dá pra testar sem servidor. Só fiz essa abstração porque é a única dependência externa. O `ArchitectureTest` (ArchUnit) garante que as camadas não se misturam.

### Estados

```
PENDING ──► PROCESSING ──► COMPLETED
   ▲             │
   └─────────────┼────────► FAILED
   (falha temporária)
```

- As transições ficam no enum (`RequestStatus.canTransitionTo`) e a entidade só muda de estado por métodos (`startProcessing`, `complete`, `fail`, `scheduleRetry`), sem setters. Transição inválida lança `InvalidStatusTransitionException`.
- Na falha temporária a solicitação volta pra `PENDING`, com `attempts` somado e `nextAttemptAt` no futuro. Assim `PROCESSING` quer dizer só "tem uma chamada acontecendo agora", e se uma solicitação ficar muito tempo em `PROCESSING` é porque a instância caiu no meio.
- Pra escrita concorrente uso `@Version` (lock otimista).

### Duplicidade

Quem garante que não duplica é a constraint `UNIQUE (external_id)` no banco. Só consultar antes de inserir não resolve: duas requisições ao mesmo tempo consultam, nenhuma acha nada e as duas inserem.

O fluxo no `ActivationRequestService.create`:

1. Procura pelo `externalId`. Se achar, vai pro passo 3.
2. Se não achar, insere. Se a constraint estourar, é porque outra requisição chegou primeiro, e vai pro passo 3.
3. Compara o que já existe com o que chegou e decide a resposta.

O método não é `@Transactional` de propósito: no PostgreSQL, depois de uma violação de constraint a transação fica abortada, então a releitura do passo 2 precisa de uma transação nova.

| Cenário | Resposta | Por quê |
|---|---|---|
| Mesmo `externalId`, mesmo conteúdo, mesmo consumidor | 200 com o registro que já existe | É um retry. O cliente não sabe se a primeira chamada deu certo, então devolvo o mesmo `id` e o POST fica idempotente. O 201 fica só pra quando cria de verdade. |
| Mesmo `externalId`, conteúdo diferente | 409 | O cliente reaproveitou o id pra outra coisa. Isso é erro dele e não dá pra esconder. |
| Mesmo `externalId`, outro consumidor | 409, sem dados | Não mostra nada da solicitação do outro sistema. |
| Duas requisições iguais ao mesmo tempo | Uma recebe 201, a outra 200 com o mesmo `id` | Testado com 16 threads contra o PostgreSQL real. |

### Integração com o provedor

É assíncrona. O POST grava como `PENDING` e responde na hora, e um scheduler pega as pendentes e chama o provedor. Fiz assim porque:

- o tempo da API não depende do provedor;
- se o provedor ficar lento, as threads e conexões do banco não ficam presas esperando;
- as retentativas acontecem sem o cliente ficar esperando.

A própria tabela funciona como outbox: gravar a solicitação já é registrar que ela precisa ser enviada. Se a aplicação cair depois do commit, ela continua `PENDING` e é enviada quando voltar.

O `ActivationProcessor` faz em três passos, e a chamada HTTP nunca fica dentro de transação:

1. Transação curta: `SELECT ... FOR UPDATE SKIP LOCKED` pega um lote de pendentes, marca como `PROCESSING` e faz commit.
2. Chama o provedor, sem transação e sem lock.
3. Transação curta: grava o resultado.

O `SKIP LOCKED` deixa rodar várias instâncias sem duas pegarem a mesma solicitação.

Outros pontos:

- Solicitações presas: se a instância cair no passo 2, um job (`recoverStuckRequests`, a cada 1 min) devolve pra `PENDING` o que está em `PROCESSING` há mais de 2 min.
- Idempotência: depois de um timeout não dá pra saber se o provedor ativou ou não. Por isso toda chamada manda `Idempotency-Key` com o id da solicitação, igual em todas as tentativas.
- Timeouts: 2 s pra conectar e 5 s pra ler, configuráveis.
- Retentativas: backoff exponencial com jitter (2 s, 4 s, 8 s... até 5 min), no máximo 5 tentativas. O jitter evita que tudo que falhou junto volte junto.

| Provedor responde | Status | Tenta de novo? |
|---|---|---|
| 2xx | `COMPLETED` | Não |
| 400 e outros 4xx (menos 408 e 429) | `FAILED` | Não, mandar de novo vai dar o mesmo erro |
| 500, 502, 504, 408 | `PENDING` | Sim, com backoff |
| Timeout ou erro de rede | `PENDING` | Sim, com backoff (seguro por causa da chave de idempotência) |
| 429 ou 503 com `Retry-After` | `PENDING` | Sim, no tempo que o provedor pediu (até 5 min) |
| Chegou no limite de tentativas | `FAILED` | Não |

Essa regra está no `HttpActivationProvider.classify` e testada no `HttpActivationProviderTest`.

### Segurança

Implementei com Spring Security como OAuth 2.0 Resource Server.

- Fluxo client credentials, porque quem chama a API são sistemas, não pessoas.
- O JWT é validado: assinatura (pelas chaves do JWKS, com cache e suporte a rotação), emissor (`iss`), audiência (`aud = activation-service`) e expiração.
- Escopos: `activation:write` pra criar e `activation:read` pra consultar. Qualquer outra rota é negada.
- Cada solicitação guarda o `sub` do token que criou. Consulta e listagem filtram por ele, e quem pede o id de outro consumidor recebe 404 (e não 403, pra não confirmar que existe).
- A credencial pro provedor externo eu não implementei, porque o mock não pede. Ficaria num cofre de segredos (Secrets Manager, Key Vault ou Vault), injetada por variável de ambiente, nunca no repositório nem em log.

### Índices

| Índice | Pra quê |
|---|---|
| `UNIQUE (external_id)` | Duplicidade e busca no POST |
| `(client_id, status, created_at desc)` | Listagem por status |
| `(client_id, customer_id, created_at desc)` | Listagem por cliente |
| `(next_attempt_at) WHERE status = 'PENDING'` | Busca do scheduler (índice parcial, só com o que está na fila) |
| `(updated_at) WHERE status = 'PROCESSING'` | Recuperação das presas |

### Erros

O `ApiExceptionHandler` usa `@RestControllerAdvice` e estende `ResponseEntityExceptionHandler`, então pega tanto as minhas exceções quanto as do Spring (JSON quebrado, parâmetro errado etc.) e devolve tudo como `ProblemDetail` com `traceId`. Erro inesperado vai com stack trace só pro log.

---

## Testes

| Teste | Tipo | O que cobre |
|---|---|---|
| `ActivationRequestTest` | Unitário | Transições de estado válidas e inválidas |
| `RetryBackoffTest` | Unitário | Backoff, jitter, teto e `Retry-After` |
| `HttpActivationProviderTest` | Unitário | Decisão pra cada resposta do provedor |
| `ActivationRequestControllerTest` | `@WebMvcTest` | Contrato HTTP: 201, 200 no retry, 400, 404, 409, 500, 401/403 |
| `ActivationRequestApiIntegrationTest` | Integração com PostgreSQL | Criação, consulta, duplicidade, concorrência com 16 threads, isolamento entre consumidores, paginação |
| `ActivationProcessingIntegrationTest` | Integração com PostgreSQL e WireMock | Sucesso, 400, 500 até o limite, timeout com a mesma chave, `Retry-After`, recuperação de presas |
| `ArchitectureTest` | ArchUnit | Dependência entre camadas |

- Usei PostgreSQL real em vez de H2 porque a duplicidade e o `SKIP LOCKED` dependem do comportamento real do banco.
- Os testes de tempo usam um relógio controlável (`MutableClock`), sem `sleep`.
- Nos testes de integração o scheduler fica desligado e o teste chama o processador direto, pra não depender de tempo.

---

## Cenário de análise

> API: 300 ms → 2,5 s · CPU 45% · memória 60% · consultas: 50 ms → 1,2 s · provedor: 400 ms → 3 s

O que eu olharia primeiro: CPU e memória normais mostram que a aplicação não está trabalhando mais, ela está esperando alguma coisa. Então começaria pelo que acaba quando tem espera: pool de conexões do banco, threads do Tomcat e conexões HTTP com o provedor. Também veria se teve deploy, aumento de volume ou mudança no provedor na hora que começou.

Como separar aplicação, banco e integração:

- Banco: comparar o tempo que o banco diz que levou (`pg_stat_statements`, slow query log, `EXPLAIN ANALYZE`) com o tempo que a aplicação mede. Se no banco continua 50 ms e na aplicação dá 1,2 s, o tempo está na espera por conexão (`hikaricp_connections_pending`), não na consulta.
- Integração: latência medida do nosso lado contra a que o provedor informa, se ele tiver.
- Aplicação: tracing pra ver onde estão os 2,5 s e thread dump pra ver onde as threads estão paradas (`getConnection`, leitura de socket ou lock).

Logs e métricas que ajudam:

- p50/p95/p99 por endpoint;
- Hikari: conexões ativas, pendentes e tempo pra pegar conexão;
- threads ocupadas do Tomcat;
- latência e erro do provedor;
- tamanho da fila `PENDING` e idade da mais antiga;
- no PostgreSQL: `pg_stat_statements`, locks e autovacuum;
- logs com correlation id pra seguir uma requisição lenta;
- GC da JVM, pra descartar.

Hipótese de causa comum: a lentidão do provedor esgotando o pool de conexões. Se a chamada ao provedor roda dentro de uma transação, cada chamada de 3 s segura uma conexão por 3 s. O pool acaba, as outras requisições ficam esperando conexão e isso aparece como "consulta lenta", mesmo com o banco normal. A API inteira fica lenta e a CPU fica baixa porque está todo mundo esperando.

Pra confirmar: `hikaricp_connections_pending > 0` nos mesmos momentos em que o provedor está lento, thread dump com várias threads em `getConnection` e consultas normais no `pg_stat_statements`.

Outra hipótese seria o banco ficar lento sozinho (tabela cresceu sem índice, lock longo, autovacuum atrasado), mas isso não explicaria o provedor lento junto, a não ser que dividam infraestrutura.

Esse serviço já foi feito pensando nisso: a chamada ao provedor é assíncrona e fora de transação, então um provedor lento só aumenta a fila de `PENDING` e não afeta a API.

Melhorias, dependendo da causa:

- tirar chamada remota de dentro de transação;
- ajustar timeouts pra falhar rápido;
- circuit breaker e bulkhead (limite de chamadas simultâneas ao provedor);
- dimensionar o pool de conexões;
- índices pras consultas lentas e paginação;
- cache de leitura, se fizer sentido;
- se o problema for o provedor, falar com quem mantém e aplicar backpressure.

Como validar depois de publicar:

- comparar p95/p99, tempo pra pegar conexão e taxa de erro antes e depois, na mesma janela de tráfego;
- publicar como canário primeiro e acompanhar o dashboard;
- antes disso, reproduzir num teste de carga com o provedor simulado em 3 s e ver se a API mantém a latência.

---

## Produção

Como eu rodaria:

- A imagem do `Dockerfile` num orquestrador (Kubernetes, ECS ou Cloud Run), com pelo menos 2 réplicas.
- Probes: liveness em `/actuator/health/liveness` e readiness em `/actuator/health/readiness`. A readiness inclui o banco, então instância sem banco sai do balanceador.
- Banco: PostgreSQL gerenciado (RDS, Cloud SQL ou Azure Database), com backup e réplica. O Flyway roda as migrations na subida; com muitas réplicas eu passaria pra um job no pipeline.
- Configuração por variável de ambiente e segredos num cofre do provedor cloud.
- Desligamento gracioso, logs em JSON num agregador, métricas no Prometheus/Grafana e alertas pra fila `PENDING` crescendo, `FAILED` e erro do provedor.

A imagem Docker:

- build em múltiplos estágios, a final só com a JRE (Alpine) e o jar em camadas;
- roda com usuário não-root;
- heap ajustado ao limite do contêiner (`MaxRAMPercentage=75`) e `ExitOnOutOfMemoryError`;
- `HEALTHCHECK` configurado.

Como microsserviço em cloud:

- Várias instâncias: a duplicidade é garantida pelo banco e o processamento usa `SKIP LOCKED`, então dá pra escalar horizontalmente sem nada a mais.
- Avisar outros serviços: publicaria `ActivationCompleted` ou `ActivationFailed` num tópico (Kafka, SNS/SQS ou Pub/Sub), usando outbox pra não perder evento. Os consumidores precisam ser idempotentes.
- CI/CD: o pipeline em `.github/workflows/ci.yml` já roda `./mvnw verify` e o build da imagem em cada push e PR. Pro deploy eu acrescentaria scan da imagem (Trivy), publicação no registry com a tag do commit, deploy automático em homologação com smoke test e depois produção com aprovação, em rolling ou canário.

---

## Pontos pendentes

O que não fiz e como faria:

- Circuit breaker: como o envio é assíncrono e tem backoff, provedor fora do ar só atrasa a fila. Se o volume crescer, colocaria Resilience4j em volta do `HttpActivationProvider`.
- Envio em sequência: cada instância manda o lote uma solicitação por vez, então uma lenta atrasa as próximas. Melhoraria mandando em paralelo com limite (virtual threads e semáforo).
- Credencial do provedor externo: só descrita, na parte de segurança.
- Rate limiting e tamanho máximo do corpo: ficariam no API Gateway ou ingress. Na aplicação cada campo já tem limite de 64 caracteres.
- 401 e 403 saem sem corpo `ProblemDetail`. Pra padronizar bastaria um `AuthenticationEntryPoint` e um `AccessDeniedHandler`.
- Tracing distribuído: hoje o correlation id vai da requisição até o provedor. O próximo passo seria Micrometer Tracing com OpenTelemetry.
- Métrica de quantidade por status e idade da `PENDING` mais antiga.
- ADRs: as decisões estão aqui no README; num time cada uma viraria um ADR em `docs/adr/`.

## Melhorias futuras

- Eventos de conclusão num broker com outbox.
- Reconciliação com o provedor pras `FAILED` por limite de tentativas, consultando o status real antes de dar como falha.
- Endpoint administrativo pra reprocessar `FAILED`, com escopo próprio.
- Limpeza ou particionamento por data das solicitações antigas.
- Documentação OpenAPI (springdoc) e testes de contrato.
- Teste de carga (Gatling ou k6) no pipeline.

## Uso de IA

- Utilizei IA para escrita de código fazendo as Análises do codigo um por um nos arquivos, projetei a arquitetura analisando o desafio e criei cada task no github, assim eu consegui esquematizar e preparar melhor o comando para IA escrever o codigo qual era o objetivo e o que eu esperava de resultado e seguindo minhas regras, após isso eu faço uma análise do que foi feito, caso tenha algo que não gostei do padrão ou de erro no teste, eu altero na mão para o que eu quero, utilizei a IA para codar para mim classes como por exemplo o createactivationrequest que fiz como um DTO padronizando a entrada, o apiexcpectionhandler fazendo tratamento global de erros usando o @RestControllerAdvice, o PageResponse fazendo o DTO de saída da listagem paginada e etc...
