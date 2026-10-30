# --- build ---
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Dependências numa camada própria: só baixam de novo quando o pom.xml muda.
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
# Os testes rodam no CI (precisam de Docker para o Testcontainers); aqui só empacota.
RUN ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/activation-service-*.jar extract --layers --launcher --destination extracted

# --- runtime ---
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

# Camadas da menos para a mais volátil, para aproveitar o cache entre versões.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8080

# Heap proporcional ao limite de memória do contêiner; morre em OOM para o orquestrador reiniciar.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
