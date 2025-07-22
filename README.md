# Async Payment Processor Worker

Este projeto é o serviço de worker assíncrono para a [Rinha de Backend 2025](https://github.com/zanfranceschi/rinha-de-backend-2025).

## Funcionalidades

* Consome mensagens de pagamento de uma fila Redis.
* Processa pagamentos utilizando serviços externos (Payment Processors Default e Fallback).
* Gerencia a saúde dos Payment Processors através de verificações periódicas.
* Lida com retries em caso de falhas no processamento.

## Tecnologias Principais

* Java 24
* Spring Boot
* Redis (para fila e cache de saúde)
* RestTemplate (para chamadas HTTP externas)
* Virtual Threads (para alta concorrência)

## Como Rodar

Para executar este serviço (geralmente em conjunto com a `API Payment Processor` e outros componentes), utilize o Docker Compose:

```bash
docker-compose up --build
