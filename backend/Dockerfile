# MAMA Trusted Exit backend — production image.
#
# Build context is the REPOSITORY ROOT (the Gradle build needs settings.gradle.kts
# and the wrapper), so build via docker-compose (which sets context: ..) or:
#   docker build -f backend/Dockerfile -t mama-backend .
#
# No secret is ever baked in: the SMS.RU api_id is supplied only at run time
# through the environment (see docker-compose.yml / .env). .dockerignore keeps
# any local .env, build output and .git out of the image.

# ---- build stage: compile and assemble the runnable distribution ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
# installDist produces build/install/backend/{bin/backend, lib/*.jar}.
RUN ./gradlew --no-daemon :backend:installDist

# ---- runtime stage: a slim JRE with only the distribution ----
FROM eclipse-temurin:17-jre
# Run as a non-root user.
RUN useradd --system --uid 10001 --create-home mama
WORKDIR /app
COPY --from=build /src/backend/build/install/backend /app
USER mama
ENV PORT=8080
EXPOSE 8080
# The launcher reads MAMA_SMSRU_API_ID and PORT from the environment at run time.
ENTRYPOINT ["/app/bin/backend"]
