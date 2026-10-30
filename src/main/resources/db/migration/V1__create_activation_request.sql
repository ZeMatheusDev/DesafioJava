create table activation_request (
    id              uuid         primary key,
    external_id     varchar(64)  not null,
    customer_id     varchar(64)  not null,
    product_code    varchar(64)  not null,
    client_id       varchar(128) not null,
    status          varchar(20)  not null,
    attempts        integer      not null default 0,
    next_attempt_at timestamptz,
    failure_reason  varchar(500),
    correlation_id  varchar(64),
    created_at      timestamptz  not null,
    updated_at      timestamptz  not null,
    version         bigint       not null default 0,
    constraint uk_activation_request_external_id unique (external_id),
    constraint ck_activation_request_status check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- Listagem: sempre filtrada pelo consumidor, opcionalmente por status/cliente, ordenada por data.
create index ix_activation_request_client_status_created
    on activation_request (client_id, status, created_at desc);
create index ix_activation_request_client_customer_created
    on activation_request (client_id, customer_id, created_at desc);

-- Fila de processamento: índices parciais pequenos, só com as linhas que o scheduler procura.
create index ix_activation_request_pending_due
    on activation_request (next_attempt_at) where status = 'PENDING';
create index ix_activation_request_processing_updated
    on activation_request (updated_at) where status = 'PROCESSING';
