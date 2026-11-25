# Builds Faultline from the GitHub repo into one container: the Java backend also serves the React site.
# Upload this file and README.md to a Hugging Face Docker Space. After pushing new code to GitHub,
# use the Space's "Factory rebuild" so it clones the latest commit.

FROM node:22-alpine AS web
RUN apk add --no-cache git
ARG REPO=https://github.com/Hiranyam1003/faultline.git
RUN git clone --depth 1 "$REPO" /src
WORKDIR /src/frontend
RUN npm ci && npm run build

FROM maven:3.9-eclipse-temurin-21 AS api
COPY --from=web /src /src
RUN cp -r /src/frontend/dist /src/backend/src/main/resources/static
WORKDIR /src/backend
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=api /src/backend/target/pwaudit-0.0.1-SNAPSHOT.jar app.jar
COPY --from=api /src/data data
RUN rm -rf data/cache && chown -R 1000 /app
USER 1000
EXPOSE 7860
CMD ["java", "-Xmx2g", "-jar", "app.jar", "--server.port=7860", "--pwaudit.data-dir=/app/data"]
