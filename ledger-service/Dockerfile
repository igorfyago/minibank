# The bank as a shippable image. Multi-stage: Maven builds, a bare JRE runs.
# No framework means no fat-jar magic is needed — compiled classes plus the
# three driver jars on a classpath is the whole deployment artifact.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package dependency:copy-dependencies

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/classes ./classes
COPY --from=build /app/target/dependency ./deps
EXPOSE 8080
# every address the bank needs comes from the environment — the same image
# runs on a laptop, in compose, or on a cluster. Config is location, not code.
ENTRYPOINT ["java", "-cp", "classes:deps/*", "dev.minibank.ledger.Main"]
