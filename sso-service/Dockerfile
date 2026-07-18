# The identity service as a shippable image. Same pattern as the bank:
# Maven builds, a bare JRE runs, config arrives as environment.
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
EXPOSE 8090
# every address the service needs comes from the environment. SSO_DB_URL=memory
# boots the demo directory; a jdbc:postgresql:// URL boots the real one.
ENTRYPOINT ["java", "-cp", "classes:deps/*", "dev.b4rruf3t.sso.Main"]
