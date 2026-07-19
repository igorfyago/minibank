# The bank as a shippable image. Multi-stage: Maven builds, a bare JRE runs.
# No framework means no fat-jar magic is needed · compiled classes plus the
# driver jars on a classpath is the whole deployment artifact.
#
# BUILD CONTEXT IS THE REPOSITORY ROOT, not this directory:
#
#     docker build -t minibank:local -f ledger-service/Dockerfile .
#
# and that changed when the bank started validating tokens. ledger-service now
# depends on ../sso-client, which is a sibling module published to no
# repository, so a build whose context is this directory cannot resolve it and
# fails before it compiles a line. The root pom.xml predicted this exactly: it
# is an aggregator rather than a parent PRECISELY so these images could keep
# building from a module directory, and it says in as many words that teaching
# the Dockerfiles to build from the root "belongs in its own change". This is
# that change, made in the same breath as the dependency that forced it,
# because a dependency that breaks the deploy has not landed.
#
# sso-service is untouched and still builds from its own directory · it has no
# sibling dependency, so nothing forced it.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Every module pom, before any source. Two reasons, and the second is not
# obvious: the usual one is Docker layer caching, so editing a .java file does
# not re-download the internet. The other is that Maven reads the WHOLE module
# list to build its reactor graph even when -pl restricts what actually gets
# built, so sso-service/pom.xml has to be here even though nothing in this
# image comes from it. Omit it and the build fails on a missing module.
COPY pom.xml .
COPY sso-client/pom.xml sso-client/
COPY sso-service/pom.xml sso-service/
COPY ledger-service/pom.xml ledger-service/
RUN mvn -q -B -pl ledger-service -am dependency:go-offline

# -am ("also make") builds sso-client first, in the same reactor, so the jar
# the bank links against is the one in this tree rather than one somebody
# remembered to install by hand.
COPY sso-client/src ./sso-client/src
COPY ledger-service/src ./ledger-service/src
RUN mvn -q -B -DskipTests -pl ledger-service -am package dependency:copy-dependencies

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/ledger-service/target/classes ./classes
COPY --from=build /build/ledger-service/target/dependency ./deps
EXPOSE 8080
# every address the bank needs comes from the environment · the same image
# runs on a laptop, in compose, or on a cluster. Config is location, not code.
# SSO_ISSUER and BANK_IDENTITY_ENFORCE are read here too · see Main and
# Enforcement. Neither has to be set: the issuer defaults to the real one, and
# enforcement defaults to off, which is the behaviour that predates identity.
ENTRYPOINT ["java", "-cp", "classes:deps/*", "dev.minibank.ledger.Main"]
